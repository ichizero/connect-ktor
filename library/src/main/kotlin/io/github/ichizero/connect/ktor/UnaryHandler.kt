package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException

/** Convert handler failures before Ktor's application-wide StatusPages sees them. */
@PublishedApi
@Suppress("TooGenericExceptionCaught") // An RPC must turn unexpected handler exceptions into UNKNOWN.
internal suspend fun <Res : Any> captureUnaryFailure(
    handler: suspend () -> ResponseMessage<Res>,
): ResponseMessage<Res> = try {
    handler()
} catch (cause: CancellationException) {
    throw cause
} catch (cause: ConnectException) {
    ResponseMessage.Failure(cause, emptyMap(), emptyMap())
} catch (cause: Exception) {
    ResponseMessage.Failure(
        ConnectException(code = Code.UNKNOWN, message = "internal server error", exception = cause),
        emptyMap(),
        emptyMap(),
    )
}

@PublishedApi
internal fun ApplicationCall.appendUnaryMetadata(message: ResponseMessage<*>) {
    message.headers.forEach { (key, values) ->
        values.forEach { response.headers.append(key, it) }
    }
    if (message is ResponseMessage.Failure) {
        message.cause.metadata.forEach { (key, values) ->
            values.forEach { response.headers.append("Trailer-$key", it) }
        }
    }
    message.trailers.forEach { (key, values) ->
        values.forEach { response.headers.append("Trailer-$key", it) }
    }
}
