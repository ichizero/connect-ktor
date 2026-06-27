package io.github.ichizero.connect.ktor

import com.connectrpc.Code

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
