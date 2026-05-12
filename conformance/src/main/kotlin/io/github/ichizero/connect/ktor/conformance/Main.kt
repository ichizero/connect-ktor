package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.ServerCompatRequest
import com.connectrpc.conformance.v1.ServerCompatResponse
import com.google.protobuf.ByteString
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.io.PrintStream
import java.security.KeyStore
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

    val handler: ConformanceServiceHandlerInterface = ConformanceServiceImpl()
    // message_receive_limit = 0 means "not set"; pass 0 to disable ConnectBodyLimit.
    val receiveLimit = request.messageReceiveLimit.toLong()
    val module: Application.() -> Unit = { conformanceModule(handler, receiveLimit) }

    val tls = TlsSetup.from(request)
    val server: EmbeddedServer<*, *> = when (engine) {
        Engine.CIO -> startCioServer(tls, module)
        Engine.NETTY -> startNettyServer(tls, module)
    }
    server.start(wait = false)

    val port = awaitPort(server.engine)

    val responseBuilder = ServerCompatResponse.newBuilder()
        .setHost("127.0.0.1")
        .setPort(port)
    tls?.pemCert?.let { responseBuilder.pemCert = ByteString.copyFrom(it) }
    writeDelimited(rawStdout, responseBuilder.build())

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

private fun startCioServer(
    tls: TlsSetup?,
    module: Application.() -> Unit,
): EmbeddedServer<*, *> = embeddedServer(
    CIO,
    configure = {
        if (tls != null) {
            installSslConnector(tls)
        } else {
            connector {
                port = 0
                host = "127.0.0.1"
            }
        }
    },
    module = module,
)

private fun startNettyServer(
    tls: TlsSetup?,
    module: Application.() -> Unit,
): EmbeddedServer<*, *> = embeddedServer(
    Netty,
    configure = {
        if (tls != null) {
            // Netty enables HTTP/2 over TLS via ALPN automatically when
            // enableHttp2 is true. h2c is incompatible with SSL so it stays
            // off on the secure connector.
            enableHttp2 = true
            installSslConnector(tls)
        } else {
            connector {
                port = 0
                host = "127.0.0.1"
            }
            // Default Netty config only speaks HTTP/1.1. Opt into h2c so the
            // same connector can also serve HTTP/2 over cleartext for the
            // conformance suite's HTTP_VERSION_2 cases.
            enableHttp2 = true
            enableH2c = true
        }
    },
    module = module,
)

private fun io.ktor.server.engine.ApplicationEngine.Configuration.installSslConnector(tls: TlsSetup) {
    sslConnector(
        keyStore = tls.keyStore,
        keyAlias = SERVER_KEY_ALIAS,
        keyStorePassword = { EMPTY_PASSWORD },
        privateKeyPassword = { EMPTY_PASSWORD },
    ) {
        port = 0
        host = "127.0.0.1"
        tls.trustStore?.let { (this as EngineSSLConnectorBuilder).trustStore = it }
    }
}

internal data class TlsSetup(
    val keyStore: KeyStore,
    val trustStore: KeyStore?,
    val pemCert: ByteArray,
) {
    companion object {
        fun from(request: ServerCompatRequest): TlsSetup? {
            if (!request.useTls) return null
            require(request.hasServerCreds()) {
                "use_tls=true but no server_creds in ServerCompatRequest"
            }
            val cert = request.serverCreds.cert.toByteArray()
            val key = request.serverCreds.key.toByteArray()
            val keyStore = keyStoreFromPem(cert, key)
            val trustStore = request.clientTlsCert
                .takeIf { !it.isEmpty }
                ?.toByteArray()
                ?.let { trustStoreFromPem(it) }
            return TlsSetup(keyStore = keyStore, trustStore = trustStore, pemCert = cert)
        }
    }
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
