package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.contentLength
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route

/**
 * Configuration for [ConnectBodyLimit].
 */
class ConnectBodyLimitConfig {
    /**
     * The maximum number of bytes allowed for an incoming request body.
     *
     * Requests exceeding this limit are rejected with a [Code.RESOURCE_EXHAUSTED]
     * Connect error.  Defaults to [Long.MAX_VALUE] (effectively unlimited) so that
     * accidentally installing the plugin without configuration is a no-op, but
     * callers are strongly encouraged to set an explicit ceiling.
     */
    var maxBytes: Long = Long.MAX_VALUE
}

/**
 * A route-scoped plugin that enforces a maximum request body size for Connect RPCs
 * and translates the resulting [PayloadTooLargeException] into a Connect-protocol
 * `resource_exhausted` error response (HTTP 429) instead of the default Ktor 413.
 *
 * The plugin handles the error translation only; for the actual byte counting it
 * relies on Ktor's [RequestBodyLimit] plugin which must be installed on the same
 * route (typically the parent service route).  In addition, this plugin performs a
 * defensive `Content-Length` precheck so that obviously oversized requests fail
 * before any bytes are read.
 *
 * Usage:
 * ```kotlin
 * routing {
 *     route("/com.example.v1.Service") {
 *         connectBodyLimit(maxBytes = 4 * 1024 * 1024)
 *         // Connect routes …
 *     }
 * }
 * ```
 *
 * The convenience extension [connectBodyLimit] installs both [RequestBodyLimit] and
 * [ConnectBodyLimit] together with the same limit so that streaming and chunked
 * (`Transfer-Encoding: chunked`) requests are also capped, not just those that
 * advertise a `Content-Length`.
 *
 * Out of scope (tracked separately):
 * - Evaluating the *decompressed* body size for compressed (e.g. gzip) requests.
 *   The current implementation caps the on-wire byte count only.  See
 *   [#191](https://github.com/ichizero/connect-ktor/issues/191) for context.
 */
val ConnectBodyLimit = createRouteScopedPlugin(
    "ConnectBodyLimit",
    ::ConnectBodyLimitConfig,
) {
    val limit = pluginConfig.maxBytes

    // Defense-in-depth: if Content-Length is set and already over the limit,
    // fail fast before reading any bytes.  When Content-Length is absent or
    // lies (e.g. Transfer-Encoding: chunked), RequestBodyLimit still enforces
    // the cap by counting bytes as they are streamed in.
    onCall { call ->
        val contentLength = call.request.contentLength() ?: return@onCall
        if (contentLength > limit) {
            throw PayloadTooLargeException(limit)
        }
    }

    // Translate PayloadTooLargeException (thrown either by our Content-Length
    // precheck or by Ktor's RequestBodyLimit byte counter) into a Connect-protocol
    // RESOURCE_EXHAUSTED JSON error.
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

/**
 * Installs both [RequestBodyLimit] (byte counting) and [ConnectBodyLimit] (error
 * translation) on this [Route] with the same [maxBytes] limit.
 *
 * Prefer this helper over installing the two plugins manually: it guarantees that
 * the limits stay in sync and that streamed / chunked requests are capped in
 * addition to those carrying an honest `Content-Length`.
 */
fun Route.connectBodyLimit(maxBytes: Long) {
    install(RequestBodyLimit) {
        bodyLimit { maxBytes }
    }
    install(ConnectBodyLimit) {
        this.maxBytes = maxBytes
    }
}
