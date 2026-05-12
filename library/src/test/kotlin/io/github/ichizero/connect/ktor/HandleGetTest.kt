package io.github.ichizero.connect.ktor

import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.SayResponse
import com.connectrpc.eliza.v1.elizaService
import com.connectrpc.eliza.v1.sayResponse
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Base64

@Resource("/connectrpc.eliza.v1.ElizaService/Say")
private class SayProcedure

// Handler that echoes the request sentence back.
private object EchoHandler : ElizaServiceHandlerInterface {
    override suspend fun say(
        request: SayRequest,
        call: ApplicationCall,
    ): ResponseMessage<SayResponse> = ResponseMessage.Success(
        sayResponse { sentence = request.sentence },
        emptyMap(),
        emptyMap(),
    )
}

class HandleGetTest : FunSpec({

    suspend fun startApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(Resources)
                routing {
                    elizaService(EchoHandler)
                }
            }
            block()
        }

    test("proto GET request with base64-encoded message") {
        startApp {
            // Build a SayRequest proto and base64url-encode it.
            val req = SayRequest.newBuilder().setSentence("hello proto").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())

            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&base64=1&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.OK

            // Decode and verify the response body.
            val responseBytes = response.bodyAsBytes()
            val strategy = GoogleJavaProtobufStrategy()
            val sayResponse = strategy.codec(SayResponse::class).deserialize(
                okio.Buffer().write(responseBytes),
            )
            sayResponse.sentence shouldBe "hello proto"
        }
    }

    test("json GET request with base64-encoded message") {
        startApp {
            // Build a JSON SayRequest and base64url-encode it.
            val req = SayRequest.newBuilder().setSentence("hello json").build()
            val json = JsonFormat.printer().print(req)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=json&base64=1&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "hello json"
        }
    }

    test("missing connect parameter returns 400") {
        startApp {
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?encoding=proto&message=Cg0=",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
        }
    }

    test("wrong connect version returns 400") {
        startApp {
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v2&encoding=proto&message=Cg0=",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
        }
    }

    test("missing encoding parameter returns 400") {
        startApp {
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v1&message=Cg0=",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
        }
    }

    test("unknown encoding parameter returns 400") {
        startApp {
            val req = SayRequest.newBuilder().setSentence("hi").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v1&encoding=xml&base64=1&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
        }
    }

    test("missing message parameter returns 400") {
        startApp {
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v1&encoding=proto",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
        }
    }

    test("unsupported compression returns 501") {
        startApp {
            val req = SayRequest.newBuilder().setSentence("hi").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&base64=1&compression=gzip&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.NotImplemented
            response.bodyAsText() shouldContain "unimplemented"
        }
    }

    test("query params are stored in call attributes") {
        // Use a handler that reads ConnectGetQueryParamsKey to verify it was set.
        val capturedQueryParams = mutableListOf<List<Pair<String, List<String>>>>()

        val capturingHandler = object : ElizaServiceHandlerInterface {
            override suspend fun say(
                request: SayRequest,
                call: ApplicationCall,
            ): ResponseMessage<SayResponse> {
                val params = call.attributes.getOrNull(ConnectGetQueryParamsKey)
                if (params != null) capturedQueryParams += params
                return ResponseMessage.Success(sayResponse { sentence = "" }, emptyMap(), emptyMap())
            }
        }

        testApplication {
            application {
                install(Resources)
                routing {
                    elizaService(capturingHandler)
                }
            }

            val req = SayRequest.newBuilder().setSentence("attr-test").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())
            client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&base64=1&message=$encoded",
            )

            capturedQueryParams.size shouldBe 1
            val params = capturedQueryParams.first()
            params.find { it.first == "connect" }?.second shouldBe listOf("v1")
            params.find { it.first == "encoding" }?.second shouldBe listOf("proto")
            params.find { it.first == "base64" }?.second shouldBe listOf("1")
        }
    }
})
