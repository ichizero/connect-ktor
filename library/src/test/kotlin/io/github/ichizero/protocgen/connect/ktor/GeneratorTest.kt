package io.github.ichizero.protocgen.connect.ktor

import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.eliza.v1.ElizaServiceClient
import com.connectrpc.eliza.v1.ElizaServiceHandlerInterface
import com.connectrpc.eliza.v1.IntroduceRequest
import com.connectrpc.eliza.v1.IntroduceResponse
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.SayResponse
import com.connectrpc.eliza.v1.elizaService
import com.connectrpc.eliza.v1.introduceRequest
import com.connectrpc.eliza.v1.introduceResponse
import com.connectrpc.eliza.v1.sayRequest
import com.connectrpc.eliza.v1.sayResponse
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.fold
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.ichizero.connect.ktor.streaming.connectResponseTrailers
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    override suspend fun introduce(
        request: IntroduceRequest,
        call: ApplicationCall,
    ): Flow<IntroduceResponse> {
        // Response headers go out with the HTTP head; trailers ride the end-stream frame.
        call.response.headers.append("x-custom-header", "foo")
        call.connectResponseTrailers().append("x-custom-trailer", "bing")
        return flow {
            emit(introduceResponse { sentence = "Hi ${request.name}!" })
            emit(introduceResponse { sentence = "I'm Eliza." })
        }
    }
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

    test("e2e server-streaming test with connect client") {
        val server = embeddedServer(CIO, port = 0) {
            startServer()
        }
        server.start(wait = false)
        afterTest { server.stop() }
        val port = server.engine.resolvedConnectors().first().port

        val okHttpClient = OkHttpClient().newBuilder().build()
        afterTest { okHttpClient.dispatcher.executorService.shutdown() }

        val client = ProtocolClient(
            httpClient = ConnectOkHttpClient(okHttpClient),
            config = ProtocolClientConfig(
                host = "http://localhost:$port",
                serializationStrategy = GoogleJavaJSONStrategy(),
                ioCoroutineContext = Dispatchers.IO,
            ),
        )

        val stream = ElizaServiceClient(client).introduce()
        stream.sendAndClose(introduceRequest { name = "Ktor" })

        val sentences = mutableListOf<String>()
        for (response in stream.responseChannel()) {
            sentences += response.sentence
        }

        sentences shouldBe listOf("Hi Ktor!", "I'm Eliza.")
        stream.responseHeaders().await()["x-custom-header"] shouldBe listOf("foo")
        stream.responseTrailers().await()["x-custom-trailer"] shouldBe listOf("bing")

        server.stop()
    }
})
