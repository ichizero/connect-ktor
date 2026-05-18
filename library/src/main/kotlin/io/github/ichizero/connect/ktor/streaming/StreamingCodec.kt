package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.SerializationStrategy
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import okio.Buffer
import kotlin.reflect.KClass

/**
 * Resolves and exposes the Connect streaming codec (proto or JSON) corresponding to a request's
 * `Content-Type`. Unlike the [io.ktor.serialization.ContentConverter] path used for unary RPCs,
 * the streaming path bypasses Ktor `ContentNegotiation` because envelope framing forbids treating
 * the body as a single message.
 */
internal class StreamingCodec(
    private val strategy: SerializationStrategy,
    val streamingContentType: ContentType,
) {
    fun <T : Any> serialize(value: T, clazz: KClass<T>): ByteArray =
        strategy.codec(clazz).serialize(value).readByteArray()

    fun <T : Any> deserialize(bytes: ByteArray, clazz: KClass<T>): T {
        val buffer = Buffer().write(bytes)
        return strategy.codec(clazz).deserialize(buffer)
    }
}

/**
 * Resolve a [StreamingCodec] for the given request `Content-Type`, using strategies registered
 * on the [Application] via [installConnectStreamingCodecs] (or built-in defaults).
 *
 * Accepts:
 * - `application/connect+proto`
 * - `application/connect+json`
 *
 * Rejects unary content types (`application/json`, `application/proto`) with
 * [Code.UNIMPLEMENTED] because they cannot carry envelope frames.
 */
internal fun resolveStreamingCodec(
    application: Application,
    contentType: ContentType?,
): StreamingCodec {
    if (contentType == null) {
        throw ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "missing Content-Type header",
        )
    }
    val strategies = application.connectStreamingStrategies()
    return when {
        contentType.match(ConnectStreamingContentType.Proto) ->
            StreamingCodec(strategies.proto, ConnectStreamingContentType.Proto)

        contentType.match(ConnectStreamingContentType.Json) ->
            StreamingCodec(strategies.json, ConnectStreamingContentType.Json)

        else -> throw ConnectException(
            code = Code.UNIMPLEMENTED,
            message = "unsupported content-type: $contentType " +
                "(streaming requires application/connect+proto or application/connect+json)",
        )
    }
}

internal object ConnectStreamingContentType {
    val Proto: ContentType = ContentType("application", "connect+proto")
    val Json: ContentType = ContentType("application", "connect+json")
}

/**
 * SerializationStrategies used by the Connect streaming path. Users can override these (most
 * commonly to supply a [com.google.protobuf.TypeRegistry] to [GoogleJavaJSONStrategy] for
 * `google.protobuf.Any` round-trip) via [installConnectStreamingCodecs].
 */
class ConnectStreamingStrategies(
    val proto: SerializationStrategy,
    val json: SerializationStrategy,
)

private val ConnectStreamingStrategiesKey: AttributeKey<ConnectStreamingStrategies> =
    AttributeKey("io.github.ichizero.connect.ktor.streaming.ConnectStreamingStrategies")

/**
 * Register custom [SerializationStrategy] instances for the Connect streaming path.
 *
 * Use this when the default `GoogleJavaJSONStrategy()` needs a non-empty
 * [com.google.protobuf.TypeRegistry] — e.g. when responses contain `google.protobuf.Any` whose
 * concrete message types must round-trip through JSON.
 *
 * Mirrors the role of `ContentNegotiation { connectProto() }` on the unary path.
 */
fun Application.installConnectStreamingCodecs(strategies: ConnectStreamingStrategies) {
    attributes.put(ConnectStreamingStrategiesKey, strategies)
}

internal fun Application.connectStreamingStrategies(): ConnectStreamingStrategies =
    attributes.getOrNull(ConnectStreamingStrategiesKey)
        ?: ConnectStreamingStrategies(
            proto = GoogleJavaProtobufStrategy(),
            json = GoogleJavaJSONStrategy(),
        )
