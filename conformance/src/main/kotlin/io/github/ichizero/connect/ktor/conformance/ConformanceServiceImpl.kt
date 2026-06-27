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
import com.connectrpc.conformance.v1.UnaryRequest
import com.connectrpc.conformance.v1.UnaryResponse
import com.connectrpc.conformance.v1.UnaryResponseDefinition
import com.connectrpc.conformance.v1.UnimplementedRequest
import com.connectrpc.conformance.v1.UnimplementedResponse
import com.google.protobuf.Message
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import okio.ByteString.Companion.toByteString
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

        val requestInfo = buildClientStreamRequestInfo(call.request, collected)

        val headers = responseDefinition?.responseHeadersList.toMultimap()
        val trailers = responseDefinition?.responseTrailersList.toMultimap()

        if (responseDefinition != null && responseDefinition.responseDelayMs > 0) {
            kotlinx.coroutines.delay(responseDefinition.responseDelayMs.toLong())
        }

        if (responseDefinition != null && responseDefinition.hasError()) {
            val err = responseDefinition.error
            val cause = ConnectException(
                code = connectCodeFor(err.code.number),
                message = if (err.hasMessage()) err.message else null,
            ).withErrorDetails(
                errorParser = NoopErrorDetailParser,
                details = err.detailsList.map {
                    ConnectErrorDetail(
                        type = it.typeUrl.substringAfterLast('/'),
                        payload = it.value.toByteArray().toByteString(),
                    )
                } + ConnectErrorDetail(
                    type = requestInfo.descriptorForType.fullName,
                    payload = requestInfo.toByteArray().toByteString(),
                ),
            )
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

    private suspend fun <Resp : Message> handleUnary(
        responseDefinition: UnaryResponseDefinition?,
        echoMessage: Message,
        call: ApplicationCall,
        successBuilder: (ConformancePayload) -> Resp,
    ): ResponseMessage<Resp> {
        val requestInfo = buildRequestInfo(call.request, echoMessage)

        val headers = responseDefinition?.responseHeadersList.toMultimap()
        val trailers = responseDefinition?.responseTrailersList.toMultimap()

        if (responseDefinition?.responseDelayMs != null && responseDefinition.responseDelayMs > 0) {
            kotlinx.coroutines.delay(responseDefinition.responseDelayMs.toLong())
        }

        if (responseDefinition != null && responseDefinition.hasError()) {
            val err = responseDefinition.error
            val cause = ConnectException(
                code = connectCodeFor(err.code.number),
                message = if (err.hasMessage()) err.message else null,
            ).withErrorDetails(
                errorParser = NoopErrorDetailParser,
                details = err.detailsList.map {
                    ConnectErrorDetail(
                        type = it.typeUrl.substringAfterLast('/'),
                        payload = it.value.toByteArray().toByteString(),
                    )
                } + ConnectErrorDetail(
                    type = requestInfo.descriptorForType.fullName,
                    payload = requestInfo.toByteArray().toByteString(),
                ),
            )
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

private fun buildRequestInfo(request: ApplicationRequest, echoMessage: Message): ConformancePayload.RequestInfo {
    val builder = ConformancePayload.RequestInfo.newBuilder()
    populateRequestHeaders(builder, request)
    builder.addRequests(ProtoAny.pack(echoMessage))
    return builder.build()
}

private fun buildClientStreamRequestInfo(
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
