package io.github.ichizero.connect.ktor.conformance

import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharsetIfNeeded
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.JsonConvertException
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connect JSON ContentConverter that knows how to print and parse [com.google.protobuf.Any]
 * fields by routing all serialization through a shared [TypeRegistry].
 *
 * Used by the conformance server because [com.connectrpc.extensions.GoogleJavaJSONAdapter]
 * does not propagate the registry when printing messages (only when parsing them).
 */
class ConformanceJsonConverter(private val registry: TypeRegistry) : ContentConverter {
    private val printer = JsonFormat.printer().usingTypeRegistry(registry)
    private val parser = JsonFormat.parser().ignoringUnknownFields().usingTypeRegistry(registry)

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent {
        val ct = contentType.withCharsetIfNeeded(charset)
        if (value !is Message) {
            return TextContent(if (value == null) "null" else value.toString(), ct)
        }
        return TextContent(printer.print(value), ct)
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any = withContext(Dispatchers.IO) {
        val builder = newBuilderFor(typeInfo.type.java as Class<out Message>)
        try {
            parser.merge(content.toInputStream().reader(charset), builder)
            builder.build()
        } catch (cause: Throwable) {
            throw JsonConvertException("Failed to deserialize Connect JSON: ${cause.message}", cause)
        }
    }

    private fun newBuilderFor(clazz: Class<out Message>): Message.Builder {
        val defaultInstance = clazz.getMethod("getDefaultInstance").invoke(null) as Message
        return defaultInstance.newBuilderForType()
    }
}
