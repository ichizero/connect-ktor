package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import io.github.ichizero.connect.ktor.withConnectTimeout
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.http.content.suppressDecompression
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handle a Connect protocol client-streaming RPC on a Ktor route.
 *
 * Wire behavior:
 * - Request body is parsed as a sequence of Connect envelope frames (data frames are decoded to
 *   [Req] and delivered as a cold [Flow]; the end-stream frame, if any, ends the flow).
 * - The handler returns a single [ResponseMessage] of [Res].
 * - Response body is written as:
 *   - On success: one data frame containing the serialized [Res], followed by an end-stream frame
 *     carrying the response trailers.
 *   - On failure (handler returned [ResponseMessage.Failure] or threw [ConnectException]): an
 *     end-stream frame only, with `error` populated.
 * - HTTP status is always 200; streaming errors are conveyed in the end-stream payload.
 *
 * Errors observed before the response body has begun (header validation, codec resolution) are
 * also reported as an end-stream-only body. The response Content-Type echoes the request when
 * the request advertised a recognized streaming type, falling back to `application/connect+json`
 * otherwise (end-stream payloads themselves are always JSON).
 *
 * Use via the generated route binding:
 * ```
 * post<Procedures.UploadCsv>(handleClientStream(handler::uploadCsv))
 * ```
 *
 * Uses the route-scoped [ConnectStreaming] limit, or 4 MiB when not configured.
 */
inline fun <Resource : Any, reified Req : Any, reified Res : Any> handleClientStream(
    noinline handlerFunc: suspend (requests: Flow<Req>, call: ApplicationCall) -> ResponseMessage<Res>,
): suspend RoutingContext.(Resource) -> Unit = { _ ->
    handleClientStreamCall(call, call.streamingMaxMessageSize(), Req::class, Res::class, handlerFunc)
}

/**
 * Handle a streaming RPC with an explicit receive limit, overriding [ConnectStreaming].
 * The default is retained for binary compatibility; calls omitting the limit select the
 * single-argument overload and resolve route configuration instead.
 */
inline fun <Resource : Any, reified Req : Any, reified Res : Any> handleClientStream(
    noinline handlerFunc: suspend (requests: Flow<Req>, call: ApplicationCall) -> ResponseMessage<Res>,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
): suspend RoutingContext.(Resource) -> Unit {
    val reqClass = Req::class
    val resClass = Res::class
    return { _ ->
        handleClientStreamCall(
            call = call,
            maxMessageSize = maxMessageSize,
            reqClass = reqClass,
            resClass = resClass,
            handlerFunc = handlerFunc,
        )
    }
}

@PublishedApi
// Map arbitrary codec and handler failures, excluding cancellation and JVM errors.
@Suppress("TooGenericExceptionCaught")
internal suspend fun <Req : Any, Res : Any> handleClientStreamCall(
    call: ApplicationCall,
    maxMessageSize: Int,
    reqClass: KClass<Req>,
    resClass: KClass<Res>,
    handlerFunc: suspend (Flow<Req>, ApplicationCall) -> ResponseMessage<Res>,
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

    val timeoutMs = call.connectTimeoutMs()
    val requestChannel = call.receiveChannel()

    val requests: Flow<Req> = requestChannel
        .readEnvelopeFrames(maxMessageSize)
        .filter { !it.isEndStream }
        .map { frame -> codec.decodeClientStreamRequest(frame, reqClass) }

    // Phase 2: run the handler. Catch failures so we can render them as end-stream frames.
    // ConnectException.metadata is propagated into the end-frame `metadata` (i.e. trailers),
    // matching connect-go's MarshalEndStream behaviour.
    val outcome: Outcome<Res> = try {
        val response = withConnectTimeout(timeoutMs?.milliseconds) { handlerFunc(requests, call) }
        Outcome.Success(response)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConnectException) {
        Outcome.Failure(error = e, trailers = e.metadata)
    } catch (e: Exception) {
        Outcome.Failure(
            error = ConnectException(code = Code.UNKNOWN, message = e.message, exception = e),
        )
    }

    // Phase 3: serialize the success message *before* opening the response writer so a codec
    // failure can still be rendered as an end-stream frame.
    val payloadBytes: SerializedOutcome<Res> = serializeOutcome(codec, resClass, outcome)

    // Response headers from the handler (HTTP headers, distinct from end-frame trailers/metadata)
    // must be written before the body. After respondBytesWriter starts, the head is committed.
    writeResponseHeaders(call, payloadBytes.responseHeaders)

    call.respondBytesWriter(contentType = codec.streamingContentType) {
        when (payloadBytes) {
            is SerializedOutcome.SuccessBytes -> {
                writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = payloadBytes.data))
                writeEndStream(error = null, trailers = payloadBytes.trailers)
            }

            is SerializedOutcome.FailureBytes ->
                writeEndStream(error = payloadBytes.error, trailers = payloadBytes.trailers)
        }
    }
}

// Preserve protocol, cancellation, and arbitrary codec failures as distinct outcomes.
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private fun <Req : Any> StreamingCodec.decodeClientStreamRequest(frame: EnvelopeFrame, reqClass: KClass<Req>): Req {
    if (frame.isCompressed) {
        throw ConnectException(code = Code.UNIMPLEMENTED, message = "compressed envelope frames are not supported")
    }
    return try {
        deserialize(frame.payload, reqClass)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConnectException) {
        throw e
    } catch (e: Exception) {
        throw ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "failed to decode request frame: ${e.message}",
            exception = e,
        )
    }
}

private sealed interface Outcome<out R : Any> {
    data class Success<R : Any>(val value: ResponseMessage<R>) : Outcome<R>

    /**
     * Failure path used for handler exceptions. `headers` is intentionally absent because
     * thrown ConnectExceptions cannot carry HTTP response headers (the unary path has no such
     * mechanism either); `ConnectException.metadata` flows into [trailers] per connect-go's
     * `MarshalEndStream` convention.
     */
    data class Failure(
        val error: ConnectException,
        val trailers: Map<String, List<String>> = emptyMap(),
    ) : Outcome<Nothing>
}

private sealed interface SerializedOutcome<out R : Any> {
    val responseHeaders: Map<String, List<String>>

    data class SuccessBytes<R : Any>(
        val data: ByteArray,
        override val responseHeaders: Map<String, List<String>>,
        val trailers: Map<String, List<String>>,
    ) : SerializedOutcome<R>

    data class FailureBytes(
        val error: ConnectException,
        override val responseHeaders: Map<String, List<String>>,
        val trailers: Map<String, List<String>>,
    ) : SerializedOutcome<Nothing>
}

// A custom response codec may throw any ordinary exception.
@Suppress("TooGenericExceptionCaught")
private fun <Res : Any> serializeOutcome(
    codec: StreamingCodec,
    resClass: KClass<Res>,
    outcome: Outcome<Res>,
): SerializedOutcome<Res> = when (outcome) {
    is Outcome.Failure -> SerializedOutcome.FailureBytes(
        error = outcome.error,
        responseHeaders = emptyMap(),
        trailers = outcome.trailers,
    )

    is Outcome.Success -> when (val response = outcome.value) {
        is ResponseMessage.Success ->
            try {
                SerializedOutcome.SuccessBytes(
                    data = codec.serialize(response.message, resClass),
                    responseHeaders = response.headers,
                    trailers = response.trailers,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SerializedOutcome.FailureBytes(
                    error = ConnectException(
                        code = Code.INTERNAL_ERROR,
                        message = "failed to encode response: ${e.message}",
                        exception = e,
                    ),
                    responseHeaders = response.headers,
                    trailers = response.trailers,
                )
            }

        is ResponseMessage.Failure ->
            SerializedOutcome.FailureBytes(
                error = response.cause,
                responseHeaders = response.headers,
                trailers = response.trailers,
            )
    }
}

private fun writeResponseHeaders(call: ApplicationCall, headers: Map<String, List<String>>) {
    if (headers.isEmpty()) return
    for ((name, values) in headers) {
        for (v in values) {
            call.response.headers.append(name, v, safeOnly = false)
        }
    }
}
