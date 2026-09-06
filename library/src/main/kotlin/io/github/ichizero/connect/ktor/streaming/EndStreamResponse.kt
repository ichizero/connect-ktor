package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.ConnectException
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel

/**
 * Write the terminating Connect end-stream frame (`flags = 2`) carrying the response trailers and,
 * when the RPC failed, the error. The payload is always JSON regardless of the message codec.
 */
internal suspend fun ByteWriteChannel.writeEndStream(
    error: ConnectException?,
    trailers: Map<String, List<String>>,
) {
    val payload = buildEndStreamPayload(trailers = trailers, error = error)
    writeEnvelopeFrame(EnvelopeFrame(flags = EnvelopeFlags.END_STREAM, payload = payload))
}

/**
 * Respond with a body that consists of nothing but an end-stream frame. Used for failures observed
 * before any data frame could be produced (header validation, codec resolution, request framing).
 */
internal suspend fun respondEndStreamOnly(
    call: ApplicationCall,
    contentType: ContentType,
    error: ConnectException,
    trailers: Map<String, List<String>>,
) {
    call.respondBytesWriter(contentType = contentType) {
        writeEndStream(error = error, trailers = trailers)
    }
}

/**
 * Pick the response Content-Type when the request couldn't even be classified to a codec —
 * echo the request type if it was a recognized streaming type, else fall back to JSON. The
 * end-frame payload itself is always JSON, but the Content-Type drives client-side framing.
 */
internal fun bestEffortResponseContentType(requestContentType: ContentType?): ContentType = when {
    requestContentType == null -> ConnectStreamingContentType.Json
    requestContentType.match(ConnectStreamingContentType.Proto) -> ConnectStreamingContentType.Proto
    requestContentType.match(ConnectStreamingContentType.Json) -> ConnectStreamingContentType.Json
    else -> ConnectStreamingContentType.Json
}
