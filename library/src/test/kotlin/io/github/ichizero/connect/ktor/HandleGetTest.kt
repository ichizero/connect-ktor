package io.github.ichizero.connect.ktor

import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.SayResponse
import com.connectrpc.eliza.v1.elizaService
import com.connectrpc.eliza.v1.sayResponse
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.reflect.KClass

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
            // Connect spec: proto request → proto response Content-Type.
            response.headers[HttpHeaders.ContentType] shouldContain "application/proto"

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
            // Connect spec: json request → json response Content-Type.
            response.headers[HttpHeaders.ContentType] shouldContain ContentType.Application.Json.contentType
            response.bodyAsText() shouldContain "hello json"
        }
    }

    test("compression=identity is accepted") {
        // Explicit `compression=identity` is the no-op case and MUST succeed (spec).
        startApp {
            val req = SayRequest.newBuilder().setSentence("identity ok").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())

            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&base64=1&compression=identity&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("malformed base64 message returns 400 invalid_argument") {
        startApp {
            // '!!!' is not valid base64url and triggers IllegalArgumentException in decode().
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&base64=1&message=!!!",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
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

    test("unknown encoding parameter returns 501 unimplemented with supported codecs") {
        // Connect spec: an unsupported codec yields CODE_UNIMPLEMENTED plus the supported list.
        startApp {
            val req = SayRequest.newBuilder().setSentence("hi").build()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v1&encoding=xml&base64=1&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.NotImplemented
            response.bodyAsText() shouldContain "unimplemented"
            response.bodyAsText() shouldContain "supported: proto, json"
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

    test("encoding=proto without base64=1 returns 400") {
        // Proto bytes are not UTF-8 safe; rejecting prevents silent payload corruption.
        startApp {
            val req = SayRequest.newBuilder().setSentence("hi").build()
            val rawProtoAsUtf8 = req.toByteArray().toString(Charsets.UTF_8)
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=proto&message=$rawProtoAsUtf8",
            )

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_argument"
            response.bodyAsText() shouldContain "base64=1"
        }
    }

    test("base64=0 is ignored and the json message is treated as non-base64 text") {
        // Connect spec: any value other than "1" for the base64 flag is ignored, NOT rejected.
        // With base64 != "1", the message is the percent-decoded query value verbatim (here JSON).
        startApp {
            val json = JsonFormat.printer().print(
                SayRequest.newBuilder().setSentence("hello flag zero").build(),
            )
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=json&base64=0&message=${json.encodeURLQueryComponent()}",
            )

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "hello flag zero"
        }
    }

    test("unknown base64 flag value is ignored rather than rejected") {
        // "foo" is neither "1" nor absent; the spec says it is ignored (treated as non-base64),
        // matching the connect-go reference (`query.Get("base64") == "1"`).
        startApp {
            val json = JsonFormat.printer().print(
                SayRequest.newBuilder().setSentence("hello flag foo").build(),
            )
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=json&base64=foo&message=${json.encodeURLQueryComponent()}",
            )

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "hello flag foo"
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
        val capturedQueryParams = mutableListOf<Parameters>()

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
            params["connect"] shouldBe "v1"
            params["encoding"] shouldBe "proto"
            params["base64"] shouldBe "1"
        }
    }

    test("jsonSerializer override takes precedence over the json strategy for GET responses") {
        testApplication {
            application {
                installConnectGetCodecs(
                    ConnectGetStrategies(
                        proto = GoogleJavaProtobufStrategy(),
                        json = GoogleJavaJSONStrategy(),
                        jsonSerializer = object : ConnectGetJsonSerializer {
                            override fun <T : Any> serialize(value: T, clazz: KClass<T>): ByteArray =
                                """{"sentence":"overridden by jsonSerializer"}""".toByteArray(Charsets.UTF_8)
                        },
                    ),
                )
                install(Resources)
                routing {
                    elizaService(EchoHandler)
                }
            }

            val req = SayRequest.newBuilder().setSentence("hello json").build()
            val json = JsonFormat.printer().print(req)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say" +
                    "?connect=v1&encoding=json&base64=1&message=$encoded",
            )

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType] shouldContain ContentType.Application.Json.contentType
            response.bodyAsText() shouldContain "overridden by jsonSerializer"
        }
    }
})
