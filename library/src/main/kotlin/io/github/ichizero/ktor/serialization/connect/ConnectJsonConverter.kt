package io.github.ichizero.ktor.serialization.connect

import com.connectrpc.extensions.GoogleJavaJSONStrategy
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import okio.buffer
import okio.source
import kotlin.reflect.KClass

/**
 * A JSON content converter for Connect Protocol.
 */
class ConnectJsonConverter : ContentConverter {
    private val serializationStrategy = GoogleJavaJSONStrategy()

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent {
        val ct = contentType.withCharsetIfNeeded(charset)

        if (value == null) return TextContent("null", ct)

        @Suppress("UNCHECKED_CAST")
        return TextContent(
            text = serializationStrategy
                .codec(typeInfo.type as KClass<Any>)
                .serialize(value)
                .readString(charset),
            contentType = ct,
        )
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            serializationStrategy
                .codec(typeInfo.type)
                .deserialize(content.toInputStream().source().buffer())
        }.getOrElse { cause ->
            throw JsonConvertException("Failed to deserialize JSON: ${cause.message}", cause)
        }
    }
}
