package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.ServerCompatRequest
import com.connectrpc.conformance.v1.ServerCompatResponse
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.PrintStream
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val engine = Engine.parse(args)

    // The conformance runner uses stdout to read the ServerCompatResponse.
    // Anything else written to stdout (e.g. Ktor logs) would corrupt the
    // framing. Capture the real stdout and route everything else to stderr.
    val rawStdout = System.out
    System.setOut(PrintStream(System.err, true))

    val request = readDelimited(System.`in`, ServerCompatRequest.parser())

    if (request.useTls) {
        System.err.println("connect-ktor conformance server does not support TLS")
        kotlin.system.exitProcess(1)
    }

    val handler: ConformanceServiceHandlerInterface = ConformanceServiceImpl()

    val server = embeddedServer(
        factory = engine.factory,
        port = 0,
        host = "127.0.0.1",
        module = { conformanceModule(handler) },
    )
    server.start(wait = false)

    val port = awaitPort(server.engine)

    val response = ServerCompatResponse.newBuilder()
        .setHost("127.0.0.1")
        .setPort(port)
        .build()
    writeDelimited(rawStdout, response)

    // The conformance runner manages the server lifecycle via signals.
    // Block the main thread until SIGTERM/SIGINT is delivered.
    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            try {
                server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
            } finally {
                latch.countDown()
            }
        },
    )
    latch.await()
}

internal enum class Engine(val factory: ApplicationEngineFactory<*, *>) {
    CIO(io.ktor.server.cio.CIO),
    NETTY(Netty),
    ;

    companion object {
        fun parse(args: Array<String>): Engine {
            val idx = args.indexOf("--engine")
            val name = if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else "cio"
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("unknown engine: $name (expected one of: ${entries.joinToString { it.name.lowercase() }})")
        }
    }
}
