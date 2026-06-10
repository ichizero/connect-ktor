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

internal class ConnectBodyLimitConfig {
    var maxBytes: Long = Long.MAX_VALUE
}

/**
 * Route-scoped plugin that translates an over-limit [PayloadTooLargeException]
 * into the Connect-protocol `resource_exhausted` JSON error (HTTP 429) instead
 * of Ktor's default 413.
 *
 * **Not part of the public API.**  Installing this plugin in isolation would
 * be a foot-gun: it only handles the error translation, while the actual byte
 * counting is done by Ktor's [RequestBodyLimit].  Wiring just one of the two
 * would silently let `Transfer-Encoding: chunked` payloads (which carry no
 * Content-Length) bypass the cap.  Use [Route.connectBodyLimit] instead — it installs
 * both plugins together with the same limit.
 */
internal val ConnectBodyLimit = createRouteScopedPlugin(
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

    // Translate PayloadTooLargeException into a Connect-protocol JSON error.
    //
    // This is a route-scoped `CallFailed` interceptor, so it runs *before* any
    // app-wide handler for the same exception type.  It does **not** suppress
    // a blanket app-wide `StatusPages.exception<Throwable>` handler though —
    // Ktor's `StatusPages.on(CallFailed)` invokes its handler unconditionally,
    // even when our route-scoped handler has already responded.  See the
    // KDoc on `Route.connectBodyLimit` for guidance.
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
 * Enforces a maximum request body size for Connect RPCs on this [Route].
 *
 * Installs both Ktor's [RequestBodyLimit] (byte counter — also catches
 * `Transfer-Encoding: chunked` bodies that carry no Content-Length) and the
 * Connect error-translation plugin so requests over [maxBytes] are rejected
 * with a Connect-protocol `resource_exhausted` JSON response (HTTP 429)
 * instead of Ktor's default 413.
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
 * ### Interaction with app-wide StatusPages
 *
 * Ktor's `StatusPages` plugin installs an app-wide `CallFailed` interceptor
 * that does not check whether the response has already been sent.  As a
 * result, a blanket `exception<Throwable>` handler installed app-wide will
 * still fire for the [PayloadTooLargeException] this plugin emits and will
 * overwrite the Connect-protocol 429 response.  To preserve the Connect
 * response, either:
 *
 * - register `StatusPages` handlers only for the specific exception types you
 *   actually want to translate (do **not** catch `Throwable` blindly), or
 * - guard inside the `StatusPages` handler:
 *   `if (call.response.isSent) return@exception`.
 *
 * Out of scope (tracked separately):
 * - Evaluating the *decompressed* body size for compressed (e.g. gzip)
 *   requests.  The current implementation caps the on-wire byte count only.
 *   See [#200](https://github.com/ichizero/connect-ktor/issues/200).
 */
fun Route.connectBodyLimit(maxBytes: Long) {
    require(maxBytes > 0) { "maxBytes must be positive, but was $maxBytes" }
    install(RequestBodyLimit) {
        bodyLimit { maxBytes }
    }
    install(ConnectBodyLimit) {
        this.maxBytes = maxBytes
    }
}
