package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.TypeRegistry
import com.stricteliza.v1.UploadRequest
import com.stricteliza.v1.uploadRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import com.google.protobuf.Any as ProtoAny

/**
 * Exercises both directions through a TypeRegistry — the stock GoogleJavaJSONStrategy only
 * applies the registry on parse, which is the bug this strategy exists to work around.
 */
class ConnectStreamingJsonStrategyTest : FunSpec({
    val registry = TypeRegistry.newBuilder()
        .add(UploadRequest.getDescriptor())
        .build()

    test("printer resolves Any using the registry") {
        // Wrap a message in an Any so the printer must consult the registry.
        val packed = ProtoAny.pack(uploadRequest { chunk = "hello" })

        // Lock in why this strategy exists: the stock strategy fails the same input because
        // its adapter never passes the registry to JsonFormat.printer(). If connect-kotlin
        // fixes this upstream, this assertion fails and this strategy can be deprecated.
        shouldThrow<InvalidProtocolBufferException> {
            GoogleJavaJSONStrategy().codec(ProtoAny::class).serialize(packed)
        }

        val json = ConnectStreamingJsonStrategy(registry)
            .codec(ProtoAny::class)
            .serialize(packed)
            .readUtf8()
        json shouldContain "stricteliza.v1.UploadRequest"
        json shouldContain "hello"
    }

    test("parser round-trips a regular protobuf message") {
        val strategy = ConnectStreamingJsonStrategy(registry)
        val original = uploadRequest { chunk = "abc" }
        val buf = strategy.codec(UploadRequest::class).serialize(original)
        val parsed = strategy.codec(UploadRequest::class).deserialize(buf)
        parsed shouldBe original
    }

    test("rejects non-Message classes") {
        val strategy = ConnectStreamingJsonStrategy(registry)
        shouldThrow<IllegalArgumentException> {
            strategy.codec(String::class)
        }
    }
})
