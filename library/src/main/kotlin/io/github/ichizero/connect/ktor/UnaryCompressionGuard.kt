package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondBytes

/**
 * A route-scoped plugin that validates the `Content-Encoding` request header for unary Connect RPCs.
 *
 * This guard intercepts the receive pipeline at the `Transform` phase, *after* Ktor's
 * [io.ktor.server.plugins.compression.Compression] plugin has had the opportunity to decode the
 * request body.  If the `Content-Encoding` header is still present at that point (i.e. the encoding
 * was not handled by `Compression`), the guard responds with [Code.UNIMPLEMENTED] and prevents the
 * request body from being read.
 *
 * `identity` (i.e. no compression) is always permitted.
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
 *     install(UnaryCompressionGuard)
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
val UnaryCompressionGuard: RouteScopedPlugin<Unit> = createRouteScopedPlugin("UnaryCompressionGuard") {
    onCallReceive { call ->
        // By the time the receive pipeline reaches the Transform phase, the Ktor Compression
        // plugin has already run its ContentDecoding phase (which is inserted *before* Transform).
        // If the Compression plugin recognised and decoded the encoding it will have removed the
        // Content-Encoding header.  If the header is still present here, the encoding is unknown.
        val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@onCallReceive

        // `identity` means no transformation — always valid regardless of Compression setup.
        if (contentEncoding.equals("identity", ignoreCase = true)) return@onCallReceive

        // The Compression plugin did not handle this encoding — reject the request.
        val error = ConnectException(
            code = Code.UNIMPLEMENTED,
            message = "unsupported Content-Encoding: $contentEncoding",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.UNIMPLEMENTED.asHTTPStatusCode(),
        )
    }
}
