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
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * Configuration for [UnaryCompressionGuard].
 */
public class UnaryCompressionGuardConfig {
    /**
     * The set of `Content-Encoding` values the server accepts.  A request whose
     * `Content-Encoding` header carries a coding outside this set (other than `identity`, which is
     * always permitted) is rejected with [Code.UNIMPLEMENTED] before the body is read, and the
     * error message lists this set as recommended by the
     * [Connect spec](https://connectrpc.com/docs/protocol/#unary-request).
     *
     * The default mirrors the gzip support Ktor's
     * [io.ktor.server.plugins.compression.Compression] plugin ships out of the box.  Override this
     * when you register additional encoders (e.g. `br`, `zstd`) and keep the set in sync with the
     * encoders actually installed on `Compression`: listing an encoding here without a matching
     * encoder means the body reaches deserialization still compressed and fails with an opaque
     * parse error instead of a clear `unimplemented` response.
     *
     * Matching is case-sensitive (except for `identity`) because Ktor's `Compression` plugin looks
     * encoders up case-sensitively: accepting `GZIP` here would let a request through that
     * `Compression` then refuses to decode.  Content-coding values are lowercase in practice.
     */
    public var supportedEncodings: Set<String> = setOf("gzip", "identity")

    /**
     * Optional upper bound (in bytes) on the *post-decompression* body size accepted by unary
     * RPCs.  When a request body decodes to more than this many bytes, the guard responds with
     * [Code.RESOURCE_EXHAUSTED] and the application handler never runs.  `null` (the default)
     * disables the cap.
     *
     * This is a self-defence cap against decompression bombs: a small gzip payload can expand to
     * an arbitrarily large byte stream, so without an explicit limit a single request can drive
     * the server out of memory.
     *
     * The cap is enforced by buffering the decoded request body in memory (reading at most one
     * byte past the cap) before deserialization sees it.  Choose a value comfortably larger than
     * your largest legitimate request and consider the per-request memory cost when sizing the
     * thread pool / connector backlog.
     *
     * Valid values are `1..Int.MAX_VALUE - 1`; anything else is rejected at install time because
     * the underlying [io.ktor.utils.io.readBuffer] API is `Int`-sized.
     *
     * **Install-order requirement:** enforcing the cap requires the guard to observe the decoded
     * body *before* it is deserialized.  Install `ContentNegotiation` *after*
     * `UnaryCompressionGuard` in the same route scope (see [UnaryCompressionGuard]).  If a
     * deserializer installed ahead of the guard consumes the body first, the guard fails the
     * request loudly instead of silently skipping the check.
     */
    public var maxDecompressedBytes: Long? = null
}

/**
 * A route-scoped plugin that validates the `Content-Encoding` request header and (optionally)
 * caps the post-decompression body size for unary Connect RPCs.
 *
 *  1. Before the route handler runs (at the call pipeline's `Plugins` phase, i.e. before any part
 *     of the body is parsed), the guard checks the `Content-Encoding` header against
 *     [UnaryCompressionGuardConfig.supportedEncodings].  Unsupported codings are rejected with
 *     [Code.UNIMPLEMENTED] and the rest of the pipeline — including the route handler — is
 *     skipped.
 *  2. If [UnaryCompressionGuardConfig.maxDecompressedBytes] is set, the guard intercepts the
 *     receive pipeline's `Transform` phase, buffers the (already decoded) body with a cap-plus-one
 *     read, and rejects bodies larger than the cap with [Code.RESOURCE_EXHAUSTED] — again without
 *     running the route handler.
 *
 * `identity` (i.e. no compression) is always permitted regardless of configuration.
 *
 * ## Plugin ordering
 *
 * Ktor runs all receive-pipeline `Transform` interceptors in a fixed order: application-scoped
 * plugins first (in install order), then route-scoped plugins (in install order).  Both the
 * `Compression` plugin's request decode and `ContentNegotiation`'s deserialization run in this
 * same `Transform` phase, so their relative order is decided purely by where they are installed.
 * For the guard's [UnaryCompressionGuardConfig.maxDecompressedBytes] cap to see the decoded
 * `ByteReadChannel` before it is deserialized, the order must be:
 *
 *  1. `Compression` — application scope (decodes the body, e.g. gunzip),
 *  2. `UnaryCompressionGuard` — route scope (buffers and caps the decoded bytes),
 *  3. `ContentNegotiation` — same route scope, installed *after* the guard (deserializes).
 *
 * Installing `ContentNegotiation` at the application scope (or before the guard in the same
 * route scope) makes it consume the body before the guard runs.  The guard does not silently
 * skip the cap in that case: it fails the request with an [IllegalStateException] that explains
 * the required install order.  When [UnaryCompressionGuardConfig.maxDecompressedBytes] is unset,
 * `ContentNegotiation` may live at any scope — the `Content-Encoding` check does not look at the
 * body.
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
 *         maxDecompressedBytes = 4 * 1024 * 1024  // 4 MiB cap against decompression bombs
 *     }
 *     install(ContentNegotiation) { // <- after the guard so the cap sees the raw bytes
 *         connectJson()
 *     }
 *     elizaService(ElizaServiceHandler)
 * }
 * ```
 *
 * When [io.ktor.server.plugins.compression.Compression] is not installed, keep
 * [UnaryCompressionGuardConfig.supportedEncodings] at `setOf("identity")` so that compressed
 * requests are rejected up front instead of failing during deserialization.
 */
public val UnaryCompressionGuard: RouteScopedPlugin<UnaryCompressionGuardConfig> =
    createRouteScopedPlugin("UnaryCompressionGuard", ::UnaryCompressionGuardConfig) {
        val supportedEncodings = pluginConfig.supportedEncodings
        val maxDecompressedBytes = pluginConfig.maxDecompressedBytes
        require(supportedEncodings.isNotEmpty()) {
            "UnaryCompressionGuard.supportedEncodings must not be empty; " +
                "use setOf(\"identity\") to accept uncompressed requests only"
        }
        if (maxDecompressedBytes != null) {
            require(maxDecompressedBytes in 1 until Int.MAX_VALUE.toLong()) {
                "UnaryCompressionGuard.maxDecompressedBytes must be in 1..${Int.MAX_VALUE - 1} " +
                    "(was $maxDecompressedBytes); the cap is enforced with an Int-sized buffer"
            }
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

        if (maxDecompressedBytes != null) {
            on(ReceiveBodyTransform) { call, body ->
                // By the time the receive pipeline reaches the route-scoped part of the Transform
                // phase, the application-scoped Compression plugin has already decoded the body,
                // so the channel below carries *decompressed* bytes.
                val channel = body as? ByteReadChannel ?: throw IllegalStateException(
                    "UnaryCompressionGuard.maxDecompressedBytes requires the request body to " +
                        "still be a ByteReadChannel when the guard runs, but it was already " +
                        "transformed to ${body::class.qualifiedName}. Install ContentNegotiation " +
                        "after UnaryCompressionGuard in the same route scope.",
                )

                // Read up to `cap + 1` bytes: if more than `cap` bytes come out, the body is over
                // the limit. The config validation above guarantees `cap + 1` fits in an Int.
                val buffered = channel.readBuffer((maxDecompressedBytes + 1).toInt())
                if (buffered.size > maxDecompressedBytes) {
                    val error = ConnectException(
                        code = Code.RESOURCE_EXHAUSTED,
                        message = "decompressed request body exceeds the $maxDecompressedBytes byte limit",
                    )
                    call.respondBytes(
                        bytes = error.toErrorJsonBytes(),
                        contentType = ContentType.Application.Json,
                        status = Code.RESOURCE_EXHAUSTED.asHTTPStatusCode(),
                    )
                    // Returning null finishes the receive pipeline; the route handler's receive()
                    // then fails and the handler body never runs.
                    return@on null
                }
                ByteReadChannel(buffered.readByteArray())
            }
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

/**
 * Intercepts the receive pipeline's `Transform` phase.  The handler returns the transformed body
 * to proceed with, or `null` to finish the receive pipeline (used after the handler has already
 * responded to the call).
 */
private object ReceiveBodyTransform : Hook<suspend (call: ApplicationCall, body: Any) -> Any?> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (call: ApplicationCall, body: Any) -> Any?,
    ) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.Transform) { body ->
            when (val transformed = handler(call, body)) {
                null -> finish()
                else -> proceedWith(transformed)
            }
        }
    }
}
