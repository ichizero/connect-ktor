package io.github.ichizero.connect.ktor.streaming

import io.ktor.http.HeadersBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import io.ktor.util.toMap

private val ConnectResponseTrailersKey: AttributeKey<HeadersBuilder> =
    AttributeKey("io.github.ichizero.connect.ktor.streaming.ConnectResponseTrailers")

/**
 * Trailers to send in the end-stream frame of a Connect streaming response.
 *
 * Streaming RPCs carry their trailers inside the terminating envelope frame (`metadata`), not as
 * HTTP headers, so they cannot be set through [io.ktor.server.response.ApplicationResponse.headers].
 * A server-streaming handler returns a `Flow`, which has no slot for them either — hence this
 * call-scoped builder.
 *
 * Values are read once, when the end-stream frame is written, so a handler may append to it either
 * before returning the flow or while the flow is being collected. Keys are lowercased on the wire
 * per the Connect protocol.
 *
 * ```
 * override suspend fun tail(request: TailRequest, call: ApplicationCall): Flow<TailResponse> {
 *     call.response.headers.append("x-stream-id", id)          // HTTP response header
 *     call.connectResponseTrailers().append("x-record-count", count.toString()) // end-stream frame
 *     return flow { ... }
 * }
 * ```
 */
fun ApplicationCall.connectResponseTrailers(): HeadersBuilder =
    attributes.computeIfAbsent(ConnectResponseTrailersKey) { HeadersBuilder() }

/**
 * Snapshot of the trailers accumulated via [connectResponseTrailers], or an empty map when the
 * handler never touched them.
 */
internal fun ApplicationCall.connectResponseTrailersSnapshot(): Map<String, List<String>> =
    attributes.getOrNull(ConnectResponseTrailersKey)?.build()?.toMap() ?: emptyMap()

/**
 * Merge end-stream trailers, concatenating values that collide on the same key so nothing is
 * dropped. Keys are lowercased later, when the end-stream payload is built.
 */
internal fun mergeTrailers(
    trailers: Map<String, List<String>>,
    extra: Map<String, List<String>>,
): Map<String, List<String>> = when {
    extra.isEmpty() -> trailers

    trailers.isEmpty() -> extra

    else -> LinkedHashMap(trailers).apply {
        for ((key, values) in extra) {
            merge(key, values) { existing, new -> existing + new }
        }
    }
}
