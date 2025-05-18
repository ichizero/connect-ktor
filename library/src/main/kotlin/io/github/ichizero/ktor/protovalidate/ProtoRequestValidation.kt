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

/**
 * A plugin that checks a request body using [Validator].
 *
 * If validation fails, it will throw [ProtoRequestValidationException].
 * If there are any validation exceptions, it will throw [ValidationException].
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

    fun toErrorJsonBytes(message: String = "invalid request"): ByteArray = ConnectException(
        code = Code.INVALID_ARGUMENT,
        message = message,
    ).withErrorDetails(errorDetailParser, result.violations.map { it.toProto() }.toConnectErrorDetails())
        .toErrorJsonBytes()
}

private object RequestBodyTransformed : Hook<suspend (content: Any) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (content: Any) -> Unit) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.After) {
            handler(subject)
        }
    }
}
