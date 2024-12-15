package io.github.ichizero.ktor.protovalidate

import build.buf.validate.Violation
import com.connectrpc.ResponseMessage
import com.stricteliza.v1.SayRequest
import com.stricteliza.v1.SayResponse
import com.stricteliza.v1.StrictElizaServiceHandler
import com.stricteliza.v1.sayResponse
import com.stricteliza.v1.strictElizaService
import io.github.ichizero.connect.ktor.toErrorJsonBytes
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

object Handler : StrictElizaServiceHandler {
    override suspend fun say(
        request: SayRequest,
        call: ApplicationCall,
    ): ResponseMessage<SayResponse> = ResponseMessage.Success(
        sayResponse {
            sentence = request.sentence
        },
        emptyMap(),
        emptyMap(),
    )
}

class ProtoRequestValidationTest : FunSpec({
    fun Application.startServer() {
        install(Resources)
        install(StatusPages) {
            exception<ProtoRequestValidationException> { call, cause ->
                call.respondBytes(
                    bytes = cause.toErrorJsonBytes(),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
            }
        }
        routing {
            install(ContentNegotiation) {
                connectJson()
            }
            install(ProtoRequestValidation)
            strictElizaService(Handler)
        }
    }

    fun ApplicationTestBuilder.startServer() = application {
        startServer()
    }

    context("request validation") {
        val maxLenViolation = Violation
            .newBuilder()
            .setFieldPath("sentence")
            .setConstraintId("string.max_len")
            .setMessage("value length must be at most 100 characters")
            .build()

        data class Test(
            val name: String,
            val requestBody: String,
            val wantBody: String,
            val wantStatusCode: HttpStatusCode,
        )
        withData(
            nameFn = { it.name },
            Test(
                name = "valid request",
                requestBody = """{"sentence":"${"a".repeat(100)}"}""",
                wantBody = """{"sentence":"${"a".repeat(100)}"}""",
                wantStatusCode = HttpStatusCode.OK,
            ),
            Test(
                name = "invalid request",
                requestBody = """{"sentence":"${"a".repeat(100 + 1)}"}""",
                // language=json
                wantBody = """
                    {
                        "code": "invalid_argument",
                        "message": "invalid request",
                        "details": [{
                          "type": "buf.validate.Violation",
                          "value": "${maxLenViolation.toByteArray().toByteString().base64()}"
                        }]
                    }
                """.trimMargin(),
                wantStatusCode = HttpStatusCode.BadRequest,
            ),
        ) { tt ->
            testApplication {
                startServer()

                client
                    .post("/stricteliza.v1.StrictElizaService/Say") {
                        header("Content-Type", "application/json")
                        header("Accept", "application/json")
                        setBody(tt.requestBody)
                    }.let { res ->
                        res.status shouldBe tt.wantStatusCode
                        res.bodyAsText() shouldEqualJson tt.wantBody
                    }
            }
        }
    }
})
