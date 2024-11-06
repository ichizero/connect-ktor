package io.github.ichizero.ktor.serialization.connect

import com.connectrpc.ProtocolClientConfig
import com.connectrpc.eliza.v1.ElizaServiceClient
import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.sayRequest
import com.connectrpc.eliza.v1.sayResponse
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.connectrpc.fold
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.kotest.assertions.json.*
import io.kotest.core.spec.style.*
import io.kotest.datatest.*
import io.kotest.datatest.withData
import io.kotest.matchers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

class ConfigurationConnectJsonTest : FunSpec({
    fun Application.startServer() {
        routing {
            install(ContentNegotiation) {
                connectJson()
            }
            post("/connectrpc.eliza.v1.ElizaService/Say") {
                val req = call.receive<SayRequest>()
                call.respond(sayResponse { sentence = req.sentence })
            }
        }
    }

    fun ApplicationTestBuilder.startServer() = application {
        startServer()
    }

    context("content negotiation") {
        data class Test(
            val name: String,
            val requestBody: String,
            val wantStatusCode: HttpStatusCode,
            val wantBody: String,
        )
        withData(
            nameFn = { it.name },
            Test(
                name = "valid request",
                requestBody = """{"sentence":"valid"}""",
                wantStatusCode = HttpStatusCode.OK,
                wantBody = """{"sentence":"valid"}""",
            ),
            Test(
                name = "with unknown field",
                requestBody = """{"sentence":"unknown", "unknownField":"value"}""",
                wantStatusCode = HttpStatusCode.OK,
                wantBody = """{"sentence":"unknown"}""",
            ),
            Test(
                name = "unknown field only",
                requestBody = """{"unknownField":"value"}""",
                wantStatusCode = HttpStatusCode.OK,
                wantBody = """{}""",
            ),
        ) { tt ->
            testApplication {
                startServer()

                client
                    .post("/connectrpc.eliza.v1.ElizaService/Say") {
                        header("Content-Type", "application/json")
                        setBody(tt.requestBody)
                    }.let { res ->
                        res.status shouldBe tt.wantStatusCode
                        res.bodyAsText() shouldEqualJson tt.wantBody
                    }
            }
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

class ConfigurationConnectProtoTest : FunSpec({
    fun Application.startServer() {
        routing {
            install(ContentNegotiation) {
                connectProto()
            }
            post("/connectrpc.eliza.v1.ElizaService/Say") {
                val req = call.receive<SayRequest>()
                call.respond(sayResponse { sentence = req.sentence })
            }
        }
    }

    test("e2e test with connect client") {
        val server = embeddedServer(CIO, port = 8098) {
            startServer()
        }
        server.start(wait = false)
        afterTest { server.stop() }

        val okHttpClient = OkHttpClient().newBuilder().build()
        afterTest { okHttpClient.dispatcher.executorService.shutdown() }

        val client = ProtocolClient(
            httpClient = ConnectOkHttpClient(okHttpClient),
            config = ProtocolClientConfig(
                host = "http://localhost:8098",
                serializationStrategy = GoogleJavaProtobufStrategy(),
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
