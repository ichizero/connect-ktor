package io.github.ichizero.connect.ktor

import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import io.ktor.server.application.ApplicationCall

/** Convert explicit Connect failures; let other handler exceptions reach application-wide StatusPages. */
@PublishedApi
internal suspend fun <Res : Any> captureUnaryFailure(
    handler: suspend () -> ResponseMessage<Res>,
): ResponseMessage<Res> = try {
    handler()
} catch (cause: ConnectException) {
    ResponseMessage.Failure(cause, emptyMap(), emptyMap())
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
