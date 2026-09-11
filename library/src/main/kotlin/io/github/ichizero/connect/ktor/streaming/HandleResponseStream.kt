package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.http.content.suppressDecompression
import io.ktor.server.request.contentType
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal suspend fun <Res : Any> handleResponseStreamCall(
    call: ApplicationCall,
    resClass: KClass<Res>,
    prepare: suspend ApplicationCall.(StreamingCodec) -> Flow<Res>,
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

    val started = call.startStream(codec, deadline, prepare)

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

private suspend fun <Res : Any> ApplicationCall.startStream(
    codec: StreamingCodec,
    deadline: StreamDeadline?,
    prepare: suspend ApplicationCall.(StreamingCodec) -> Flow<Res>,
): StartedStream<Res> = try {
    StartedStream.Ready(
        withDeadline(deadline) {
            prepare(codec)
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
