package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import com.connectrpc.conformance.v1.ClientStreamRequest
import com.connectrpc.conformance.v1.ClientStreamResponse
import com.connectrpc.conformance.v1.ConformancePayload
import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.IdempotentUnaryRequest
import com.connectrpc.conformance.v1.IdempotentUnaryResponse
import com.connectrpc.conformance.v1.ServerStreamRequest
import com.connectrpc.conformance.v1.ServerStreamResponse
import com.connectrpc.conformance.v1.UnaryRequest
import com.connectrpc.conformance.v1.UnaryResponse
import com.connectrpc.conformance.v1.UnaryResponseDefinition
import com.connectrpc.conformance.v1.UnimplementedRequest
import com.connectrpc.conformance.v1.UnimplementedResponse
import com.google.protobuf.Message
import io.github.ichizero.connect.ktor.ConnectGetQueryParamsKey
import io.github.ichizero.connect.ktor.streaming.connectResponseTrailers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import okio.ByteString.Companion.toByteString
import com.connectrpc.conformance.v1.Error as ConformanceError
import com.connectrpc.conformance.v1.Header as ConformanceHeader
import com.google.protobuf.Any as ProtoAny

private const val CONNECT_TIMEOUT_HEADER = "Connect-Timeout-Ms"

class ConformanceServiceImpl : ConformanceServiceHandlerInterface {
    override suspend fun unary(
        request: UnaryRequest,
        call: ApplicationCall,
    ): ResponseMessage<UnaryResponse> = handleUnary(
        responseDefinition = if (request.hasResponseDefinition()) request.responseDefinition else null,
        echoMessage = request,
        call = call,
        successBuilder = { payload ->
            UnaryResponse.newBuilder().setPayload(payload).build()
        },
    )

    override suspend fun idempotentUnary(
        request: IdempotentUnaryRequest,
        call: ApplicationCall,
    ): ResponseMessage<IdempotentUnaryResponse> = handleUnary(
        responseDefinition = if (request.hasResponseDefinition()) request.responseDefinition else null,
        echoMessage = request,
        call = call,
        successBuilder = { payload ->
            IdempotentUnaryResponse.newBuilder().setPayload(payload).build()
        },
    )

    override suspend fun unimplemented(
        request: UnimplementedRequest,
        call: ApplicationCall,
    ): ResponseMessage<UnimplementedResponse> = ResponseMessage.Failure(
        cause = ConnectException(code = Code.UNIMPLEMENTED, message = "unimplemented"),
        headers = emptyMap(),
        trailers = emptyMap(),
    )

    override suspend fun clientStream(
        requests: Flow<ClientStreamRequest>,
        call: ApplicationCall,
    ): ResponseMessage<ClientStreamResponse> {
        // Conformance spec: only the first request carries the response definition;
        // every request message must be echoed back via RequestInfo.requests.
        val collected = requests.toList()
        val responseDefinition = collected.firstOrNull()
            ?.takeIf { it.hasResponseDefinition() }
            ?.responseDefinition

        val requestInfo = buildStreamRequestInfo(call.request, collected)

        val headers = responseDefinition?.responseHeadersList.toMultimap()
        val trailers = responseDefinition?.responseTrailersList.toMultimap()

        if (responseDefinition != null && responseDefinition.responseDelayMs > 0) {
            kotlinx.coroutines.delay(responseDefinition.responseDelayMs.toLong())
        }

        if (responseDefinition != null && responseDefinition.hasError()) {
            val cause = responseDefinition.error.toConnectException(requestInfo)
            return ResponseMessage.Failure(cause = cause, headers = headers, trailers = trailers)
        }

        val payload = ConformancePayload.newBuilder()
            .setRequestInfo(requestInfo)
            .apply {
                if (responseDefinition != null &&
                    responseDefinition.responseCase == UnaryResponseDefinition.ResponseCase.RESPONSE_DATA
                ) {
                    data = responseDefinition.responseData
                }
            }
            .build()

        return ResponseMessage.Success(
            message = ClientStreamResponse.newBuilder().setPayload(payload).build(),
            headers = headers,
            trailers = trailers,
        )
    }

    override suspend fun serverStream(
        request: ServerStreamRequest,
        call: ApplicationCall,
    ): Flow<ServerStreamResponse> {
        val responseDefinition = if (request.hasResponseDefinition()) request.responseDefinition else null

        // Headers go out with the response head, trailers with the end-stream frame. Both are set
        // before the flow is collected so they are observable even when the producer is slow or the
        // stream ends in an error.
        responseDefinition?.responseHeadersList?.forEach { header ->
            header.valueList.forEach { call.response.headers.append(header.name, it, safeOnly = false) }
        }
        responseDefinition?.responseTrailersList?.forEach { header ->
            call.connectResponseTrailers().appendAll(header.name, header.valueList)
        }

        val requestInfo = buildStreamRequestInfo(call.request, listOf(request))

        return flow {
            if (responseDefinition == null) return@flow

            var sent = 0
            for (data in responseDefinition.responseDataList) {
                if (responseDefinition.responseDelayMs > 0) {
                    kotlinx.coroutines.delay(responseDefinition.responseDelayMs.toLong())
                }
                val payload = ConformancePayload.newBuilder()
                    .setData(data)
                    // Nothing in the request info changes after the first response, so the
                    // conformance spec only expects it on the first message.
                    .apply { if (sent == 0) setRequestInfo(requestInfo) }
                    .build()
                emit(ServerStreamResponse.newBuilder().setPayload(payload).build())
                sent++
            }

            if (responseDefinition.hasError()) {
                // The request info can only be reported through the error when no response carried it.
                throw responseDefinition.error.toConnectException(requestInfo.takeIf { sent == 0 })
            }
        }
    }

    private suspend fun <Resp : Message> handleUnary(
        responseDefinition: UnaryResponseDefinition?,
        echoMessage: Message,
        call: ApplicationCall,
        successBuilder: (ConformancePayload) -> Resp,
    ): ResponseMessage<Resp> {
        val requestInfo = buildRequestInfo(call, echoMessage)

        val headers = responseDefinition?.responseHeadersList.toMultimap()
        val trailers = responseDefinition?.responseTrailersList.toMultimap()

        if (responseDefinition?.responseDelayMs != null && responseDefinition.responseDelayMs > 0) {
            kotlinx.coroutines.delay(responseDefinition.responseDelayMs.toLong())
        }

        if (responseDefinition != null && responseDefinition.hasError()) {
            val cause = responseDefinition.error.toConnectException(requestInfo)
            return ResponseMessage.Failure(cause = cause, headers = headers, trailers = trailers)
        }

        val payload = ConformancePayload.newBuilder()
            .setRequestInfo(requestInfo)
            .apply {
                if (responseDefinition != null &&
                    responseDefinition.responseCase == UnaryResponseDefinition.ResponseCase.RESPONSE_DATA
                ) {
                    data = responseDefinition.responseData
                }
            }
            .build()

        return ResponseMessage.Success(
            message = successBuilder(payload),
            headers = headers,
            trailers = trailers,
        )
    }
}

private fun buildRequestInfo(call: ApplicationCall, echoMessage: Message): ConformancePayload.RequestInfo {
    val request = call.request
    val builder = ConformancePayload.RequestInfo.newBuilder()
    populateRequestHeaders(builder, request)
    builder.addRequests(ProtoAny.pack(echoMessage))

    // Populate connect_get_info when the request was received via HTTP GET.
    val queryParams = call.attributes.getOrNull(ConnectGetQueryParamsKey)
    if (queryParams != null) {
        val connectGetInfo = ConformancePayload.ConnectGetInfo.newBuilder()
        queryParams.forEach { name, values ->
            connectGetInfo.addQueryParams(
                ConformanceHeader.newBuilder().setName(name).addAllValue(values).build(),
            )
        }
        builder.setConnectGetInfo(connectGetInfo.build())
    }

    return builder.build()
}

private fun buildStreamRequestInfo(
    request: ApplicationRequest,
    echoMessages: List<Message>,
): ConformancePayload.RequestInfo {
    val builder = ConformancePayload.RequestInfo.newBuilder()
    populateRequestHeaders(builder, request)
    echoMessages.forEach { builder.addRequests(ProtoAny.pack(it)) }
    return builder.build()
}

private fun populateRequestHeaders(builder: ConformancePayload.RequestInfo.Builder, request: ApplicationRequest) {
    request.headers.entries().forEach { (name, values) ->
        if (name.equals(CONNECT_TIMEOUT_HEADER, ignoreCase = true)) {
            values.firstOrNull()?.toLongOrNull()?.let { builder.timeoutMs = it }
        }
        builder.addRequestHeaders(
            ConformanceHeader.newBuilder().setName(name).addAllValue(values).build(),
        )
    }
}

private fun List<ConformanceHeader>?.toMultimap(): Map<String, List<String>> {
    if (this.isNullOrEmpty()) return emptyMap()
    val map = linkedMapOf<String, MutableList<String>>()
    for (h in this) {
        map.getOrPut(h.name) { mutableListOf() }.addAll(h.valueList)
    }
    return map
}

/**
 * Build the Connect error the conformance definition asks for. [requestInfo], when non-null, is
 * appended to the error details — the conformance client reads it back from there whenever no
 * response message could carry it.
 */
private fun ConformanceError.toConnectException(
    requestInfo: ConformancePayload.RequestInfo?,
): ConnectException = ConnectException(
    code = connectCodeFor(code.number),
    message = if (hasMessage()) message else null,
).withErrorDetails(
    errorParser = NoopErrorDetailParser,
    details = detailsList.map {
        ConnectErrorDetail(
            type = it.typeUrl.substringAfterLast('/'),
            payload = it.value.toByteArray().toByteString(),
        )
    } + listOfNotNull(
        requestInfo?.let {
            ConnectErrorDetail(
                type = it.descriptorForType.fullName,
                payload = it.toByteArray().toByteString(),
            )
        },
    ),
)

private fun connectCodeFor(protoNumber: Int): Code =
    Code.entries.firstOrNull { it.value == protoNumber } ?: Code.UNKNOWN

/**
 * No-op parser used to satisfy [ConnectException.withErrorDetails]. The conformance
 * runner inspects the raw details on the wire so client-side decoding is not needed.
 */
private object NoopErrorDetailParser : com.connectrpc.ErrorDetailParser {
    override fun <E : Any> unpack(any: com.connectrpc.AnyError, clazz: kotlin.reflect.KClass<E>): E? = null
    override fun parseDetails(bytes: ByteArray): List<com.connectrpc.ConnectErrorDetail> = emptyList()
}
