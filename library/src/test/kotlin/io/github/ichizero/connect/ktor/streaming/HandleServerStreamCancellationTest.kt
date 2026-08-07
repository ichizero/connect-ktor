package io.github.ichizero.connect.ktor.streaming

import com.stricteliza.v1.CountdownRequest
import com.stricteliza.v1.CountdownResponse
import com.stricteliza.v1.countdownRequest
import com.stricteliza.v1.countdownResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.resources.Resource
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Socket

/**
 * Cancellation propagation needs a real engine and a real socket: the in-memory test host never
 * breaks a connection the way a client that walks away does.
 */
class HandleServerStreamCancellationTest : FunSpec({
    test("server streaming: a client disconnect cancels the handler's flow") {
        val collectorReleased = CompletableDeferred<Unit>()

        val server = embeddedServer(CIO, port = 0) {
            install(Resources)
            routing {
                post<CancellingCountdownResource>(
                    handleServerStream<CancellingCountdownResource, CountdownRequest, CountdownResponse>(
                        handlerFunc = { _, _ ->
                            flow {
                                try {
                                    // Bounded so a server that never notices the disconnect fails the
                                    // test on the await() timeout instead of streaming forever.
                                    repeat(MAX_MESSAGES) { value ->
                                        emit(countdownResponse { this.value = value })
                                        delay(EMIT_INTERVAL_MS)
                                    }
                                } finally {
                                    // Stands in for whatever resource a real producer would hold.
                                    collectorReleased.complete(Unit)
                                }
                            }
                        },
                    ),
                )
            }
        }
        server.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port

            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    socket.sendRequest(port, countdownRequest { from = 1 })

                    // Block until the server has begun responding, so the disconnect lands mid-stream.
                    val firstByte = socket.getInputStream().read()
                    firstByte shouldBe 'H'.code

                    // Close with a RST rather than a FIN so the server's next write fails promptly.
                    socket.setSoLinger(true, 0)
                }
            }

            withTimeout(RELEASE_TIMEOUT_MS) { collectorReleased.await() }
        } finally {
            server.stop()
        }
    }
})

private const val MAX_MESSAGES = 10_000
private const val EMIT_INTERVAL_MS = 20L
private const val SOCKET_TIMEOUT_MS = 10_000
private const val RELEASE_TIMEOUT_MS = 10_000L

@Resource("/stricteliza.v1.StrictElizaService/Countdown")
private class CancellingCountdownResource

private fun Socket.sendRequest(port: Int, request: CountdownRequest) {
    val payload = request.toByteArray()
    val body = byteArrayOf(
        0,
        ((payload.size ushr 24) and 0xFF).toByte(),
        ((payload.size ushr 16) and 0xFF).toByte(),
        ((payload.size ushr 8) and 0xFF).toByte(),
        (payload.size and 0xFF).toByte(),
    ) + payload

    val head = (
        "POST /stricteliza.v1.StrictElizaService/Countdown HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Content-Type: application/connect+proto\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    getOutputStream().apply {
        write(head)
        write(body)
        flush()
    }
}
