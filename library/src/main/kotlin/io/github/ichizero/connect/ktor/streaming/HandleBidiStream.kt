package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.ichizero.ktor.protovalidate.validateStreamingRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * Handle a Connect bidirectional-streaming RPC. Requests are decoded and validated on demand;
 * responses are flushed individually, allowing reads and writes to interleave over HTTP/2.
 * The request flow consumes the channel: sequential collections resume at the next unread
 * message. Only one collector may read at a time, within the lifetime of the call.
 *
 * For half-duplex, collect all requests (for example with `toList()`) before returning the
 * response flow. This also permits use over HTTP/1.1. For full-duplex, return a flow that
 * collects requests and emits responses, or use `channelFlow` for concurrent producers.
 * Set HTTP response headers before returning the flow; append trailers with
 * [connectResponseTrailers]. The timeout budget covers handler setup and flow collection.
 * Cancellation and broken response channels propagate as for [handleServerStream].
 *
 * Use [maxMessageSize] to bound each incoming message. Keep full-duplex routes outside
 * `connectBodyLimit` / Ktor `RequestBodyLimit`: that plugin buffers small request chunks
 * until EOF and prevents request/response interleaving.
 */
inline fun <Resource : Any, reified Req : Any, reified Res : Any> handleBidiStream(
    noinline handlerFunc: suspend (requests: Flow<Req>, call: ApplicationCall) -> Flow<Res>,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
): suspend RoutingContext.(Resource) -> Unit = { _ ->
    handleBidiStreamCall(call, maxMessageSize, Req::class, Res::class, handlerFunc)
}

@PublishedApi
internal suspend fun <Req : Any, Res : Any> handleBidiStreamCall(
    call: ApplicationCall,
    maxMessageSize: Int,
    reqClass: KClass<Req>,
    resClass: KClass<Res>,
    handlerFunc: suspend (Flow<Req>, ApplicationCall) -> Flow<Res>,
) {
    handleResponseStreamCall(call, resClass) { codec ->
        handlerFunc(bidiRequests(codec, reqClass, maxMessageSize), this)
    }
}

private suspend fun <Req : Any> ApplicationCall.bidiRequests(
    codec: StreamingCodec,
    reqClass: KClass<Req>,
    maxMessageSize: Int,
): Flow<Req> {
    val channel = receiveChannel()
    val collecting = AtomicBoolean(false)
    var ended = false
    return flow {
        check(collecting.compareAndSet(false, true)) { "request flow already has an active collector" }
        try {
            if (!ended) {
                channel.readEnvelopeFrames(maxMessageSize).collect { frame ->
                    if (frame.isEndStream) {
                        ended = true
                    } else {
                        if (frame.isCompressed) {
                            throw ConnectException(
                                code = Code.INTERNAL_ERROR,
                                message = "protocol error: sent compressed message without connect-content-encoding",
                            )
                        }
                        val request = codec.decodeRequest(frame, reqClass)
                        validateStreamingRequest(request)
                        emit(request)
                    }
                }
                ended = true
            }
        } finally {
            collecting.set(false)
        }
    }
}
