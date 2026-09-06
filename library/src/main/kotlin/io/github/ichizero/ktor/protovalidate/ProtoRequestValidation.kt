package io.github.ichizero.ktor.protovalidate

import build.buf.protovalidate.ValidationResult
import build.buf.protovalidate.Validator
import build.buf.protovalidate.ValidatorFactory
import build.buf.protovalidate.exceptions.ValidationException
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.google.protobuf.Message
import io.github.ichizero.connect.ktor.toConnectErrorDetails
import io.github.ichizero.connect.ktor.toErrorJsonBytes
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.AttributeKey

/**
 * A plugin that checks a request body using [Validator].
 *
 * If validation fails, it will throw [ProtoRequestValidationException].
 * If there are any validation exceptions, it will throw [ValidationException].
 * Server-streaming requests use the same validator after decoding; violations are returned as
 * INVALID_ARGUMENT end-stream errors with the validation details.
 */
val ProtoRequestValidation: RouteScopedPlugin<ProtoRequestValidationConfig> = createRouteScopedPlugin(
    "ProtoRequestValidation",
    ::ProtoRequestValidationConfig,
) {
    val validator = if (pluginConfig.config !==
        null
    ) {
        ValidatorFactory.newBuilder().withConfig(pluginConfig.config).build()
    } else {
        ValidatorFactory.newBuilder().build()
    }

    onCall { call ->
        call.attributes.put(StreamingRequestValidatorKey, validator)
    }

    on(RequestBodyTransformed) { content ->
        if (content !is Message) return@on

        val result = validator.validate(content)
        if (result.isSuccess) return@on

        throw ProtoRequestValidationException(content, result)
    }
}

/**
 * An exception that is thrown when a request body validation fails.
 */
class ProtoRequestValidationException internal constructor(
    val value: Any,
    val result: ValidationResult,
) : IllegalArgumentException("Validation failed for $value. $result") {
    companion object {
        private val errorDetailParser = GoogleJavaJSONStrategy().errorDetailParser()
    }

    fun toErrorJsonBytes(message: String = "invalid request"): ByteArray =
        toConnectException(message).toErrorJsonBytes()

    internal fun toConnectException(message: String = "invalid request"): ConnectException = ConnectException(
        code = Code.INVALID_ARGUMENT,
        message = message,
    ).withErrorDetails(errorDetailParser, result.violations.map { it.toProto() }.toConnectErrorDetails())
}

private object RequestBodyTransformed : Hook<suspend (content: Any) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (content: Any) -> Unit) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.After) {
            handler(subject)
        }
    }
}

private val StreamingRequestValidatorKey = AttributeKey<Validator>("ConnectStreamingRequestValidator")

/** Validate an already decoded streaming message using the validator installed on this call's route. */
internal fun ApplicationCall.validateStreamingRequest(content: Any) {
    if (content !is Message) return
    val validator = attributes.getOrNull(StreamingRequestValidatorKey) ?: return
    val result = validator.validate(content)
    if (!result.isSuccess) {
        throw ProtoRequestValidationException(content, result).toConnectException()
    }
}
