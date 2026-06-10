package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.protocols.CONNECT_PROTOCOL_VERSION_KEY
import com.connectrpc.protocols.CONNECT_PROTOCOL_VERSION_VALUE
import com.connectrpc.protocols.CONNECT_STREAMING_CONTENT_ENCODING
import com.connectrpc.protocols.CONNECT_TIMEOUT_MS
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Validate Connect streaming request headers.
 *
 * - `Connect-Protocol-Version`: optional, but if present must be `1`
 *   ([com.connectrpc.protocols.CONNECT_PROTOCOL_VERSION_VALUE]).
 * - `Connect-Content-Encoding`: optional, but if present must be `identity`
 *   (compression is not implemented yet).
 */
internal fun ApplicationCall.validateConnectStreamingHeaders() {
    request.header(CONNECT_PROTOCOL_VERSION_KEY)?.let { version ->
        if (version != CONNECT_PROTOCOL_VERSION_VALUE) {
            throw ConnectException(
                code = Code.INVALID_ARGUMENT,
                message = "unsupported $CONNECT_PROTOCOL_VERSION_KEY: $version",
            )
        }
    }
    request.header(CONNECT_STREAMING_CONTENT_ENCODING)?.let { encoding ->
        if (!encoding.equals("identity", ignoreCase = true)) {
            throw ConnectException(
                code = Code.UNIMPLEMENTED,
                message = "$CONNECT_STREAMING_CONTENT_ENCODING $encoding is not supported",
            )
        }
    }
}

/**
 * Parse `Connect-Timeout-Ms` request header. Returns null when the header is absent, malformed,
 * or non-positive.
 */
internal fun ApplicationCall.connectTimeoutMs(): Long? =
    request.header(CONNECT_TIMEOUT_MS)?.toLongOrNull()?.takeIf { it > 0 }
