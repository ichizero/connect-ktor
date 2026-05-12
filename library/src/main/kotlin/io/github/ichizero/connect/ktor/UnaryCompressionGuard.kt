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
 * This guard intercepts the receive pipeline after Ktor's [io.ktor.server.plugins.compression.Compression]
 * plugin has had the opportunity to decode the request body.  If the `Content-Encoding` header is still
 * present at that point (i.e. the encoding was not handled by [Compression]) the guard responds with
 * [Code.UNIMPLEMENTED] and prevents the request body from being read.
 *
 * `identity` (i.e. no compression) is always permitted.
 *
 * ## Usage
 *
 * Install the Ktor [io.ktor.server.plugins.compression.Compression] plugin in your application with
 * the encodings you want to support, then install [UnaryCompressionGuard] on the route scope that
 * serves Connect unary RPCs:
 *
 * ```kotlin
 * install(Compression) {
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
