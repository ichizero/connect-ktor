package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.conformance.v1.BidiStreamRequest
import com.connectrpc.conformance.v1.BidiStreamResponse
import com.connectrpc.conformance.v1.StreamResponseDefinition
import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import com.connectrpc.conformance.v1.Code as ConformanceCode
import com.connectrpc.conformance.v1.Error as ConformanceError

class ConformanceServiceImplTest : FunSpec({
    for (responseCount in listOf(0, 1, 3)) {
        for (withError in listOf(false, true)) {
            test("full duplex finishes with an open request stream: responses=$responseCount, error=$withError") {
                testApplication {
                    application {
                        routing {
                            get("/bidi") {
                                val definition = StreamResponseDefinition.newBuilder().apply {
                                    repeat(responseCount) { addResponseData(ByteString.copyFromUtf8("response-$it")) }
                                    if (withError) {
                                        error =
                                            ConformanceError.newBuilder().setCode(ConformanceCode.CODE_ABORTED).build()
                                    }
                                }.build()
                                val requests = Channel<BidiStreamRequest>(Channel.UNLIMITED)
                                requests.send(
                                    BidiStreamRequest.newBuilder()
                                        .setFullDuplex(true).setResponseDefinition(definition).build(),
                                )
                                repeat((responseCount - 1).coerceAtLeast(0)) {
                                    requests.send(BidiStreamRequest.getDefaultInstance())
                                }
                                // Keep the sending side open: completion must not require another request or EOF.
                                val responses = mutableListOf<BidiStreamResponse>()
                                try {
                                    withTimeout(2_000) {
                                        val flow = ConformanceServiceImpl().bidiStream(requests.receiveAsFlow(), call)
                                        if (withError) {
                                            shouldThrow<ConnectException> { flow.toList(responses) }.code shouldBe
                                                Code.ABORTED
                                        } else {
                                            flow.toList(responses)
                                        }
                                    }
                                    responses.map { it.payload.data.toStringUtf8() } shouldBe
                                        List(responseCount) { "response-$it" }
                                    responses.forEach { it.payload.requestInfo.requestsCount shouldBe 1 }
                                    call.respondText("completed")
                                } finally {
                                    requests.cancel()
                                }
                            }
                        }
                    }
                    client.get("/bidi").status shouldBe HttpStatusCode.OK
                }
            }
        }
    }
})
