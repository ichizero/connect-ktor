package io.github.ichizero.protocgen.connect.ktor

import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.elizaService
import com.stricteliza.v1.StrictElizaServiceHandlerInterface
import com.stricteliza.v1.UploadRequest
import com.stricteliza.v1.UploadResponse
import com.stricteliza.v1.strictElizaService
import com.stricteliza.v1.uploadResponse
import io.github.ichizero.connect.ktor.streaming.ConnectStreaming
import io.github.ichizero.connect.ktor.streaming.decodeTestFrames
import io.github.ichizero.connect.ktor.streaming.encodeTestFrame
import io.github.ichizero.connect.ktor.streaming.handleBidiStream
import io.github.ichizero.connect.ktor.streaming.handleClientStream
import io.github.ichizero.connect.ktor.streaming.handleServerStream
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count

private object ConsumingUploadHandler : StrictElizaServiceHandlerInterface
by io.github.ichizero.ktor.protovalidate.Handler {
    override suspend fun upload(
        requests: Flow<UploadRequest>,
        call: ApplicationCall,
    ): ResponseMessage<UploadResponse> = ResponseMessage.Success(
        uploadResponse { chunkCount = requests.count().toLong() },
        emptyMap(),
        emptyMap(),
    )
}

class GeneratedMessageLimitTest : FunSpec({
    val paths = listOf(
        "/stricteliza.v1.StrictElizaService/Upload",
        "/connectrpc.eliza.v1.ElizaService/Introduce",
        "/connectrpc.eliza.v1.ElizaService/Converse",
    )
    for (path in paths) {
        for (length in listOf(8, 9)) {
            test("generated $path enforces an 8-byte payload limit for length $length") {
                testApplication {
                    application {
                        install(Resources)
                        routing {
                            install(ConnectStreaming) { maxMessageSize = 8 }

                            strictElizaService(ConsumingUploadHandler)
                            elizaService(Handler)
                        }
                    }
                    // All three requests encode field 1 as a string.
                    val payload = byteArrayOf(10, (length - 2).toByte()) + ByteArray(length - 2) { 97 }
                    val response = client.post(path) {
                        header("Content-Type", "application/connect+proto")
                        setBody(encodeTestFrame(payload))
                    }
                    response.status shouldBe HttpStatusCode.OK
                    val end = decodeTestFrames(response.bodyAsBytes()).last()
                    end.isEndStream shouldBe true
                    if (length == 8) {
                        String(end.payload) shouldNotContain "\"error\""
                    } else {
                        String(end.payload) shouldContain "resource_exhausted"
                    }
                }
            }
        }
    }

    test("generated client stream limits each payload rather than the total request") {
        testApplication {
            application {
                install(Resources)
                routing {
                    install(ConnectStreaming) { maxMessageSize = 8 }
                    strictElizaService(ConsumingUploadHandler)
                }
            }
            val frame = encodeTestFrame(byteArrayOf(10, 6) + ByteArray(6) { 97 })
            val response = client.post(paths.first()) {
                header("Content-Type", "application/connect+proto")
                setBody(frame + frame)
            }
            val frames = decodeTestFrames(response.bodyAsBytes())
            UploadResponse.parseFrom(frames.first().payload).chunkCount shouldBe 2L
            String(frames.last().payload) shouldBe "{}"
        }
    }

    test("streaming limit does not cap unary requests on the same generated service") {
        testApplication {
            application {
                install(Resources)
                install(ContentNegotiation) { connectJson() }
                routing {
                    install(ConnectStreaming) { maxMessageSize = 8 }
                    elizaService(Handler)
                }
            }
            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header("Content-Type", "application/json")
                setBody("""{"sentence":"longer than eight bytes"}""")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("nested configuration overrides its parent without leaking to siblings") {
        testApplication {
            application {
                install(Resources)
                routing {
                    install(ConnectStreaming) { maxMessageSize = 8 }
                    route("/parent") { elizaService(Handler) }
                    route("/child") {
                        install(ConnectStreaming) { maxMessageSize = 16 }
                        elizaService(Handler)
                    }
                    route("/sibling") { elizaService(Handler) }
                }
            }
            for ((prefix, rejected) in listOf("parent" to true, "child" to false, "sibling" to true)) {
                val end = postLimitProbe("/$prefix/connectrpc.eliza.v1.ElizaService/Converse")
                end.contains("resource_exhausted") shouldBe rejected
            }
        }
    }

    test("explicit limits override plugin settings for every streaming kind") {
        testApplication {
            application {
                install(Resources)
                routing {
                    install(ConnectStreaming) { maxMessageSize = 8 }
                    post<StrictElizaServiceHandlerInterface.Procedures.Upload>(
                        handleClientStream(ConsumingUploadHandler::upload, maxMessageSize = 16),
                    )
                    post<ElizaServiceHandlerInterface.Procedures.Introduce>(
                        handleServerStream(Handler::introduce, maxMessageSize = 16),
                    )
                    post<ElizaServiceHandlerInterface.Procedures.Converse>(
                        handleBidiStream(Handler::converse, maxMessageSize = 16),
                    )
                }
            }
            for (path in paths) postLimitProbe(path) shouldNotContain "\"error\""
        }
    }

    test("unconfigured generated routes use the default limit") {
        testApplication {
            application {
                install(Resources)
                routing {
                    strictElizaService(ConsumingUploadHandler)
                    elizaService(Handler)
                }
            }
            for (path in paths) {
                postLimitProbe(path) shouldNotContain "\"error\""
                // An oversized header is rejected without allocating or sending a large payload.
                val response = client.post(path) {
                    header("Content-Type", "application/connect+proto")
                    setBody(byteArrayOf(0, 0, 64, 0, 1))
                }
                String(decodeTestFrames(response.bodyAsBytes()).last().payload) shouldContain "resource_exhausted"
            }
        }
    }

    for (limit in listOf(0, -1)) {
        test("invalid plugin limit $limit fails at installation") {
            testApplication {
                application {
                    routing { install(ConnectStreaming) { maxMessageSize = limit } }
                }
                shouldThrow<IllegalArgumentException> { startApplication() }
            }
        }
    }
})

private suspend fun ApplicationTestBuilder.postLimitProbe(path: String): String {
    val response = client.post(path) {
        header("Content-Type", "application/connect+proto")
        setBody(encodeTestFrame(byteArrayOf(10, 7) + ByteArray(7) { 97 }))
    }
    response.status shouldBe HttpStatusCode.OK
    return String(decodeTestFrames(response.bodyAsBytes()).last().payload)
}
