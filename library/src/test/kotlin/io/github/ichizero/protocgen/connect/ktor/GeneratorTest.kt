package io.github.ichizero.protocgen.connect.ktor

import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ElizaServiceClient
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.SayResponse
import com.connectrpc.eliza.v1.elizaService
import com.connectrpc.eliza.v1.sayRequest
import com.connectrpc.eliza.v1.sayResponse
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.fold
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.kotest.core.spec.style.*
import io.kotest.matchers.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

object Handler : ElizaServiceHandlerInterface {
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

class GeneratorTest : FunSpec({
    fun Application.startServer() {
        install(Resources)
        routing {
            install(ContentNegotiation) {
                connectJson()
            }
            elizaService(Handler)
        }
    }

    test("e2e test with connect client") {
        val server = embeddedServer(CIO, port = 8099) {
            startServer()
        }
        server.start(wait = false)
        afterTest { server.stop() }

        val okHttpClient = OkHttpClient().newBuilder().build()
        afterTest { okHttpClient.dispatcher.executorService.shutdown() }

        val client = ProtocolClient(
            httpClient = ConnectOkHttpClient(okHttpClient),
            config = ProtocolClientConfig(
                host = "http://localhost:8099",
                serializationStrategy = GoogleJavaJSONStrategy(),
                ioCoroutineContext = Dispatchers.IO,
            ),
        )

        ElizaServiceClient(client).say(sayRequest { sentence = "Hi! Ktor Server" }).fold(
            onSuccess = { it shouldBe sayResponse { sentence = "Hi! Ktor Server" } },
            onFailure = { it shouldBe null },
        )

        server.stop()
    }
})
