package io.github.ichizero.connect.ktor

import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.google.protobuf.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

/**
 * Convert a [ConnectException] to the error response JSON byte array.
 *
 * For more details about the error response, see [Connect Protocol Reference](https://connectrpc.com/docs/protocol#error-end-stream).
 */
fun ConnectException.toErrorJsonBytes(): ByteArray = Json
    .encodeToString(
        ErrorPayload.serializer(),
        ErrorPayload(
            code = code.codeName,
            message = message,
            details = details.map { ErrorDetailPayload.of(it) },
        ),
    ).toByteArray()

/**
 * Error represents an error response of the Connect protocol.
 */
@Serializable
internal data class ErrorPayload(
    val code: String,
    val message: String?,
    val details: List<ErrorDetailPayload>?,
)

/**
 * ErrorDetail represents an error detail of the Connect protocol.
 */
@Serializable
internal data class ErrorDetailPayload(
    val type: String,
    val value: String,
) {
    companion object {
        fun of(detail: ConnectErrorDetail): ErrorDetailPayload = ErrorDetailPayload(
            type = detail.type,
            value = detail.payload.base64(),
        )
    }
}

/**
 * Convert a list of [Message] to a list of [ConnectErrorDetail].
 */
fun List<Message>.toConnectErrorDetails(): List<ConnectErrorDetail> = map {
    ConnectErrorDetail(
        type = it.descriptorForType.fullName,
        payload = it.toByteArray().toByteString(),
    )
}
