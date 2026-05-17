package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * Configuration for [UnaryCompressionGuard].
 *
 * The caller is responsible for keeping [supportedEncodings] in sync with the encoders registered
 * on Ktor's [io.ktor.server.plugins.compression.Compression] plugin: the value advertised here is
 * surfaced verbatim in error responses, so a mismatch with the actually-installed encoders will
 * mislead clients.
 */
public class UnaryCompressionGuardConfig {
    /**
     * The set of `Content-Encoding` values the server can decode.  Surfaced in error responses
     * when the guard rejects an unsupported encoding, as recommended by the Connect spec.
     *
     * The default mirrors the encodings Ktor's [io.ktor.server.plugins.compression.Compression]
     * plugin ships out of the box (`gzip` plus `identity`).  Override this when you install
     * additional encoders (e.g. `br`, `zstd`) and keep the set in sync with those encoders.
     */
    public var supportedEncodings: Set<String> = setOf("gzip", "identity")

    /**
     * Upper bound (in bytes) on the *post-decompression* body size accepted by unary RPCs.  When
     * a request body decodes to more than this many bytes, the guard responds with
     * [Code.RESOURCE_EXHAUSTED] before the application handler runs.
     *
     * This is a self-defence cap against decompression bombs: a small gzip payload can expand to
     * an arbitrarily large byte stream, so without an explicit limit a single request can drive
     * the server out of memory.  The default of [Long.MAX_VALUE] disables the cap entirely.
     *
     * The cap is enforced by buffering the (decoded) request body in memory before the application
     * sees it, then re-feeding it to the receive pipeline.  Choose a value comfortably larger than
     * your largest legitimate request and consider the per-request memory cost when sizing the
     * thread pool / connector backlog.
     *
     * Implementation note: values above [Int.MAX_VALUE] are clamped to [Int.MAX_VALUE] because the
     * underlying [io.ktor.utils.io.readBuffer] API is `Int`-sized.  A request whose body exceeds
     * 2 GiB will therefore be rejected even when the configured cap is higher.
     */
    public var maxDecompressedBytes: Long = Long.MAX_VALUE
}

/**
 * A route-scoped plugin that validates the `Content-Encoding` request header and (optionally)
 * caps the post-decompression body size for unary Connect RPCs.
 *
 * This guard intercepts the receive pipeline at the `Transform` phase, *after* Ktor's
 * [io.ktor.server.plugins.compression.Compression] plugin has had the opportunity to decode the
 * request body:
 *
 *  1. If the `Content-Encoding` header is still present at that point (i.e. the encoding was not
 *     handled by `Compression`), the guard responds with [Code.UNIMPLEMENTED].  The error message
 *     lists the encodings configured via [UnaryCompressionGuardConfig.supportedEncodings] per the
 *     [Connect spec recommendation](https://connectrpc.com/docs/protocol/#unary-request).
 *  2. If [UnaryCompressionGuardConfig.maxDecompressedBytes] is set, the (decoded) body is buffered
 *     with a cap-plus-one read; bodies larger than the cap are rejected with
 *     [Code.RESOURCE_EXHAUSTED].
 *
 * `identity` (i.e. no compression) is always permitted regardless of configuration.
 *
 * ## Important: install `Compression` at the application scope
 *
 * The guard relies on the [io.ktor.server.plugins.compression.Compression] plugin running its
 * `ContentDecoding` phase *before* `UnaryCompressionGuard`'s `onCallReceive` interceptor.  This
 * ordering is only guaranteed when `Compression` is installed at the **application scope** (i.e.
 * outside the `routing { }` block).  If `Compression` is installed inside the same route scope as
 * `UnaryCompressionGuard` — or omitted entirely — the guard will treat *every* non-identity
 * `Content-Encoding` (including `gzip`) as unknown and respond with `Code.UNIMPLEMENTED`, because
 * no decoder will have stripped the header.
 *
 * ## Important: keep [UnaryCompressionGuardConfig.supportedEncodings] in sync
 *
 * The set passed to [UnaryCompressionGuardConfig.supportedEncodings] only feeds error messages;
 * it does not influence which encodings the server actually decodes.  Whether a non-identity
 * encoding is accepted is decided exclusively by whether
 * [io.ktor.server.plugins.compression.Compression] has stripped the `Content-Encoding` header.
 * Listing an encoding here without also registering an encoder will produce misleading error
 * responses — but will not change the accept/reject outcome.
 *
 * ## Usage
 *
 * Install the Ktor [io.ktor.server.plugins.compression.Compression] plugin at the **application
 * scope** with the encodings you want to support, then install [UnaryCompressionGuard] on the
 * route scope that serves Connect unary RPCs:
 *
 * ```kotlin
 * install(Compression) {       // <- application scope (outside `routing { }`)
 *     gzip()
 *     identity()
 * }
 * routing {
 *     install(UnaryCompressionGuard) {
 *         supportedEncodings = setOf("gzip", "identity")
 *         maxDecompressedBytes = 4 * 1024 * 1024  // 4 MiB cap against decompression bombs
 *     }
 *     elizaService(ElizaServiceHandler)
 * }
 * ```
 *
 * When [io.ktor.server.plugins.compression.Compression] is not installed, all non-identity encodings
 * are rejected because no decompressor will strip the `Content-Encoding` header.
 *
 * ## Implementation note
 *
 * This guard relies on Ktor's internal receive-pipeline phase ordering (`ContentDecoding` is
 * inserted *before* `Transform`, and `onCallReceive` interceptors run at `Transform`).  A
 * `testApplication` regression test exercises a real gzip-encoded request body to pin this
 * contract; if Ktor changes the phase layout the test will fail.
 */
public val UnaryCompressionGuard: RouteScopedPlugin<UnaryCompressionGuardConfig> =
    createRouteScopedPlugin("UnaryCompressionGuard", ::UnaryCompressionGuardConfig) {
        val supportedEncodings = pluginConfig.supportedEncodings
        val maxDecompressedBytes = pluginConfig.maxDecompressedBytes

        onCallReceive { call, body ->
            // By the time the receive pipeline reaches the Transform phase, the Ktor Compression
            // plugin has already run its ContentDecoding phase (which is inserted *before* Transform).
            // If the Compression plugin recognised and decoded the encoding it will have removed the
            // Content-Encoding header.  If the header is still present here, the encoding is unknown.
            val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
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
                return@onCallReceive
            }

            if (maxDecompressedBytes == Long.MAX_VALUE) return@onCallReceive

            // Cap the post-decompression body size.  We read up to `cap + 1` bytes: if the channel
            // still has data after that, the body is over the limit.  Clamp to Int.MAX_VALUE because
            // ByteReadChannel.readBuffer takes an `Int`.
            val channel = body as? ByteReadChannel ?: return@onCallReceive
            val readLimit = (maxDecompressedBytes + 1)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val buffered = channel.readBuffer(readLimit)
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
                return@onCallReceive
            }

            val bytes = buffered.readByteArray()
            transformBody { ByteReadChannel(bytes) }
        }
    }
