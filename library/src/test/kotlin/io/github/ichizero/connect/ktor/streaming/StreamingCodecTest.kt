package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication

class StreamingCodecTest : FunSpec({
    test("resolves application/connect+proto to a proto codec") {
        withApplication { app ->
            val codec = resolveStreamingCodec(app, ContentType("application", "connect+proto"))
            codec.streamingContentType.contentType shouldBe "application"
            codec.streamingContentType.contentSubtype shouldBe "connect+proto"
        }
    }

    test("resolves application/connect+json to a json codec") {
        withApplication { app ->
            val codec = resolveStreamingCodec(app, ContentType("application", "connect+json"))
            codec.streamingContentType.contentSubtype shouldBe "connect+json"
        }
    }

    test("ignores charset parameter when matching content type") {
        withApplication { app ->
            val codec = resolveStreamingCodec(
                app,
                ContentType("application", "connect+json").withParameter("charset", "utf-8"),
            )
            codec.streamingContentType.contentSubtype shouldBe "connect+json"
        }
    }

    test("rejects null content-type with INVALID_ARGUMENT") {
        withApplication { app ->
            val ex = shouldThrow<ConnectException> { resolveStreamingCodec(app, null) }
            ex.code shouldBe Code.INVALID_ARGUMENT
        }
    }

    test("rejects unary content-type (application/json) with UNIMPLEMENTED") {
        withApplication { app ->
            val ex = shouldThrow<ConnectException> {
                resolveStreamingCodec(app, ContentType("application", "json"))
            }
            ex.code shouldBe Code.UNIMPLEMENTED
        }
    }

    test("rejects unary content-type (application/proto) with UNIMPLEMENTED") {
        withApplication { app ->
            val ex = shouldThrow<ConnectException> {
                resolveStreamingCodec(app, ContentType("application", "proto"))
            }
            ex.code shouldBe Code.UNIMPLEMENTED
        }
    }
})

private suspend fun withApplication(block: suspend (Application) -> Unit) {
    testApplication {
        application { block(this) }
        startApplication()
    }
}
