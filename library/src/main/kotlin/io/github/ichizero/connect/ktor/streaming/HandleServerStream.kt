package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
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
 * Handle a Connect protocol server-streaming RPC on a Ktor route.
 *
 * Wire behavior:
 * - Request body is a single Connect envelope frame holding the request message. A body with no
 *   data frame, or with more than one, is rejected with [Code.UNIMPLEMENTED] — the same shape
 *   connect-go's server-stream handler produces.
 * - The handler returns a cold [Flow] of [Res]. Each emitted message is written as one data frame
 *   and flushed, so the client observes messages as the producer emits them.
 * - The stream terminates with an end-stream frame carrying the trailers accumulated via
 *   [connectResponseTrailers] and, when the flow failed, the error. A [ConnectException] thrown by
 *   the flow supplies both the error and (through its `metadata`) extra trailers.
 * - HTTP status is always 200; streaming errors are conveyed in the end-stream payload.
 *
 * Response headers are set by the handler on `call.response.headers` before the flow is collected,
 * and the response head is flushed ahead of the first message so a slow producer never keeps the
 * client waiting on headers.
 *
 * Cancellation propagates: when the client disconnects, the collector of the handler's flow is
 * cancelled instead of the failure being converted into an end-stream frame nobody will read.
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
    // Connect streaming bodies are length-prefixed (LPM). Any user-installed Ktor Compression
    // plugin would otherwise double-encode the body on output or attempt to decode the request
    // before our framer sees it. Per-message compression negotiated via `Connect-Content-Encoding`
    // is the framer's responsibility (currently unimplemented; see issue #190).
    call.suppressCompression()
    call.suppressDecompression()

    val requestContentType = call.request.contentType()

    // Phase 1: validate headers and resolve codec before any response writing.
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

    // Phase 2: read the single request message and let the handler produce its flow. Failures here
    // happen before any data frame exists, so they render as an end-stream-only body.
    val started = call.startStream(codec, reqClass, maxMessageSize, deadline, handlerFunc)

    // Phase 3: stream the messages. The response head is committed by respondBytesWriter; flushing
    // before the first message pushes it to the client even when the producer is slow, mirroring
    // the empty send connect-go issues at the start of a server stream.
    when (started) {
        is StartedStream.Failed -> call.respondEndStream(codec, started.error)

        is StartedStream.Ready -> call.respondBytesWriter(contentType = codec.streamingContentType) {
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
            handlerFunc(request, this)
        },
    )
} catch (e: TimeoutCancellationException) {
    StartedStream.Failed(deadlineExceeded(e))
} catch (e: CancellationException) {
    throw e
} catch (e: ConnectException) {
    StartedStream.Failed(e)
} catch (e: Throwable) {
    StartedStream.Failed(ConnectException(code = Code.UNKNOWN, message = e.message, exception = e))
}

/**
 * Read the one envelope frame a server-streaming request carries.
 *
 * Both "no message" and "more than one message" are protocol violations for this stream type;
 * connect-go reports them as [Code.UNIMPLEMENTED] and the conformance suite pins that expectation.
 * Reading a second frame before invoking the handler is what makes the second case detectable.
 */
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
} catch (e: Throwable) {
    throw ConnectException(
        code = Code.INVALID_ARGUMENT,
        message = "failed to decode request frame: ${e.message}",
        exception = e,
    )
}

/**
 * Collect [responses] into data frames, returning the error that terminated the stream (or null on
 * normal completion) so the caller can put it in the end-stream frame.
 *
 * Cancellation and broken response channels are rethrown rather than converted: the client is gone,
 * so there is nobody left to read an end-stream frame, and swallowing the cancellation would leave
 * the flow's collector — and whatever resources it holds — running.
 */
private suspend fun <Res : Any> ByteWriteChannel.writeResponseMessages(
    codec: StreamingCodec,
    resClass: KClass<Res>,
    responses: Flow<Res>,
    deadline: StreamDeadline?,
): ConnectException? = try {
    withDeadline(deadline) {
        responses.collect { message ->
            writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = codec.encodeResponse(message, resClass)))
        }
    }
    null
} catch (e: TimeoutCancellationException) {
    deadlineExceeded(e)
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    throw e
} catch (e: ConnectException) {
    e
} catch (e: Throwable) {
    ConnectException(code = Code.UNKNOWN, message = e.message, exception = e)
}

private fun <Res : Any> StreamingCodec.encodeResponse(message: Res, resClass: KClass<Res>): ByteArray = try {
    serialize(message, resClass)
} catch (e: Throwable) {
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
