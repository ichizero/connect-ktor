package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.ServerCompatRequest
import com.connectrpc.conformance.v1.ServerCompatResponse
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val engine = try {
        Engine.parse(args)
    } catch (ex: IllegalArgumentException) {
        System.err.println("[conformance] ${ex.message}")
        exitProcess(2)
    }
    System.err.println("[conformance] using engine=${engine.name.lowercase()}")

    // The conformance runner uses stdout to read the ServerCompatResponse.
    // Anything else written to stdout (e.g. Ktor logs) would corrupt the
    // framing. Capture the real stdout and route everything else to stderr.
    val rawStdout = System.out
    System.setOut(PrintStream(System.err, true))

    val request = readDelimited(System.`in`, ServerCompatRequest.parser())

    if (request.useTls) {
        // config.yaml declares supports_tls=false, so the runner should never
        // send use_tls=true. Bail loudly if it does instead of silently
        // serving plaintext on a TLS-only test case.
        System.err.println("[conformance] connect-ktor conformance server does not support TLS")
        exitProcess(1)
    }

    val handler: ConformanceServiceHandlerInterface = ConformanceServiceImpl()
    val module: Application.() -> Unit = { conformanceModule(handler) }

    val server: EmbeddedServer<*, *> = when (engine) {
        Engine.CIO -> embeddedServer(CIO, port = 0, host = "127.0.0.1", module = module)

        Engine.NETTY -> embeddedServer(
            Netty,
            configure = {
                connector {
                    port = 0
                    host = "127.0.0.1"
                }
                // Default Netty config only speaks HTTP/1.1. Opt into h2c so the
                // same connector can also serve HTTP/2 over cleartext for the
                // conformance suite's HTTP_VERSION_2 cases.
                enableHttp2 = true
                enableH2c = true
            },
            module = module,
        )
    }
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

internal enum class Engine {
    CIO,
    NETTY,
    ;

    companion object {
        /**
         * Parses the `--engine` flag from CLI args. Accepts both
         * `--engine cio` and `--engine=cio` forms, and falls back to
         * [Engine.CIO] when the flag is absent (so the binary stays usable
         * by hand for local debugging).
         */
        fun parse(args: Array<String>): Engine {
            val name = extractEngineArg(args)?.lowercase() ?: return CIO
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "unknown --engine '$name' (expected one of: ${entries.joinToString { it.name.lowercase() }})",
                )
        }

        private fun extractEngineArg(args: Array<String>): String? {
            for ((idx, raw) in args.withIndex()) {
                if (raw == "--engine") {
                    return args.getOrNull(idx + 1)
                        ?: throw IllegalArgumentException("--engine requires a value")
                }
                if (raw.startsWith("--engine=")) {
                    return raw.removePrefix("--engine=")
                }
            }
            return null
        }
    }
}
