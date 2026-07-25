package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route

/**
 * A route-scoped plugin that validates the `Content-Encoding` request header for unary Connect
 * RPCs.
 *
 * Before the route handler runs (at the call pipeline's `Plugins` phase, i.e. before any part of
 * the body is parsed), the guard checks the `Content-Encoding` header against
 * [UnaryCompressionGuardConfig.supportedEncodings].  Unsupported codings are rejected with
 * [Code.UNIMPLEMENTED] and the rest of the pipeline — including the route handler — is skipped.
 *
 * `identity` (i.e. no compression) is always permitted regardless of configuration.
 *
 * ## Important: keep [UnaryCompressionGuardConfig.supportedEncodings] in sync
 *
 * The guard accepts or rejects requests based solely on
 * [UnaryCompressionGuardConfig.supportedEncodings]; whether the body can actually be decoded is
 * decided by the encoders registered on [io.ktor.server.plugins.compression.Compression].  Keep
 * the two in sync.  Note that `Compression` matches encoder names case-sensitively, so the guard
 * does too (a request sending `Content-Encoding: GZIP` is rejected with `unimplemented` because
 * Ktor would not decode it).
 *
 * ## Usage
 *
 * ```kotlin
 * install(Compression) {           // <- application scope (outside `routing { }`)
 *     gzip()
 *     identity()
 * }
 * routing {
 *     install(UnaryCompressionGuard) {
 *         supportedEncodings = setOf("gzip", "identity")
 *     }
 *     connectBodyLimit(maxBytes = 4 * 1024 * 1024)  // <- caps the *decoded* body size
 *     install(ContentNegotiation) {
 *         connectJson()
 *     }
 *     elizaService(ElizaServiceHandler)
 * }
 * ```
 *
 * When [io.ktor.server.plugins.compression.Compression] is not installed, keep
 * [UnaryCompressionGuardConfig.supportedEncodings] at `setOf("identity")` so that compressed
 * requests are rejected up front instead of failing during deserialization.
 *
 * ## Decompression bombs
 *
 * Accepting a compressed request means a small payload can expand to an arbitrarily large byte
 * stream.  This guard does not bound that expansion; pair it with
 * [Route.connectBodyLimit], which enforces its cap against the *decoded* body size for
 * compressed requests.
 */
public val UnaryCompressionGuard: RouteScopedPlugin<UnaryCompressionGuardConfig> =
    createRouteScopedPlugin("UnaryCompressionGuard", ::UnaryCompressionGuardConfig) {
        val supportedEncodings = pluginConfig.supportedEncodings
        require(supportedEncodings.isNotEmpty()) {
            "UnaryCompressionGuard.supportedEncodings must not be empty; " +
                "use setOf(\"identity\") to accept uncompressed requests only"
        }

        on(BeforeCall) { call ->
            // Runs before the route handler and before anything reads the body, so unsupported
            // encodings are rejected even when ContentNegotiation is installed at the
            // application scope (where its Transform interceptor would otherwise attempt to
            // parse the still-compressed body first).
            val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@on

            val unsupported = contentEncoding.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { coding ->
                    // `identity` is matched case-insensitively because it never needs decoding;
                    // everything else is matched case-sensitively to mirror the Ktor Compression
                    // plugin's case-sensitive encoder lookup.
                    coding.equals("identity", ignoreCase = true) || coding in supportedEncodings
                }
            if (unsupported.isEmpty()) return@on

            val supportedList = supportedEncodings.joinToString(", ")
            val error = ConnectException(
                code = Code.UNIMPLEMENTED,
                message = "unsupported Content-Encoding \"$contentEncoding\"; supported: $supportedList",
            )
            call.respondBytes(
                bytes = error.toErrorJsonBytes(),
                contentType = ContentType.Application.Json,
                status = Code.UNIMPLEMENTED.asHTTPStatusCode(),
            )
            // BeforeCall finishes the pipeline once a response has been sent, so the route
            // handler never runs.
        }
    }

/**
 * Runs [the handler][Hook.install] at the call pipeline's `Plugins` phase — after routing has
 * selected the route but before the route handler executes and before anything touches the
 * request body.  If the handler responds to the call, the pipeline is finished so that no
 * further interceptor (including the route handler) runs.
 */
private object BeforeCall : Hook<suspend (call: ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (call: ApplicationCall) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            handler(call)
            if (call.response.isSent) finish()
        }
    }
}
