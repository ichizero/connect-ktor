package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentLength
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Route-scoped plugin that enforces the Connect message receive limit on the *decoded* body and
 * translates an over-limit [PayloadTooLargeException] into the Connect-protocol
 * `resource_exhausted` JSON error (HTTP 429) instead of Ktor's default 413.
 *
 * **Not part of the public API.**  Installing this plugin in isolation would be a foot-gun: it
 * relies on Ktor's [RequestBodyLimit] to count the bytes of uncompressed requests, and wiring
 * just one of the two would silently let `Transfer-Encoding: chunked` payloads (which carry no
 * Content-Length) bypass the cap.  Use [Route.connectBodyLimit] instead — it installs both
 * plugins together with the same limit.
 */
internal val ConnectBodyLimit = createRouteScopedPlugin(
    "ConnectBodyLimit",
    ::ConnectBodyLimitConfig,
) {
    val limit = pluginConfig.maxBytes

    // Defense-in-depth: if Content-Length is set and already over the limit, fail fast before
    // reading any bytes.  When Content-Length is absent or lies (e.g. Transfer-Encoding:
    // chunked), RequestBodyLimit still enforces the cap by counting bytes as they are streamed
    // in.  Compressed requests are skipped: their Content-Length measures the *encoded* payload,
    // which says nothing about the decoded size the Connect spec caps.
    onCall { call ->
        // Latch the decision here, at the call pipeline's Plugins phase: Ktor's Compression
        // plugin drops the Content-Encoding header once it has decoded the body, so by the time
        // the receive pipeline reaches the Transform hook below the header is gone.
        val contentEncoded = call.request.isContentEncoded()
        call.attributes.put(ContentEncodedKey, contentEncoded)

        if (contentEncoded) return@onCall
        val contentLength = call.request.contentLength() ?: return@onCall
        if (contentLength > limit) {
            throw PayloadTooLargeException(limit)
        }
    }

    // Count the *decoded* bytes of compressed requests.  [ReceiveDecodedBody] runs after the
    // Compression plugin's decode and ahead of every body transformer, so the channel below
    // carries decompressed bytes.  Reading at most `limit + 1` of them bounds both the check and
    // the memory a single request can claim, no matter how far the payload inflates.
    on(ReceiveDecodedBody) { call, body ->
        if (!call.isContentEncoded()) return@on body

        val decoded = body.readAtMost(limit + 1)
        if (decoded.size > limit) {
            throw PayloadTooLargeException(limit)
        }
        ByteReadChannel(decoded.readByteArray())
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
        if (cause.unwrapPayloadTooLarge() == null) return@on

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

/** Chunk size used to grow the decode buffer, so that [readAtMost] can honour a `Long` budget. */
private const val READ_CHUNK_BYTES = 64 * 1024

/**
 * Reads up to [max] bytes from this channel, stopping early at end-of-stream.
 *
 * [io.ktor.utils.io.readBuffer] takes an `Int`, so the read is issued in [READ_CHUNK_BYTES]
 * chunks; a short chunk means the channel is exhausted.
 */
private suspend fun ByteReadChannel.readAtMost(max: Long): Buffer {
    val result = Buffer()
    var remaining = max
    while (remaining > 0) {
        val requested = minOf(remaining, READ_CHUNK_BYTES.toLong()).toInt()
        val chunk = readBuffer(requested)
        val read = chunk.size
        result.transferFrom(chunk)
        if (read < requested) break
        remaining -= read
    }
    return result
}

/**
 * Records whether the request arrived compressed, latched before anything reads the body.
 * Ktor's `Compression` plugin removes the `Content-Encoding` header while decoding, so the
 * header cannot be consulted from the receive pipeline's `Transform` phase.
 */
private val ContentEncodedKey = AttributeKey<Boolean>("ConnectBodyLimitContentEncoded")

/**
 * Whether this call arrived with a non-`identity` `Content-Encoding`, using the value latched by
 * [ConnectBodyLimit] and falling back to the header for calls that never passed through it.
 */
private fun ApplicationCall.isContentEncoded(): Boolean =
    attributes.getOrNull(ContentEncodedKey) ?: request.isContentEncoded()

/**
 * Whether the request carries a `Content-Encoding` other than `identity`, i.e. whether its
 * on-the-wire byte count differs from the decoded message size.
 */
private fun ApplicationRequest.isContentEncoded(): Boolean {
    val header = headers[HttpHeaders.ContentEncoding]?.trim()?.takeIf { it.isNotEmpty() }
        ?: return false
    return header.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .any { !it.equals("identity", ignoreCase = true) }
}

/**
 * Finds a [PayloadTooLargeException] in the cause chain.  The limit raised while a deserializer
 * (e.g. `ContentNegotiation`) reads the counted channel surfaces wrapped in that deserializer's
 * own exception type, so an identity check on [Throwable] alone is not enough.
 */
private fun Throwable.unwrapPayloadTooLarge(): PayloadTooLargeException? {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is PayloadTooLargeException) return current
        current = current.cause
    }
    return null
}

/**
 * Enforces a maximum request message size for Connect RPCs on this [Route].
 *
 * Requests over [maxBytes] are rejected with a Connect-protocol `resource_exhausted` JSON
 * response (HTTP 429) instead of Ktor's default 413.  Following the
 * [Connect protocol](https://connectrpc.com/docs/protocol#error-codes) — and `connect-go`'s
 * `connect.WithReadMaxBytes(n)` — the cap is evaluated against the **decoded** message size:
 *
 * - **Uncompressed requests** are capped by Ktor's [RequestBodyLimit], which counts bytes as
 *   they stream in (so `Transfer-Encoding: chunked` bodies carrying no Content-Length are capped
 *   too), plus a Content-Length fast path that rejects before any byte is read.
 * - **Compressed requests** (a `Content-Encoding` other than `identity`) are capped against the
 *   post-decompression byte count instead, so a small payload that inflates past the limit — a
 *   decompression bomb — is rejected rather than silently accepted.  The wire-byte cap is
 *   deliberately *not* applied to them: compressing can grow incompressible payloads slightly,
 *   which would reject legitimate requests sitting just under the limit.
 *
 * This caps the size of the whole HTTP request body, which for unary RPCs is a single message.
 * It is intended for unary RPCs; it does **not** implement the per-message receive limit that
 * streaming RPCs require (the error would also be emitted as a unary JSON response rather than a
 * streaming end-of-stream frame).
 *
 * The decoded-size check runs in its own receive-pipeline phase, inserted after the `Compression`
 * plugin's decode and ahead of every body transformer.  It therefore holds however the handler
 * receives the body — `receive<ByteArray>()`, `receiveText()` or a `ContentNegotiation`
 * deserializer — and wherever `ContentNegotiation` happens to be installed.
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
 */
public fun Route.connectBodyLimit(maxBytes: Long) {
    require(maxBytes in 1 until Long.MAX_VALUE) {
        "maxBytes must be in 1..${Long.MAX_VALUE - 1}, but was $maxBytes"
    }
    install(RequestBodyLimit) {
        bodyLimit { call ->
            // Compressed bodies are capped on their decoded size by ConnectBodyLimit instead;
            // see the KDoc above.
            if (call.isContentEncoded()) Long.MAX_VALUE else maxBytes
        }
    }
    install(ConnectBodyLimit) {
        this.maxBytes = maxBytes
    }
}
