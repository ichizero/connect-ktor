package io.github.ichizero.protocgen.connect.ktor

import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.elizaService
import com.stricteliza.v1.StrictElizaServiceHandlerInterface
import com.stricteliza.v1.UploadRequest
import com.stricteliza.v1.UploadResponse
import com.stricteliza.v1.strictElizaService
import com.stricteliza.v1.uploadResponse
import io.github.ichizero.connect.ktor.streaming.decodeTestFrames
import io.github.ichizero.connect.ktor.streaming.encodeTestFrame
import io.github.ichizero.ktor.serialization.connect.connectJson
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
import io.ktor.server.routing.routing
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
                            strictElizaService(ConsumingUploadHandler, maxMessageSize = 8)
                            elizaService(Handler, maxMessageSize = 8)
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
                routing { strictElizaService(ConsumingUploadHandler, maxMessageSize = 8) }
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
                routing { elizaService(Handler, maxMessageSize = 8) }
            }
            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                header("Content-Type", "application/json")
                setBody("""{"sentence":"longer than eight bytes"}""")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }
})
