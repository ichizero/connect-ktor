package io.github.ichizero.connect.ktor

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Regression test that pins the receive-pipeline ordering contract between Ktor's
 * [Compression] plugin and [UnaryCompressionGuard].  If Ktor changes the phase layout
 * (so that `ContentDecoding` no longer runs before `Transform`), the gzip success case
 * here will fail because the guard will see a stale `Content-Encoding: gzip` header.
 */
class UnaryCompressionGuardTest : FunSpec({

    fun gzip(payload: String): ByteArray {
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(payload.toByteArray()) }
        return sink.toByteArray()
    }

    fun Application.withGuard(installCompression: Boolean) {
        if (installCompression) {
            install(Compression) {
                gzip()
                identity()
            }
        }
        routing {
            install(UnaryCompressionGuard)
            post("/echo") {
                val body = call.receiveText()
                call.respondText(body, ContentType.Text.Plain)
            }
        }
    }

    test("identity request passes through when no Content-Encoding header is sent") {
        testApplication {
            application { withGuard(installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("hello")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("explicit identity Content-Encoding is accepted") {
        testApplication {
            application { withGuard(installCompression = true) }

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
        testApplication {
            application { withGuard(installCompression = true) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip("hello"))
            }

            // If Ktor's ContentDecoding phase no longer runs before Transform, this
            // request would either fail with UNIMPLEMENTED (guard sees stale header)
            // or surface the raw gzip bytes — either outcome trips the assertion.
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hello"
        }
    }

    test("unknown Content-Encoding is rejected with Code.UNIMPLEMENTED") {
        testApplication {
            application { withGuard(installCompression = true) }

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
                    "message": "unsupported Content-Encoding: br"
                }
            """
        }
    }

    test("non-identity encoding is rejected when Compression plugin is absent") {
        testApplication {
            application { withGuard(installCompression = false) }

            val response = client.post("/echo") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip("hello"))
            }

            response.status shouldBe HttpStatusCode.NotImplemented
        }
    }
})
