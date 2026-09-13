package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.ichizero.ktor.protovalidate.validateStreamingRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.reflect.KClass

/**
 * Handle a Connect server-streaming RPC with one request envelope and a cold [Flow] of responses.
 * Zero or multiple request messages are rejected with [Code.UNIMPLEMENTED]. Each response is
 * flushed as a data frame, followed by an end-stream frame containing trailers and any RPC error.
 * Streaming responses use HTTP 200; RPC errors are carried in the end-stream payload.
 *
 * Set response headers on `call.response.headers` before returning the flow. Trailers can be
 * appended through [connectResponseTrailers] until collection ends; [ConnectException.metadata]
 * is merged into them on failure. Cancellation, broken response channels, and JVM errors propagate.
 *
 * Use via the generated route binding:
 * ```
 * post<Procedures.Tail>(handleServerStream(handler::tail))
 * ```
 */
inline fun <Resource : Any, reified Req : Any, reified Res : Any> handleServerStream(
    noinline handlerFunc: suspend (request: Req, call: ApplicationCall) -> Flow<Res>,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
): suspend RoutingContext.(Resource) -> Unit {
    val reqClass = Req::class
    val resClass = Res::class
    return { _ ->
        handleServerStreamCall(
            call = call,
            maxMessageSize = maxMessageSize,
            reqClass = reqClass,
            resClass = resClass,
            handlerFunc = handlerFunc,
        )
    }
}

@PublishedApi
internal suspend fun <Req : Any, Res : Any> handleServerStreamCall(
    call: ApplicationCall,
    maxMessageSize: Int,
    reqClass: KClass<Req>,
    resClass: KClass<Res>,
    handlerFunc: suspend (Req, ApplicationCall) -> Flow<Res>,
) {
    handleResponseStreamCall(call, resClass) { codec ->
        val request = codec.decodeRequest(receiveRequestFrame(maxMessageSize), reqClass)
        validateStreamingRequest(request)
        handlerFunc(request, this)
    }
}

// Read up to two data frames to distinguish missing and extra messages, matching connect-go's
// UNIMPLEMENTED response for these protocol violations.
private suspend fun ApplicationCall.receiveRequestFrame(maxMessageSize: Int): EnvelopeFrame {
    val frames = receiveChannel()
        .readEnvelopeFrames(maxMessageSize)
        .filter { !it.isEndStream }
        .take(2)
        .toList()

    val violation = when {
        frames.isEmpty() -> "unary request has zero messages"
        frames.size > 1 -> "unary request has multiple messages"
        else -> null
    }
    if (violation != null) {
        throw ConnectException(code = Code.UNIMPLEMENTED, message = violation)
    }

    val frame = frames.first()
    if (frame.isCompressed) {
        // A compressed frame without negotiated compression is a protocol error rather than a
        // missing feature — connect-go's envelopeReader reports it the same way.
        throw ConnectException(
            code = Code.INTERNAL_ERROR,
            message = "protocol error: sent compressed message without connect-content-encoding",
        )
    }
    return frame
}

internal fun <Req : Any> StreamingCodec.decodeRequest(frame: EnvelopeFrame, reqClass: KClass<Req>): Req = try {
    deserialize(frame.payload, reqClass)
} catch (e: ConnectException) {
    throw e
} catch (e: Exception) {
    throw ConnectException(
        code = Code.INVALID_ARGUMENT,
        message = "failed to decode request frame: ${e.message}",
        exception = e,
    )
}
