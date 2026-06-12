package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.CODEC_NAME_JSON
import com.connectrpc.Codec
import com.connectrpc.ErrorDetailParser
import com.connectrpc.SerializationStrategy
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.google.protobuf.Internal
import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.reflect.full.isSubclassOf

/**
 * JSON [SerializationStrategy] for the Connect streaming path that propagates a [TypeRegistry]
 * to BOTH the JSON printer and parser.
 *
 * The stock [com.connectrpc.extensions.GoogleJavaJSONStrategy] uses an adapter that only forwards
 * the registry to the parser side, so serialising a response that embeds a
 * `google.protobuf.Any` whose concrete type lives in [registry] fails with
 * `Cannot find type for url`. Use this strategy when streaming responses round-trip `Any` values
 * — typically wired through [installConnectStreamingCodecs]:
 *
 * ```
 * installConnectStreamingCodecs(
 *     ConnectStreamingStrategies(
 *         proto = GoogleJavaProtobufStrategy(),
 *         json = ConnectStreamingJsonStrategy(typeRegistry),
 *     ),
 * )
 * ```
 *
 * For responses that do not embed `Any`, the default `GoogleJavaJSONStrategy()` is sufficient.
 */
class ConnectStreamingJsonStrategy(
    private val registry: TypeRegistry,
) : SerializationStrategy {
    private val errorDetailParser = GoogleJavaJSONStrategy().errorDetailParser()

    override fun serializationName(): String = CODEC_NAME_JSON

    override fun <E : Any> codec(clazz: KClass<E>): Codec<E> {
        require(clazz.isSubclassOf(Message::class)) {
            "class ${clazz.qualifiedName} does not extend Message"
        }
        @Suppress("UNCHECKED_CAST")
        return RegistryAwareJsonCodec(clazz as KClass<out Message>, registry) as Codec<E>
    }

    override fun errorDetailParser(): ErrorDetailParser = errorDetailParser
}

private class RegistryAwareJsonCodec<E : Message>(
    private val clazz: KClass<E>,
    registry: TypeRegistry,
) : Codec<E> {
    private val printer = JsonFormat.printer().usingTypeRegistry(registry)
    private val parser = JsonFormat.parser().ignoringUnknownFields().usingTypeRegistry(registry)
    private val instance by lazy { Internal.getDefaultInstance(clazz.java) }

    override fun encodingName(): String = CODEC_NAME_JSON

    override fun serialize(message: E): Buffer =
        Buffer().write(printer.print(message).encodeUtf8())

    override fun deterministicSerialize(message: E): Buffer =
        Buffer().write(printer.sortingMapKeys().print(message).encodeUtf8())

    override fun deserialize(source: BufferedSource): E {
        val builder = instance.newBuilderForType()
        parser.merge(source.readUtf8(), builder)
        return clazz.cast(builder.build())
    }
}
