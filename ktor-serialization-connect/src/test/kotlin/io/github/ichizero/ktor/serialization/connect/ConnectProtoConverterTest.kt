package io.github.ichizero.ktor.serialization.connect

import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.sayRequest
import io.kotest.assertions.json.*
import io.kotest.core.spec.style.*
import io.kotest.matchers.*
import io.ktor.http.content.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.jvm.javaio.*

class ConnectProtoConverterTest : FunSpec({
    test("serialize then deserialize") {
        val target = sayRequest {
            sentence = "hello"
        }

        val serialized = ConnectProtoConverter().serialize(
            contentType = contentTypeConnectJson,
            charset = Charsets.UTF_8,
            typeInfo = TypeInfo(SayRequest::class),
            value = target,
        ) as ByteArrayContent

        // Note: https://protobuf.dev/programming-guides/encoding/
        serialized.bytes() shouldBe byteArrayOf(
            0b0000_1010,
            0b0000_0101,
            'h'.code.toByte(),
            'e'.code.toByte(),
            'l'.code.toByte(),
            'l'.code.toByte(),
            'o'.code.toByte(),
        )

        val deserialized = ConnectProtoConverter().deserialize(
            charset = Charsets.UTF_8,
            typeInfo = TypeInfo(SayRequest::class),
            content = serialized.bytes().inputStream().toByteReadChannel(),
        ) as SayRequest

        deserialized shouldBe target
    }
})
