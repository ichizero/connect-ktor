package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.stricteliza.v1.CountdownRequest
import com.stricteliza.v1.CountdownResponse
import com.stricteliza.v1.countdownRequest
import com.stricteliza.v1.countdownResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

class HandleServerStreamTest : FunSpec({
    test("server streaming: 3 messages produce 3 data frames plus an end frame") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 3 }),
        ) { request, _ ->
            flow {
                for (value in request.from downTo 1) {
                    emit(countdownResponse { this.value = value })
                }
            }
        }

        response.status shouldBe HttpStatusCode.OK
        response.parseContentType()?.contentSubtype shouldBe "connect+proto"

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 4
        frames.dropLast(1).map { it.isEndStream } shouldBe listOf(false, false, false)
        frames.dropLast(1).map { CountdownResponse.parseFrom(it.payload).value } shouldBe listOf(3, 2, 1)

        frames.last().isEndStream shouldBe true
        String(frames.last().payload) shouldBe "{}"
    }

    test("server streaming: empty flow produces an end frame only") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 0 }),
        ) { _, _ -> emptyFlow() }

        response.status shouldBe HttpStatusCode.OK
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        String(frames[0].payload) shouldBe "{}"
    }

    test("server streaming: response headers are HTTP headers, trailers ride the end frame") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }),
        ) { _, call ->
            call.response.headers.append("x-custom-header", "foo")
            call.connectResponseTrailers().append("x-custom-trailer", "bing")
            flow { emit(countdownResponse { value = 1 }) }
        }

        response.headers["x-custom-header"] shouldBe "foo"
        response.headers["x-custom-trailer"] shouldBe null

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        String(frames[1].payload) shouldBe """{"metadata":{"x-custom-trailer":["bing"]}}"""
    }

    test("server streaming: trailers keep every value of a repeated key") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 0 }),
        ) { _, call ->
            call.connectResponseTrailers().appendAll("x-custom-trailer", listOf("bing", "quux"))
            emptyFlow()
        }

        val frames = decodeFrames(response.bodyAsBytes())
        String(frames[0].payload) shouldBe """{"metadata":{"x-custom-trailer":["bing","quux"]}}"""
    }

    test("server streaming: failure after some messages keeps the emitted data frames") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 2 }),
        ) { _, call ->
            call.connectResponseTrailers().append("x-custom-trailer", "bing")
            flow {
                emit(countdownResponse { value = 2 })
                emit(countdownResponse { value = 1 })
                throw ConnectException(code = Code.INTERNAL_ERROR, message = "server stream failed")
            }
        }

        response.status shouldBe HttpStatusCode.OK
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 3
        frames.dropLast(1).map { CountdownResponse.parseFrom(it.payload).value } shouldBe listOf(2, 1)
        String(frames.last().payload) shouldBe
            """{"error":{"code":"internal","message":"server stream failed"},""" +
            """"metadata":{"x-custom-trailer":["bing"]}}"""
    }

    test("server streaming: non-Connect exception is mapped to UNKNOWN") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }),
        ) { _, _ ->
            flow<CountdownResponse> { throw IllegalStateException("boom") }
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe """{"error":{"code":"unknown","message":"boom"}}"""
    }

    test("server streaming: ConnectException.metadata is merged into end-stream trailers") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }),
        ) { _, call ->
            call.connectResponseTrailers().append("x-custom-trailer", "bing")
            flow<CountdownResponse> {
                throw ConnectException(
                    code = Code.RESOURCE_EXHAUSTED,
                    message = "slow down",
                    metadata = mapOf("retry-after" to listOf("30")),
                )
            }
        }

        val frames = decodeFrames(response.bodyAsBytes())
        String(frames[0].payload) shouldBe
            """{"error":{"code":"resource_exhausted","message":"slow down"},""" +
            """"metadata":{"x-custom-trailer":["bing"],"retry-after":["30"]}}"""
    }

    test("server streaming: exception thrown before the flow is returned yields an end-frame-only body") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }),
        ) { _, _ ->
            throw ConnectException(code = Code.PERMISSION_DENIED, message = "no")
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe """{"error":{"code":"permission_denied","message":"no"}}"""
    }

    test("server streaming: JSON content-type round-trips data and end frames") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Json,
            body = encodeJsonFrame(countdownRequest { from = 2 }),
        ) { request, _ ->
            flow {
                for (value in request.from downTo 1) {
                    emit(countdownResponse { this.value = value })
                }
            }
        }

        response.parseContentType()?.contentSubtype shouldBe "connect+json"
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 3
        val decoded = CountdownResponse.newBuilder().also {
            com.google.protobuf.util.JsonFormat.parser().merge(String(frames[0].payload), it)
        }.build()
        decoded.value shouldBe 2
        String(frames.last().payload) shouldBe "{}"
    }

    test("server streaming: a request without any message is rejected with UNIMPLEMENTED") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = byteArrayOf(),
        ) { _, _ -> emptyFlow() }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe
            """{"error":{"code":"unimplemented","message":"unary request has zero messages"}}"""
    }

    test("server streaming: a request with multiple messages is rejected with UNIMPLEMENTED") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }) + encodeFrame(countdownRequest { from = 2 }),
        ) { _, _ -> emptyFlow() }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe
            """{"error":{"code":"unimplemented","message":"unary request has multiple messages"}}"""
    }

    test("server streaming: a compressed request frame is a protocol error") {
        val payload = countdownRequest { from = 1 }.toByteArray()
        val compressedFrame = byteArrayOf(1, 0, 0, 0, payload.size.toByte()) + payload
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = compressedFrame,
        ) { _, _ -> emptyFlow() }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe """{"error":{"code":"internal",""" +
            """"message":"protocol error: sent compressed message without connect-content-encoding"}}"""
    }

    test("server streaming: malformed request frame yields INVALID_ARGUMENT") {
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = byteArrayOf(0x00, 0x00, 0x00, 0x00, garbage.size.toByte()) + garbage,
        ) { _, _ -> emptyFlow() }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload).contains(""""code":"invalid_argument"""") shouldBe true
    }

    test("server streaming: messages larger than maxMessageSize fail with RESOURCE_EXHAUSTED") {
        val big = ByteArray(1024) { 'a'.code.toByte() }
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = byteArrayOf(0x00, 0x00, 0x00, 0x00, big.size.toByte()) + big,
            maxMessageSize = 64,
        ) { _, _ -> emptyFlow() }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload).contains(""""code":"resource_exhausted"""") shouldBe true
    }

    test("server streaming: unsupported content-type produces end frame with UNIMPLEMENTED") {
        val response = postCountdown(
            contentType = ContentType("application", "json"),
            body = byteArrayOf(),
        ) { _, _ -> emptyFlow() }

        response.parseContentType()?.contentSubtype shouldBe "connect+json"
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload).contains(""""code":"unimplemented"""") shouldBe true
    }

    test("server streaming: Connect-Timeout-Ms expiry produces DEADLINE_EXCEEDED") {
        val response = postCountdown(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrame(countdownRequest { from = 1 }),
            extraHeaders = mapOf("connect-timeout-ms" to "50"),
        ) { _, _ ->
            flow {
                emit(countdownResponse { value = 1 })
                // Block past the deadline before the stream would complete.
                kotlinx.coroutines.delay(500)
                emit(countdownResponse { value = 0 })
            }
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        frames[0].isEndStream shouldBe false
        String(frames[1].payload).contains(""""code":"deadline_exceeded"""") shouldBe true
    }
})

@Resource("/stricteliza.v1.StrictElizaService/Countdown")
private class CountdownResource

private suspend fun postCountdown(
    contentType: ContentType,
    body: ByteArray,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
    extraHeaders: Map<String, String> = emptyMap(),
    handler: suspend (CountdownRequest, ApplicationCall) -> Flow<CountdownResponse>,
): HttpResponse {
    lateinit var resp: HttpResponse
    testApplication {
        application { configureCountdown(maxMessageSize, handler) }
        resp = client.post("/stricteliza.v1.StrictElizaService/Countdown") {
            header(HttpHeaders.ContentType, contentType.toString())
            extraHeaders.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }
    return resp
}

private fun Application.configureCountdown(
    maxMessageSize: Int,
    handler: suspend (CountdownRequest, ApplicationCall) -> Flow<CountdownResponse>,
) {
    install(Resources)
    routing {
        post<CountdownResource>(
            handleServerStream<CountdownResource, CountdownRequest, CountdownResponse>(
                handlerFunc = handler,
                maxMessageSize = maxMessageSize,
            ),
        )
    }
}

private fun encodeFrame(request: CountdownRequest): ByteArray = frame(request.toByteArray())

private fun encodeJsonFrame(request: CountdownRequest): ByteArray {
    val printer = com.google.protobuf.util.JsonFormat.printer().omittingInsignificantWhitespace()
    return frame(printer.print(request).toByteArray(Charsets.UTF_8))
}

private fun frame(payload: ByteArray): ByteArray = byteArrayOf(
    0,
    ((payload.size ushr 24) and 0xFF).toByte(),
    ((payload.size ushr 16) and 0xFF).toByte(),
    ((payload.size ushr 8) and 0xFF).toByte(),
    (payload.size and 0xFF).toByte(),
) + payload

private fun decodeFrames(bytes: ByteArray): List<EnvelopeFrame> {
    val frames = mutableListOf<EnvelopeFrame>()
    var i = 0
    while (i < bytes.size) {
        if (i + ENVELOPE_HEADER_SIZE > bytes.size) error("truncated header at $i")
        val flags = bytes[i]
        val length = ((bytes[i + 1].toInt() and 0xFF) shl 24) or
            ((bytes[i + 2].toInt() and 0xFF) shl 16) or
            ((bytes[i + 3].toInt() and 0xFF) shl 8) or
            (bytes[i + 4].toInt() and 0xFF)
        if (i + ENVELOPE_HEADER_SIZE + length > bytes.size) {
            error("truncated payload at $i: declared length $length exceeds remaining bytes")
        }
        val payload = bytes.copyOfRange(i + ENVELOPE_HEADER_SIZE, i + ENVELOPE_HEADER_SIZE + length)
        frames.add(EnvelopeFrame(flags, payload))
        i += ENVELOPE_HEADER_SIZE + length
    }
    return frames
}

private fun HttpResponse.parseContentType(): ContentType? =
    headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
