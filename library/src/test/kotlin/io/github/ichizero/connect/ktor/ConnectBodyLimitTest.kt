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
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.identity
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private const val RESOURCE_EXHAUSTED_JSON =
    """{"code":"resource_exhausted","message":"request body too large"}"""

class ConnectBodyLimitTest : FunSpec({

    fun gzip(payload: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(payload) }
        return sink.toByteArray()
    }

    fun gzip(payload: String): ByteArray = gzip(payload.toByteArray())

    class HandlerProbe {
        var invoked: Boolean = false
    }

    /**
     * The recommended layout for a server that accepts compressed requests: application-scoped
     * [Compression], then `connectBodyLimit` and [ContentNegotiation] — in that order — inside
     * `routing { }`.
     */
    fun Application.withCompressedConnectSetup(probe: HandlerProbe, maxBytes: Long) {
        install(Compression) {
            gzip()
            identity()
        }
        routing {
            connectBodyLimit(maxBytes = maxBytes)
            install(ContentNegotiation) {
                connectJson()
            }
            post("/connectrpc.eliza.v1.ElizaService/Say") {
                val request = call.receive<SayRequest>()
                // Set after receive() so that a body rejected by the cap (which is enforced
                // while the deserializer pulls the body) leaves this false.
                probe.invoked = true
                call.respond(sayResponse { sentence = request.sentence })
            }
        }
    }

    context("connectBodyLimit") {
        test("request within limit is accepted") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 100)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("ok")
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(50))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }
            }
        }

        test("request exactly at limit is accepted") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 50)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("ok")
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(50))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }
            }
        }

        test("request exceeding limit returns RESOURCE_EXHAUSTED connect error") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                // Body is 26 bytes, larger than 10-byte limit.
                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(26))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("chunked transfer-encoding without Content-Length is also capped") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                // Send a 100-byte body via Transfer-Encoding: chunked (no
                // Content-Length).  RequestBodyLimit's byte counter must
                // enforce the cap and trigger the Connect error response.
                val body = "a".repeat(100).toByteArray()
                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(
                            object : OutgoingContent.ReadChannelContent() {
                                override fun readFrom(): ByteReadChannel = ByteReadChannel(body)
                            },
                        )
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("REST and Connect routes coexist with different overflow behaviour") {
            // Route-scoped Ktor RequestBodyLimit on /rest gives a default 413,
            // while connectBodyLimit on the /connect subtree turns the overflow
            // into a Connect-protocol 429 + JSON body.
            testApplication {
                application {
                    routing {
                        route("/rest") {
                            install(RequestBodyLimit) {
                                bodyLimit { 10 }
                            }
                            post {
                                call.receive<ByteArray>()
                                call.respondText("rest-ok")
                            }
                        }
                        route("/connect") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("connect-ok")
                            }
                        }
                    }
                }

                client
                    .post("/rest") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        // Ktor's default RequestBodyLimit response is 413.
                        res.status shouldBe HttpStatusCode.PayloadTooLarge
                    }

                client
                    .post("/connect") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("app-wide StatusPages handler for unrelated exception does not interfere") {
            // When the user installs StatusPages app-wide but only registers
            // handlers for exception types *other than* PayloadTooLargeException,
            // connectBodyLimit's Connect-protocol 429 wins.  A blanket
            // `exception<Throwable>` handler still overrides — that limitation
            // is documented on `Route.connectBodyLimit`.
            testApplication {
                application {
                    install(StatusPages) {
                        exception<IllegalStateException> { call, _ ->
                            call.respond(HttpStatusCode.InternalServerError, "ise")
                        }
                    }
                    routing {
                        route("/connect") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                client
                    .post("/connect") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("route outside connectBodyLimit scope is not affected") {
            testApplication {
                application {
                    routing {
                        route("/limited") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("limited")
                            }
                        }
                        route("/unlimited") {
                            post {
                                call.receive<ByteArray>()
                                call.respondText("unlimited")
                            }
                        }
                    }
                }

                // The /unlimited route should accept large bodies.
                client
                    .post("/unlimited") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }

                // The /limited route should reject large bodies.
                client
                    .post("/limited") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                    }
            }
        }

        test("non-positive maxBytes is rejected at install time") {
            shouldThrowAny {
                testApplication {
                    application {
                        routing {
                            connectBodyLimit(maxBytes = 0)
                            post("/test") { call.respondText("unreachable") }
                        }
                    }
                    client.post("/test")
                }
            }.message shouldContain "maxBytes must be in"
        }
    }

    context("connectBodyLimit with compressed requests") {
        test("gzip body decoding to within the limit is accepted") {
            val probe = HandlerProbe()
            testApplication {
                application { withCompressedConnectSetup(probe, maxBytes = 64) }

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

        test("gzip body decoding to exactly the limit is accepted") {
            val probe = HandlerProbe()
            val payload = """{"sentence":"hello"}"""
            testApplication {
                application {
                    withCompressedConnectSetup(probe, maxBytes = payload.length.toLong())
                }

                val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(gzip(payload))
                }

                response.status shouldBe HttpStatusCode.OK
                probe.invoked shouldBe true
            }
        }

        test("gzip bomb whose decompressed body exceeds the limit is rejected") {
            val probe = HandlerProbe()
            testApplication {
                application { withCompressedConnectSetup(probe, maxBytes = 64) }

                // ~4 KiB of JSON compresses to far less than the 64-byte cap on the wire, so
                // only a post-decompression counter can catch it.
                val payload = """{"sentence":"${"A".repeat(4096)}"}"""
                val compressed = gzip(payload)
                // Guards the premise of this test: the wire bytes are under the cap, so a
                // RequestBodyLimit-only implementation would let this request through.
                (compressed.size < 64) shouldBe true

                val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(compressed)
                }

                response.status shouldBe HttpStatusCode.TooManyRequests
                response.bodyAsText() shouldEqualJson RESOURCE_EXHAUSTED_JSON
                probe.invoked shouldBe false
            }
        }

        test("explicit identity Content-Encoding still uses the wire-byte cap") {
            testApplication {
                application {
                    install(Compression) {
                        gzip()
                        identity()
                    }
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.ContentEncoding, "identity")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson RESOURCE_EXHAUSTED_JSON
                    }
            }
        }

        test("cap holds when ContentNegotiation is installed at the application scope") {
            // The limit runs in its own receive-pipeline phase ahead of every body transformer,
            // so it does not care where ContentNegotiation lives.
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
                        connectBodyLimit(maxBytes = 64)
                        post("/connectrpc.eliza.v1.ElizaService/Say") {
                            val request = call.receive<SayRequest>()
                            probe.invoked = true
                            call.respond(sayResponse { sentence = request.sentence })
                        }
                    }
                }

                client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(gzip("""{"sentence":"hello"}"""))
                }.let { res ->
                    res.status shouldBe HttpStatusCode.OK
                    probe.invoked shouldBe true
                }

                probe.invoked = false
                client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(gzip("""{"sentence":"${"A".repeat(4096)}"}"""))
                }.let { res ->
                    res.status shouldBe HttpStatusCode.TooManyRequests
                    res.bodyAsText() shouldEqualJson RESOURCE_EXHAUSTED_JSON
                    probe.invoked shouldBe false
                }
            }
        }

        test("cap holds for bodies received as ByteArray rather than deserialized") {
            // Ktor's built-in transformers materialize the body for receive<ByteArray>() at the
            // Transform phase; the limit must still see the decoded bytes first.
            testApplication {
                application {
                    install(Compression) {
                        gzip()
                        identity()
                    }
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 64)
                            post {
                                call.respondText(call.receive<ByteArray>().size.toString())
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(gzip("hello"))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                        res.bodyAsText() shouldBe "5"
                    }

                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(gzip("A".repeat(4096)))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson RESOURCE_EXHAUSTED_JSON
                    }
            }
        }

        test("cap holds for bodies received as text") {
            testApplication {
                application {
                    install(Compression) {
                        gzip()
                        identity()
                    }
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 64)
                            post { call.respondText(call.receiveText()) }
                        }
                    }
                }

                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(gzip("hello"))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                        res.bodyAsText() shouldBe "hello"
                    }

                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(gzip("A".repeat(4096)))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                    }
            }
        }
    }
})
