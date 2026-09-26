package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import com.stricteliza.v1.CountdownRequest
import com.stricteliza.v1.CountdownResponse
import com.stricteliza.v1.SayRequest
import com.stricteliza.v1.SayResponse
import com.stricteliza.v1.StrictElizaServiceHandlerInterface
import com.stricteliza.v1.UploadRequest
import com.stricteliza.v1.UploadResponse
import com.stricteliza.v1.sayResponse
import com.stricteliza.v1.strictElizaService
import io.github.ichizero.ktor.protovalidate.ProtoRequestValidation
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Base64

private class ErrorHandler(
    private val result: suspend () -> ResponseMessage<SayResponse>,
) : StrictElizaServiceHandlerInterface {
    override suspend fun say(request: SayRequest, call: ApplicationCall): ResponseMessage<SayResponse> = result()

    override suspend fun upload(
        requests: Flow<UploadRequest>,
        call: ApplicationCall,
    ): ResponseMessage<UploadResponse> = error("unused")

    override suspend fun countdown(request: CountdownRequest, call: ApplicationCall): Flow<CountdownResponse> =
        emptyFlow()
}

class UnaryConnectErrorsTest : FunSpec({
    val path = "/stricteliza.v1.StrictElizaService/Say"

    suspend fun checkUnaryRoutes(
        handler: ErrorHandler,
        expectedStatus: HttpStatusCode,
        expectedBody: String,
        expectedHeader: String? = null,
        expectedTrailer: String? = null,
    ) {
        testApplication {
            application {
                install(Resources)
                routing {
                    install(ContentNegotiation) { connectJson() }
                    strictElizaService(handler)
                }
            }
            val responses = listOf(
                client.post(path) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"sentence":"hello"}""")
                },
                client.get("$path?connect=v1&encoding=json&message=%7B%22sentence%22%3A%22hello%22%7D"),
                client.get(
                    "$path?connect=v1&encoding=proto&base64=1&message=" +
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                            SayRequest.newBuilder().setSentence("hello").build().toByteArray(),
                        ),
                ),
            )
            responses.forEach { response ->
                response.status shouldBe expectedStatus
                response.headers[HttpHeaders.ContentType] shouldBe "application/json"
                response.bodyAsText() shouldEqualJson expectedBody
                response.headers["X-Test"] shouldBe expectedHeader
                response.headers["Trailer-X-Test"] shouldBe expectedTrailer
            }
        }
    }

    test("generated POST and GET preserve explicit failure code, body, and metadata") {
        checkUnaryRoutes(
            ErrorHandler {
                ResponseMessage.Failure(
                    ConnectException(Code.PERMISSION_DENIED, "denied"),
                    mapOf("X-Test" to listOf("header")),
                    mapOf("X-Test" to listOf("trailer")),
                )
            },
            HttpStatusCode.Forbidden,
            """{"code":"permission_denied","message":"denied"}""",
            "header",
            "trailer",
        )
    }

    test("generated POST and GET convert thrown ConnectException with metadata") {
        checkUnaryRoutes(
            ErrorHandler {
                throw ConnectException(
                    code = Code.UNAVAILABLE,
                    message = "later",
                    metadata = mapOf("X-Test" to listOf("retry")),
                )
            },
            HttpStatusCode.ServiceUnavailable,
            """{"code":"unavailable","message":"later"}""",
            expectedTrailer = "retry",
        )
    }

    test("generated POST and GET include failure cause metadata as trailers") {
        checkUnaryRoutes(
            ErrorHandler {
                ResponseMessage.Failure(
                    ConnectException(
                        code = Code.PERMISSION_DENIED,
                        message = "denied",
                        metadata = mapOf("X-Test" to listOf("cause")),
                    ),
                    emptyMap(),
                    emptyMap(),
                )
            },
            HttpStatusCode.Forbidden,
            """{"code":"permission_denied","message":"denied"}""",
            expectedTrailer = "cause",
        )
    }

    test("generated POST and GET convert unexpected handler exceptions") {
        checkUnaryRoutes(
            ErrorHandler { throw IllegalStateException("password=secret") },
            HttpStatusCode.InternalServerError,
            """{"code":"unknown","message":"internal server error"}""",
        )
    }

    test("validation failure is a Connect error on generated POST and GET without StatusPages") {
        testApplication {
            application {
                install(Resources)
                routing {
                    install(ContentNegotiation) { connectJson() }
                    install(ProtoRequestValidation)
                    strictElizaService(
                        ErrorHandler {
                            ResponseMessage.Success(sayResponse { sentence = "ok" }, emptyMap(), emptyMap())
                        },
                    )
                }
            }
            val sentence = "a".repeat(101)
            val responses = listOf(
                client.post(path) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"sentence":"$sentence"}""")
                },
                client.get(
                    "$path?connect=v1&encoding=json&message=" +
                        """{"sentence":"$sentence"}""".encodeURLQueryComponent(),
                ),
                client.get(
                    "$path?connect=v1&encoding=proto&base64=1&message=" +
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                            SayRequest.newBuilder().setSentence(sentence).build().toByteArray(),
                        ),
                ),
            )
            val bodies = responses.map { response ->
                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.ContentType] shouldBe "application/json"
                response.bodyAsText().also { body ->
                    body.contains("\"code\":\"invalid_argument\"") shouldBe true
                    body.contains("\"message\":\"invalid request\"") shouldBe true
                    body.contains("buf.validate.Violation") shouldBe true
                }
            }
            bodies[0] shouldEqualJson bodies[1]
            bodies[0] shouldEqualJson bodies[2]
        }
    }

    test("existing StatusPages still handles REST errors while generated routes return Connect errors") {
        var statusPagesCalls = 0
        testApplication {
            application {
                install(Resources)
                install(StatusPages) {
                    exception<Throwable> { call, _ ->
                        statusPagesCalls++
                        call.respondText("REST error", status = HttpStatusCode.InternalServerError)
                    }
                }
                routing {
                    get("/rest") { throw IllegalStateException("broken") }
                    post("/rest-proto") {
                        call.receive<SayRequest>()
                        call.respondText("REST ok")
                    }
                    install(ContentNegotiation) { connectJson() }
                    install(ProtoRequestValidation)
                    strictElizaService(ErrorHandler { throw IllegalStateException("broken") })
                }
            }
            client.get("/rest").bodyAsText() shouldBe "REST error"
            val response = client.post(path) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"sentence":"hello"}""")
            }
            response.status shouldBe HttpStatusCode.InternalServerError
            response.bodyAsText() shouldEqualJson """{"code":"unknown","message":"internal server error"}"""
            val invalid = client.post(path) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"sentence":"${"a".repeat(101)}"}""")
            }
            invalid.status shouldBe HttpStatusCode.BadRequest
            invalid.bodyAsText().contains("\"code\":\"invalid_argument\"") shouldBe true
            val invalidRest = client.post("/rest-proto") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"sentence":"${"a".repeat(101)}"}""")
            }
            invalidRest.status shouldBe HttpStatusCode.InternalServerError
            invalidRest.bodyAsText() shouldBe "REST error"
            statusPagesCalls shouldBe 2
        }
    }

    test("caller cancellation and JVM errors escape the unary handler boundary") {
        val cancellation = CancellationException("stopped")
        shouldThrow<CancellationException> {
            captureUnaryFailure<SayResponse> { throw cancellation }
        } shouldBe cancellation

        val fatal = AssertionError("fatal")
        shouldThrow<AssertionError> {
            captureUnaryFailure<SayResponse> { throw fatal }
        } shouldBe fatal
    }
})
