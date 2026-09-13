package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.google.protobuf.util.JsonFormat
import com.stricteliza.v1.SayRequest
import com.stricteliza.v1.SayResponse
import com.stricteliza.v1.sayRequest
import com.stricteliza.v1.sayResponse
import io.github.ichizero.ktor.protovalidate.ProtoRequestValidation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

class HandleBidiStreamTest : FunSpec({
    for (json in listOf(false, true)) {
        test("bidi: echoes multiple messages with json=$json") {
            val response = postBidi(bidiFrame("one", json) + bidiFrame("two", json), json = json)
            response.status shouldBe HttpStatusCode.OK
            val frames = decodeTestFrames(response.bodyAsBytes())
            frames.size shouldBe 3
            frames.dropLast(1).map {
                if (json) {
                    SayResponse.newBuilder().also { builder ->
                        JsonFormat.parser().merge(String(it.payload), builder)
                    }.build().sentence
                } else {
                    SayResponse.parseFrom(it.payload).sentence
                }
            } shouldBe listOf("one", "two")
            String(frames.last().payload) shouldBe "{}"
        }

        test("bidi: validates each message, preserving prior responses with json=$json") {
            val response = postBidi(bidiFrame("valid", json) + bidiFrame("a".repeat(101), json), json = json)
            val frames = decodeTestFrames(response.bodyAsBytes())
            frames.size shouldBe 2
            String(frames.last().payload) shouldContain "\"code\":\"invalid_argument\""
            String(frames.last().payload) shouldContain "buf.validate.Violation"
        }
    }

    test("bidi: empty request and response streams succeed") {
        val response = postBidi(byteArrayOf())
        val frames = decodeTestFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames.single().payload) shouldBe "{}"
    }

    test("bidi: half duplex drains all messages before creating responses") {
        val response = postBidi(bidiFrame("one") + bidiFrame("two")) { requests, _ ->
            val received = requests.toList()
            received.map { it.sentence } shouldBe listOf("one", "two")
            flow { received.reversed().forEach { emit(sayResponse { sentence = it.sentence }) } }
        }
        decodeTestFrames(response.bodyAsBytes()).dropLast(1)
            .map { SayResponse.parseFrom(it.payload).sentence } shouldBe listOf("two", "one")
    }

    test("bidi: reading first message for headers resumes without replay") {
        val response = postBidi(bidiFrame("one") + bidiFrame("two")) { requests, call ->
            call.response.headers.append("x-first", requests.firstOrNull()!!.sentence)
            requests.map { sayResponse { sentence = it.sentence } }
        }
        response.headers["x-first"] shouldBe "one"
        decodeTestFrames(response.bodyAsBytes()).dropLast(1)
            .map { SayResponse.parseFrom(it.payload).sentence } shouldBe listOf("two")
    }

    test("bidi: end-stream remains terminal across sequential collections") {
        val end = byteArrayOf(2, 0, 0, 0, 2) + "{}".toByteArray()
        val response = postBidi(bidiFrame("one") + end + bidiFrame("ignored")) { requests, _ ->
            requests.toList().map { it.sentence } shouldBe listOf("one")
            requests.toList() shouldBe emptyList()
            emptyFlow()
        }
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldBe "{}"
    }

    test("bidi: trailers and errors survive partial responses") {
        val response = postBidi(bidiFrame("one")) { requests, call ->
            call.response.headers.append("x-header", "header")
            call.connectResponseTrailers().append("x-trailer", "first")
            flow {
                requests.collect { emit(sayResponse { sentence = it.sentence }) }
                throw ConnectException(
                    code = Code.PERMISSION_DENIED,
                    message = "denied",
                    metadata = mapOf("x-trailer" to listOf("second")),
                )
            }
        }
        response.headers["x-header"] shouldBe "header"
        response.headers["x-trailer"] shouldBe null
        val frames = decodeTestFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        String(frames.last().payload) shouldContain "\"code\":\"permission_denied\""
        String(frames.last().payload) shouldContain "\"x-trailer\":[\"first\",\"second\"]"
    }

    test("bidi: handler failure before returning a flow is an end-stream error") {
        val response = postBidi(byteArrayOf()) { _, _ ->
            throw ConnectException(code = Code.UNAUTHENTICATED, message = "missing token")
        }
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldContain "unauthenticated"
    }

    test("bidi: malformed second frame preserves the first response") {
        val response = postBidi(bidiFrame("one") + byteArrayOf(0, 0, 0))
        val frames = decodeTestFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        String(frames.last().payload) shouldContain "invalid_argument"
    }

    test("bidi: malformed protobuf is invalid argument") {
        val response = postBidi(encodeTestFrame(byteArrayOf(0xFF.toByte())))
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldContain "invalid_argument"
    }

    test("bidi: compressed frames without negotiation are protocol errors") {
        val body = bidiFrame("one").apply { this[0] = 1 }
        val response = postBidi(body)
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldContain "\"code\":\"internal\""
    }

    test("bidi: oversized frame fails before reading its payload") {
        val response = postBidi(byteArrayOf(0, 0, 0, 1, 0), maxMessageSize = 64)
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldContain "resource_exhausted"
    }

    test("bidi: timeout applies during response collection") {
        val response = postBidi(bidiFrame("one"), timeoutMs = 100) { requests, _ ->
            flow {
                requests.collect { emit(sayResponse { sentence = it.sentence }) }
                delay(10_000)
            }
        }
        val frames = decodeTestFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        String(frames.last().payload) shouldContain "deadline_exceeded"
    }

    test("bidi: timeout applies before a response flow is returned") {
        val response = postBidi(byteArrayOf(), timeoutMs = 50) { _, _ ->
            delay(10_000)
            emptyFlow()
        }
        String(decodeTestFrames(response.bodyAsBytes()).single().payload) shouldContain "deadline_exceeded"
    }
})

@Resource("/bidi")
private class BidiResource

private suspend fun postBidi(
    body: ByteArray,
    json: Boolean = false,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
    timeoutMs: Long? = null,
    handler: suspend (Flow<SayRequest>, ApplicationCall) -> Flow<SayResponse> = { requests, _ ->
        requests.map { sayResponse { sentence = it.sentence } }
    },
): HttpResponse {
    lateinit var response: HttpResponse
    testApplication {
        application {
            install(Resources)
            routing {
                install(ProtoRequestValidation)
                post<BidiResource>(handleBidiStream(handler, maxMessageSize))
            }
        }
        response = client.post("/bidi") {
            header(HttpHeaders.ContentType, if (json) "application/connect+json" else "application/connect+proto")
            if (timeoutMs != null) header("Connect-Timeout-Ms", timeoutMs)
            setBody(body)
        }
    }
    return response
}

private fun bidiFrame(sentence: String, json: Boolean = false): ByteArray {
    val request = sayRequest { this.sentence = sentence }
    return encodeTestFrame(
        if (json) JsonFormat.printer().print(request).toByteArray() else request.toByteArray(),
    )
}
