package io.github.ichizero.ktor.serialization.connect

import com.connectrpc.eliza.v1.SayRequest
import com.connectrpc.eliza.v1.sayRequest
import io.kotest.assertions.json.*
import io.kotest.core.spec.style.*
import io.kotest.matchers.*
import io.ktor.http.content.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.jvm.javaio.*

class ConnectJsonConverterTest : FunSpec({
    test("serialize then deserialize") {
        val target = sayRequest {
            sentence = "hello"
        }

        val serialized = ConnectJsonConverter().serialize(
            contentType = contentTypeConnectJson,
            charset = Charsets.UTF_8,
            typeInfo = TypeInfo(SayRequest::class),
            value = target,
        ) as TextContent

        serialized.text shouldEqualJson """{"sentence":"hello"}"""

        val deserialized = ConnectJsonConverter().deserialize(
            charset = Charsets.UTF_8,
            typeInfo = TypeInfo(SayRequest::class),
            content = serialized.bytes().inputStream().toByteReadChannel(),
        ) as SayRequest

        deserialized shouldBe target
    }
})
