package io.github.ichizero.connect.ktor

import com.connectrpc.SerializationStrategy
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlin.reflect.KClass

/**
 * A JSON serializer that can be overridden for the Connect GET response path.
 *
 * Implement this interface when the default [SerializationStrategy]-based serializer does not
 * produce the correct JSON — for example when responses contain `google.protobuf.Any` fields
 * and [com.connectrpc.extensions.GoogleJavaJSONStrategy] omits the TypeRegistry when printing.
 */
interface ConnectGetJsonSerializer {
    fun <T : Any> serialize(value: T, clazz: KClass<T>): ByteArray
}

/**
 * Serialization strategies used by the Connect GET path (query-parameter encoded unary requests).
 *
 * Users can override these (most commonly to supply a
 * [com.google.protobuf.TypeRegistry] to the JSON codec for `google.protobuf.Any` round-trip) via
 * [installConnectGetCodecs].
 *
 * @param proto strategy used when `encoding=proto` is in the query parameters
 * @param json strategy used when `encoding=json` is in the query parameters
 * @param jsonSerializer optional override for the JSON response serializer. Use this when
 *   the default [SerializationStrategy]-based serializer does not produce the correct JSON.
 *   When provided, this function is used instead of `json.codec(clazz).serialize(value)`.
 */
class ConnectGetStrategies(
    val proto: SerializationStrategy,
    val json: SerializationStrategy,
    val jsonSerializer: ConnectGetJsonSerializer? = null,
)

private val ConnectGetStrategiesKey: AttributeKey<ConnectGetStrategies> =
    AttributeKey("io.github.ichizero.connect.ktor.ConnectGetStrategies")

/**
 * Register custom [SerializationStrategy] instances for the Connect GET path.
 *
 * Use this when the default `GoogleJavaJSONStrategy()` needs a non-empty
 * [com.google.protobuf.TypeRegistry] — e.g. when responses contain `google.protobuf.Any`
 * whose concrete message types must round-trip through JSON.
 *
 * Mirrors the role of `ContentNegotiation { connectJson() }` on the POST path.
 */
fun Application.installConnectGetCodecs(strategies: ConnectGetStrategies) {
    attributes.put(ConnectGetStrategiesKey, strategies)
}

internal fun Application.connectGetStrategies(): ConnectGetStrategies =
    attributes.getOrNull(ConnectGetStrategiesKey)
        ?: ConnectGetStrategies(
            proto = GoogleJavaProtobufStrategy(),
            json = GoogleJavaJSONStrategy(),
        )
