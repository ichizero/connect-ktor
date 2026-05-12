package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.response.respondBytes

/**
 * Configuration for [ConnectBodyLimit].
 */
class ConnectBodyLimitConfig {
    /**
     * The maximum number of bytes allowed for an incoming request body.
     * Requests exceeding this limit are rejected with a [Code.RESOURCE_EXHAUSTED] Connect error.
     */
    var maxBytes: Long = Long.MAX_VALUE
}

/**
 * A route-scoped plugin that enforces a maximum request body size for Connect RPCs.
 *
 * When the inbound body exceeds [ConnectBodyLimitConfig.maxBytes], the plugin returns
 * a Connect-protocol error response with code `resource_exhausted` (HTTP 429) instead
 * of the default Ktor 413 Payload Too Large.
 *
 * The check is performed against the `Content-Length` request header, which is always
 * present for Connect unary RPCs. Requests that omit `Content-Length` are not rejected.
 *
 * Usage:
 * ```kotlin
 * routing {
 *     route("/com.example.v1.Service") {
 *         install(ConnectBodyLimit) { maxBytes = 4 * 1024 * 1024 }
 *         // Connect routes …
 *     }
 * }
 * ```
 */
val ConnectBodyLimit = createRouteScopedPlugin(
    "ConnectBodyLimit",
    ::ConnectBodyLimitConfig,
) {
    val limit = pluginConfig.maxBytes

    // Check Content-Length synchronously before the handler runs.
    // Throws PayloadTooLargeException in the call pipeline so that CallFailed
    // can catch it reliably (no background-coroutine wrapping).
    onCall { call ->
        val contentLength = call.request.contentLength() ?: return@onCall
        if (contentLength > limit) {
            throw PayloadTooLargeException(limit)
        }
    }

    // Convert PayloadTooLargeException to a Connect-protocol RESOURCE_EXHAUSTED
    // error response (HTTP 429) instead of the default Ktor 413.
    on(CallFailed) { call, cause ->
        if (cause !is PayloadTooLargeException) return@on

        val error = ConnectException(
            code = Code.RESOURCE_EXHAUSTED,
            message = "request body too large",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.RESOURCE_EXHAUSTED.asHTTPStatusCode(),
        )
    }
}
