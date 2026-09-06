package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.ichizero.ktor.protovalidate.validateStreamingRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.http.content.suppressDecompression
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
    // Envelope framing owns compression; HTTP compression plugins must not transform these bodies.
    call.suppressCompression()
    call.suppressDecompression()

    val requestContentType = call.request.contentType()

    // Resolve the codec before opening the response writer so header failures can use a fallback type.
    val codec: StreamingCodec = try {
        call.validateConnectStreamingHeaders()
        resolveStreamingCodec(call.application, requestContentType)
    } catch (e: ConnectException) {
        respondEndStreamOnly(
            call = call,
            contentType = bestEffortResponseContentType(requestContentType),
            error = e,
            trailers = emptyMap(),
        )
        return
    }

    val deadline = call.connectTimeoutMs()?.let(::StreamDeadline)

    val started = call.startStream(codec, reqClass, maxMessageSize, deadline, handlerFunc)

    when (started) {
        is StartedStream.Failed -> call.respondEndStream(codec, started.error)

        is StartedStream.Ready -> call.respondBytesWriter(contentType = codec.streamingContentType) {
            // Send headers before collection so a slow producer does not delay the response head.
            flush()
            val error = writeResponseMessages(codec, resClass, started.responses, deadline)
            writeEndStream(
                error = error,
                trailers = mergeTrailers(call.connectResponseTrailersSnapshot(), error?.metadata.orEmpty()),
            )
        }
    }
}

private sealed interface StartedStream<out R : Any> {
    data class Ready<R : Any>(val responses: Flow<R>) : StartedStream<R>

    data class Failed(val error: ConnectException) : StartedStream<Nothing>
}

private suspend fun <Req : Any, Res : Any> ApplicationCall.startStream(
    codec: StreamingCodec,
    reqClass: KClass<Req>,
    maxMessageSize: Int,
    deadline: StreamDeadline?,
    handlerFunc: suspend (Req, ApplicationCall) -> Flow<Res>,
): StartedStream<Res> = try {
    StartedStream.Ready(
        withDeadline(deadline) {
            val request = codec.decodeRequest(receiveRequestFrame(maxMessageSize), reqClass)
            validateStreamingRequest(request)
            handlerFunc(request, this)
        },
    )
} catch (e: TimeoutCancellationException) {
    StartedStream.Failed(deadlineExceeded(e))
} catch (e: CancellationException) {
    throw e
} catch (e: ConnectException) {
    StartedStream.Failed(e)
} catch (e: Exception) {
    StartedStream.Failed(ConnectException(code = Code.UNKNOWN, message = e.message, exception = e))
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

private fun <Req : Any> StreamingCodec.decodeRequest(frame: EnvelopeFrame, reqClass: KClass<Req>): Req = try {
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

/** Return the terminating RPC error, or null on successful collection. */
private suspend fun <Res : Any> ByteWriteChannel.writeResponseMessages(
    codec: StreamingCodec,
    resClass: KClass<Res>,
    responses: Flow<Res>,
    deadline: StreamDeadline?,
): ConnectException? = try {
    var producerError: ConnectException? = null
    withDeadline(deadline) {
        // Flow.catch only handles upstream failures, leaving response-channel failures to propagate.
        responses.catch { cause ->
            producerError = when (cause) {
                is CancellationException -> throw cause
                is ConnectException -> cause
                is Exception -> ConnectException(code = Code.UNKNOWN, message = cause.message, exception = cause)
                else -> throw cause
            }
        }.collect { message ->
            writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = codec.encodeResponse(message, resClass)))
        }
    }
    producerError
} catch (e: TimeoutCancellationException) {
    deadlineExceeded(e)
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    throw e
} catch (e: ConnectException) {
    e
} catch (e: Exception) {
    ConnectException(code = Code.UNKNOWN, message = e.message, exception = e)
}

private fun <Res : Any> StreamingCodec.encodeResponse(message: Res, resClass: KClass<Res>): ByteArray = try {
    serialize(message, resClass)
} catch (e: Exception) {
    throw ConnectException(
        code = Code.INTERNAL_ERROR,
        message = "failed to encode response: ${e.message}",
        exception = e,
    )
}

private suspend fun ApplicationCall.respondEndStream(codec: StreamingCodec, error: ConnectException) {
    respondEndStreamOnly(
        call = this,
        contentType = codec.streamingContentType,
        error = error,
        trailers = mergeTrailers(connectResponseTrailersSnapshot(), error.metadata),
    )
}

private fun deadlineExceeded(cause: Throwable): ConnectException = ConnectException(
    code = Code.DEADLINE_EXCEEDED,
    message = "deadline exceeded",
    exception = cause,
)

/**
 * The `Connect-Timeout-Ms` budget, shared by the request-read and response-streaming phases so both
 * are bounded by the same deadline instead of each getting a fresh one.
 */
private class StreamDeadline(timeoutMs: Long) {
    private val start = TimeSource.Monotonic.markNow()
    private val budget = timeoutMs.milliseconds

    fun remaining(): Duration = budget - start.elapsedNow()
}

private suspend fun <T> withDeadline(deadline: StreamDeadline?, block: suspend () -> T): T =
    if (deadline == null) block() else withTimeout(deadline.remaining()) { block() }
