package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ConverseRequest
import com.connectrpc.eliza.v1.ConverseResponse
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.IntroduceRequest
import com.connectrpc.eliza.v1.IntroduceResponse
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.SayResponse
import com.connectrpc.eliza.v1.elizaService
import com.connectrpc.eliza.v1.sayRequest
import com.connectrpc.eliza.v1.sayResponse
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

class ConnectTimeoutTest : FunSpec({
    test("own timeout becomes deadline exceeded") {
        val error = runCatching {
            withConnectTimeout(10.milliseconds) { delay(200) }
        }.exceptionOrNull() as ConnectException
        error.code shouldBe Code.DEADLINE_EXCEEDED
    }

    test("outer cancellation remains cancellation") {
        val error = runCatching {
            withTimeout(10) { withConnectTimeout(200.milliseconds) { delay(500) } }
        }.exceptionOrNull()
        (error is CancellationException) shouldBe true
        (error is ConnectException) shouldBe false
    }

    test("nullable handler result is allowed") {
        withConnectTimeout(100.milliseconds) { null as String? } shouldBe null
    }

    test("generated unary POST honors the timeout header") {
        testApplication {
            application { timeoutRoutes() }
            val response = client.post("/connectrpc.eliza.v1.ElizaService/Say") {
                contentType(ContentType.Application.Json)
                header("Connect-Timeout-Ms", "10")
                setBody("""{"sentence":"wait"}""")
            }
            response.status shouldBe HttpStatusCode.GatewayTimeout
            response.bodyAsText() shouldContain """"code":"deadline_exceeded"""
        }
    }

    test("generated unary GET honors the timeout header") {
        testApplication {
            application { timeoutRoutes() }
            val message = Base64.getUrlEncoder().withoutPadding().encodeToString(
                sayRequest { sentence = "wait" }.toByteArray(),
            )
            val response = client.get(
                "/connectrpc.eliza.v1.ElizaService/Say?connect=v1&encoding=proto&base64=1&message=$message",
            ) { header("Connect-Timeout-Ms", "10") }
            response.status shouldBe HttpStatusCode.GatewayTimeout
            response.bodyAsText() shouldContain """"code":"deadline_exceeded"""
        }
    }
})

private fun io.ktor.server.application.Application.timeoutRoutes() {
    install(Resources)
    routing {
        install(ContentNegotiation) { connectJson() }
        elizaService(object : ElizaServiceHandlerInterface {
            override suspend fun say(request: SayRequest, call: ApplicationCall): ResponseMessage<SayResponse> {
                delay(200)
                return ResponseMessage.Success(sayResponse { sentence = request.sentence }, emptyMap(), emptyMap())
            }

            override suspend fun converse(
                requests: Flow<ConverseRequest>,
                call: ApplicationCall,
            ): Flow<ConverseResponse> = emptyFlow()

            override suspend fun introduce(
                request: IntroduceRequest,
                call: ApplicationCall,
            ): Flow<IntroduceResponse> = emptyFlow()
        })
    }
}
