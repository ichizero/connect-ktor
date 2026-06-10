package io.github.ichizero.connect.ktor

import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.sayResponse
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.identity
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Covers the two enforcement points of [UnaryCompressionGuard]:
 *
 *  - the `Content-Encoding` check runs before the route handler (and before any body
 *    deserialization, even with an application-scoped `ContentNegotiation`), and
 *  - the `maxDecompressedBytes` cap observes the post-decompression bytes when
 *    `ContentNegotiation` is installed after the guard in the same route scope.
 *
 * The "realistic configuration" tests pin the recommended plugin layout (application-scoped
 * `Compression`, route-scoped guard followed by `ContentNegotiation` with the Connect
 * converters); if Ktor changes its pipeline phase layout or interceptor ordering these tests
 * will fail.
 */
class UnaryCompressionGuardTest : FunSpec({

    fun gzip(payload: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(payload) }
        return sink.toByteArray()
    }

    fun gzip(payload: String): ByteArray = gzip(payload.toByteArray())

    class HandlerProbe {
        var invoked: Boolean = false
    }

    fun Application.withGuard(
        probe: HandlerProbe,
        installCompression: Boolean,
        maxDecompressedBytes: Long? = null,
    ) {
        if (installCompression) {
            install(Compression) {
                gzip()
                identity()
            }
        }
        routing {
            install(UnaryCompressionGuard) {
                supportedEncodings = setOf("gzip", "identity")
                this.maxDecompressedBytes = maxDecompressedBytes
            }
            post("/echo") {
                probe.invoked = true
                val body = call.receiveText()
                call.respondText(body, ContentType.Text.Plain)
            }
        }
    }

    /**
     * The recommended real-world layout: application-scoped [Compression], then the guard and
     * [ContentNegotiation] (with the Connect JSON converter) inside `routing { }` in that order.
     */
    fun Application.withRealisticConnectSetup(
        probe: HandlerProbe,
        maxDecompressedBytes: Long? = null,
    ) {
        install(Compression) {
            gzip()
            identity()
        }
        routing {
            install(UnaryCompressionGuard) {
                supportedEncodings = setOf("gzip", "identity")
                this.maxDecompressedBytes = maxDecompressedBytes
            }
            install(ContentNegotiation) {
                connectJson()
            }
            post("/connectrpc.eliza.v1.ElizaService/Say") {
                val request = call.receive<SayRequest>()
                // Set after receive() so that a body rejected by the guard's cap (which is
                // enforced lazily, when the handler receives the body) leaves this false.
                probe.invoked = true
                call.respond(sayResponse { sentence = request.sentence })
            }
        }
    }

    test("identity request passes through when no Content-Encoding header is sent") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
            probe.invoked shouldBe true
        }
    }

    test("explicit identity Content-Encoding is accepted") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "identity")
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("gzip-encoded body is decoded and accepted when Compression is installed") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip("hello"))
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("unknown Content-Encoding is rejected with Code.UNIMPLEMENTED and skips the handler") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "br")
                setBody("payload")
            }

            response.status shouldBe HttpStatusCode.NotImplemented
            // language=json
            response.bodyAsText() shouldEqualJson """
                {
                    "code": "unimplemented",
                    "message": "unsupported Content-Encoding \"br\"; supported: gzip, identity"
                }
            """
            probe.invoked shouldBe false
        }
    }

    test("uppercase GZIP Content-Encoding is rejected because Ktor's encoder lookup is case-sensitive") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "GZIP")
                setBody(gzip("hello"))
            }

            // The Compression plugin would not decode `GZIP` (its encoder lookup is
            // case-sensitive), so the guard rejects it up front instead of letting the
            // compressed bytes reach deserialization.
            response.status shouldBe HttpStatusCode.NotImplemented
            probe.invoked shouldBe false
        }
    }

    test("empty Content-Encoding header is treated as absent and passes through") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "")
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("whitespace-only Content-Encoding header is treated as absent and passes through") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "   ")
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("uppercase IDENTITY Content-Encoding is accepted") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "IDENTITY")
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("body within maxDecompressedBytes passes through after buffering") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true, maxDecompressedBytes = 16) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("body equal to maxDecompressedBytes is accepted") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true, maxDecompressedBytes = 5) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("body exceeding maxDecompressedBytes is rejected with RESOURCE_EXHAUSTED") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true, maxDecompressedBytes = 4) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.TooManyRequests
            // language=json
            response.bodyAsText() shouldEqualJson """
                {
                    "code": "resource_exhausted",
                    "message": "decompressed request body exceeds the 4 byte limit"
                }
            """
        }
    }

    test("gzip-bomb whose decompressed body exceeds maxDecompressedBytes is rejected") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = true, maxDecompressedBytes = 8) }

            // 256 'A's compresses to ~15 bytes but decodes to 256 bytes — exceeds the 8-byte cap.
            val payload = "A".repeat(256)
            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip(payload))
            }

            response.status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    test("realistic config: gzip-encoded Connect JSON request round-trips") {
        val probe = HandlerProbe()
        testApplication {
            application { withRealisticConnectSetup(probe) }

            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip("""{"sentence":"hello"}"""))
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldEqualJson """{"sentence":"hello"}"""
            probe.invoked shouldBe true
        }
    }

    test("realistic config: unknown encoding is rejected with 501 before the body is parsed") {
        val probe = HandlerProbe()
        testApplication {
            application { withRealisticConnectSetup(probe) }

            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.ContentEncoding, "zstd")
                // Deliberately *not* valid JSON: if ContentNegotiation parsed the body before
                // the guard ran, this request would fail with 400 instead of 501.
                setBody(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x00, 0x00))
            }

            response.status shouldBe HttpStatusCode.NotImplemented
            // language=json
            response.bodyAsText() shouldEqualJson """
                {
                    "code": "unimplemented",
                    "message": "unsupported Content-Encoding \"zstd\"; supported: gzip, identity"
                }
            """
            probe.invoked shouldBe false
        }
    }

    test("realistic config: unknown encoding is rejected even when ContentNegotiation is application-scoped") {
        val probe = HandlerProbe()
        testApplication {
            application {
                install(Compression) {
                    gzip()
                    identity()
                }
                install(ContentNegotiation) {
                    connectJson()
                }
                routing {
                    install(UnaryCompressionGuard) {
                        supportedEncodings = setOf("gzip", "identity")
                    }
                    post("/connectrpc.eliza.v1.ElizaService/Say") {
                        probe.invoked = true
                        val request = call.receive<SayRequest>()
                        call.respond(sayResponse { sentence = request.sentence })
                    }
                }
            }

            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.ContentEncoding, "br")
                setBody("not json at all")
            }

            response.status shouldBe HttpStatusCode.NotImplemented
            probe.invoked shouldBe false
        }
    }

    test("realistic config: gzip bomb is rejected with RESOURCE_EXHAUSTED before deserialization") {
        val probe = HandlerProbe()
        testApplication {
            application { withRealisticConnectSetup(probe, maxDecompressedBytes = 64) }

            val payload = """{"sentence":"${"A".repeat(4096)}"}"""
            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip(payload))
            }

            response.status shouldBe HttpStatusCode.TooManyRequests
            probe.invoked shouldBe false
        }
    }

    test("misconfiguration: application-scoped ContentNegotiation with maxDecompressedBytes fails loudly") {
        val probe = HandlerProbe()
        testApplication {
            application {
                install(Compression) {
                    gzip()
                    identity()
                }
                // ContentNegotiation at the application scope consumes the body before the
                // route-scoped guard can measure it. The guard must fail the request rather
                // than silently skipping the cap.
                install(ContentNegotiation) {
                    connectJson()
                }
                routing {
                    install(UnaryCompressionGuard) {
                        supportedEncodings = setOf("gzip", "identity")
                        maxDecompressedBytes = 64
                    }
                    post("/connectrpc.eliza.v1.ElizaService/Say") {
                        val request = call.receive<SayRequest>()
                        probe.invoked = true
                        call.respond(sayResponse { sentence = request.sentence })
                    }
                }
            }

            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"sentence":"hello"}""")
            }

            response.status shouldBe HttpStatusCode.InternalServerError
            probe.invoked shouldBe false
        }
    }

    test("non-identity encoding is still validated when Compression plugin is absent") {
        val probe = HandlerProbe()
        testApplication {
            application { withGuard(probe, installCompression = false) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "br")
                setBody("payload")
            }

            response.status shouldBe HttpStatusCode.NotImplemented
            probe.invoked shouldBe false
        }
    }

    test("empty supportedEncodings is rejected at install time") {
        shouldThrowAny {
            testApplication {
                application {
                    routing {
                        install(UnaryCompressionGuard) {
                            supportedEncodings = emptySet()
                        }
                        post("/echo") { call.respondText("unreachable") }
                    }
                }
                client.post("/echo")
            }
        }.message shouldContain "supportedEncodings must not be empty"
    }

    test("non-positive maxDecompressedBytes is rejected at install time") {
        shouldThrowAny {
            testApplication {
                application {
                    routing {
                        install(UnaryCompressionGuard) {
                            maxDecompressedBytes = 0
                        }
                        post("/echo") { call.respondText("unreachable") }
                    }
                }
                client.post("/echo")
            }
        }.message shouldContain "maxDecompressedBytes must be in"
    }

    test("maxDecompressedBytes above Int.MAX_VALUE - 1 is rejected at install time") {
        shouldThrowAny {
            testApplication {
                application {
                    routing {
                        install(UnaryCompressionGuard) {
                            maxDecompressedBytes = Int.MAX_VALUE.toLong()
                        }
                        post("/echo") { call.respondText("unreachable") }
                    }
                }
                client.post("/echo")
            }
        }.message shouldContain "maxDecompressedBytes must be in"
    }
})
