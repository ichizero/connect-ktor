package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.ConnectException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Connect streaming end-of-stream message JSON.
 *
 * Wire format matches connect-go `connectEndStreamMessage`:
 * ```
 * { "error": { ... }?, "metadata": { ... }? }
 * ```
 *
 * Both fields are omitted when empty/null; an end-stream payload with neither field serializes to `{}`.
 */
@Serializable
internal data class EndStreamPayload(
    val error: EndStreamError? = null,
    val metadata: Map<String, List<String>>? = null,
)

@Serializable
internal data class EndStreamError(
    val code: String,
    val message: String? = null,
    val details: List<EndStreamErrorDetail>? = null,
)

@Serializable
internal data class EndStreamErrorDetail(
    val type: String,
    val value: String,
)

private val EndStreamJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Build an end-of-stream payload as UTF-8 JSON bytes.
 *
 * @param trailers response trailers; omitted from the payload when empty.
 * @param error optional [ConnectException]; when non-null, encoded under the `error` field.
 */
internal fun buildEndStreamPayload(
    trailers: Map<String, List<String>>,
    error: ConnectException?,
): ByteArray {
    val payload = EndStreamPayload(
        error = error?.let { it.toEndStreamError() },
        metadata = trailers.normalizeKeys().takeIf { it.isNotEmpty() },
    )
    return EndStreamJson.encodeToString(EndStreamPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
}

/**
 * Normalize metadata keys to lowercase per the Connect protocol spec, merging values when
 * multiple input keys collide on the same lowercase form (so values are never silently dropped).
 * Returns a [LinkedHashMap] so wire output preserves first-seen ordering for deterministic JSON.
 */
private fun Map<String, List<String>>.normalizeKeys(): Map<String, List<String>> {
    if (isEmpty()) return this
    val out = LinkedHashMap<String, List<String>>(size)
    for ((k, v) in this) {
        val key = k.lowercase()
        val existing = out[key]
        out[key] = if (existing == null) v else existing + v
    }
    return out
}

private fun ConnectException.toEndStreamError(): EndStreamError = EndStreamError(
    code = code.codeName,
    message = message,
    details = details.takeIf { it.isNotEmpty() }?.map {
        // Connect protocol requires unpadded base64 for error details.
        // https://connectrpc.com/docs/protocol/#error-end-stream
        EndStreamErrorDetail(type = it.type, value = it.payload.base64().trimEnd('='))
    },
)
