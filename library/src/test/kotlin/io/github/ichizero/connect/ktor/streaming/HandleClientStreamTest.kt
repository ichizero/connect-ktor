package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import com.stricteliza.v1.UploadRequest
import com.stricteliza.v1.UploadResponse
import com.stricteliza.v1.uploadRequest
import com.stricteliza.v1.uploadResponse
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
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList

class HandleClientStreamTest : FunSpec({
    test("client streaming: 3 messages aggregated, single data frame + end frame returned") {
        val reqs = listOf(
            uploadRequest { chunk = "hello" },
            uploadRequest { chunk = " " },
            uploadRequest { chunk = "world" },
        )

        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(reqs),
        ) { collected ->
            val merged = collected.fold(0L to 0L) { (total, count), r ->
                (total + r.chunk.length) to (count + 1)
            }
            ResponseMessage.Success(
                message = uploadResponse {
                    totalChars = merged.first
                    chunkCount = merged.second
                },
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }

        response.status shouldBe HttpStatusCode.OK
        response.parseContentType()?.contentSubtype shouldBe "connect+proto"

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        frames[0].isEndStream shouldBe false
        frames[1].isEndStream shouldBe true

        val decoded = UploadResponse.parseFrom(frames[0].payload)
        decoded.totalChars shouldBe 11L
        decoded.chunkCount shouldBe 3L

        // end-stream frame is JSON {} when no trailers and no error
        String(frames[1].payload) shouldBe "{}"
    }

    test("client streaming: handler failure produces end-frame-only response with error") {
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
        ) {
            ResponseMessage.Failure(
                cause = ConnectException(code = Code.INVALID_ARGUMENT, message = "no good"),
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        val json = String(frames[0].payload)
        json shouldBe """{"error":{"code":"invalid_argument","message":"no good"}}"""
    }

    test("client streaming: handler exception is mapped to UNKNOWN end-frame") {
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
        ) {
            throw IllegalStateException("boom")
        }

        response.status shouldBe HttpStatusCode.OK
        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        val json = String(frames[0].payload)
        json shouldBe """{"error":{"code":"unknown","message":"boom"}}"""
    }

    test("client streaming: ConnectException from handler preserves its code") {
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
        ) {
            throw ConnectException(code = Code.PERMISSION_DENIED, message = "no")
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe """{"error":{"code":"permission_denied","message":"no"}}"""
    }

    test("client streaming: ConnectException.metadata flows into end-stream trailers") {
        // Mirrors connect-go MarshalEndStream: error metadata is merged into the end-frame
        // `metadata` field (trailers), not into HTTP response headers.
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
        ) {
            throw ConnectException(
                code = Code.RESOURCE_EXHAUSTED,
                message = "slow down",
                metadata = mapOf("retry-after" to listOf("30")),
            )
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload) shouldBe
            """{"error":{"code":"resource_exhausted","message":"slow down"},""" +
            """"metadata":{"retry-after":["30"]}}"""
    }

    test("client streaming: unsupported content-type produces end-frame with UNIMPLEMENTED") {
        val response = postUpload(
            contentType = ContentType("application", "json"),
            body = byteArrayOf(),
        ) {
            ResponseMessage.Success(uploadResponse {}, emptyMap(), emptyMap())
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        val json = String(frames[0].payload)
        json.contains(""""code":"unimplemented"""") shouldBe true
    }

    test("client streaming: messages larger than maxMessageSize fail with RESOURCE_EXHAUSTED") {
        val big = ByteArray(1024) { 'a'.code.toByte() }
        // Pre-build envelope with a declared length larger than the configured max (64)
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00, big.size.toByte()) + big
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = body,
            maxMessageSize = 64,
        ) { reqs ->
            reqs.toList() // never reached; reader throws
            ResponseMessage.Success(uploadResponse {}, emptyMap(), emptyMap())
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        String(frames[0].payload).contains(""""code":"resource_exhausted"""") shouldBe true
    }

    test("client streaming: JSON content-type round-trips both data and end frames") {
        val reqs = listOf(uploadRequest { chunk = "abc" }, uploadRequest { chunk = "de" })
        val response = postUpload(
            contentType = ConnectStreamingContentType.Json,
            body = encodeJsonFrames(reqs),
        ) { collected ->
            val total = collected.fold(0L) { acc, r -> acc + r.chunk.length }
            ResponseMessage.Success(
                uploadResponse {
                    totalChars = total
                    chunkCount = 2
                },
                emptyMap(),
                emptyMap(),
            )
        }

        response.status shouldBe HttpStatusCode.OK
        response.parseContentType()?.contentSubtype shouldBe "connect+json"

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        frames[0].isEndStream shouldBe false
        // Data frame is JSON when codec is JSON. Parse it back and assert structurally rather than
        // pinning the exact wire form (proto JSON renders int64 as string by default).
        val decoded = UploadResponse.newBuilder().also {
            com.google.protobuf.util.JsonFormat.parser().merge(String(frames[0].payload), it)
        }.build()
        decoded.totalChars shouldBe 5L
        decoded.chunkCount shouldBe 2L
        // end frame is always JSON {}
        String(frames[1].payload) shouldBe "{}"
    }

    test("client streaming: successful response carries trailers in end-stream metadata") {
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
        ) {
            ResponseMessage.Success(
                message = uploadResponse {
                    totalChars = 1
                    chunkCount = 1
                },
                headers = emptyMap(),
                trailers = mapOf("x-checksum" to listOf("deadbeef")),
            )
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 2
        frames[1].isEndStream shouldBe true
        String(frames[1].payload) shouldBe """{"metadata":{"x-checksum":["deadbeef"]}}"""
    }

    test("client streaming: Connect-Timeout-Ms expiry produces DEADLINE_EXCEEDED") {
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = encodeFrames(listOf(uploadRequest { chunk = "x" })),
            extraHeaders = mapOf("connect-timeout-ms" to "50"),
        ) {
            // Block longer than the deadline.
            kotlinx.coroutines.delay(500)
            ResponseMessage.Success(uploadResponse {}, emptyMap(), emptyMap())
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        String(frames[0].payload).contains(""""code":"deadline_exceeded"""") shouldBe true
    }

    test("client streaming: malformed protobuf request frame yields INVALID_ARGUMENT") {
        // Single frame containing bytes that don't parse as UploadRequest.
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00, garbage.size.toByte()) + garbage
        val response = postUpload(
            contentType = ConnectStreamingContentType.Proto,
            body = body,
        ) { reqs ->
            reqs.toList()
            ResponseMessage.Success(uploadResponse {}, emptyMap(), emptyMap())
        }

        val frames = decodeFrames(response.bodyAsBytes())
        frames.size shouldBe 1
        frames[0].isEndStream shouldBe true
        String(frames[0].payload).contains(""""code":"invalid_argument"""") shouldBe true
    }

    test("client streaming: unsupported request Content-Type echoes JSON for end frame") {
        val response = postUpload(
            contentType = ContentType("application", "json"),
            body = byteArrayOf(),
        ) {
            ResponseMessage.Success(uploadResponse {}, emptyMap(), emptyMap())
        }

        response.parseContentType()?.contentSubtype shouldBe "connect+json"
    }
})

@Resource("/stricteliza.v1.StrictElizaService/Upload")
private class UploadResource

private suspend fun postUpload(
    contentType: ContentType,
    body: ByteArray,
    maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
    extraHeaders: Map<String, String> = emptyMap(),
    handler: suspend (Flow<UploadRequest>) -> ResponseMessage<UploadResponse>,
): HttpResponse {
    lateinit var resp: HttpResponse
    testApplication {
        application { configureUpload(maxMessageSize, handler) }
        resp = client.post("/stricteliza.v1.StrictElizaService/Upload") {
            header(HttpHeaders.ContentType, contentType.toString())
            extraHeaders.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }
    return resp
}

private fun Application.configureUpload(
    maxMessageSize: Int,
    handler: suspend (Flow<UploadRequest>) -> ResponseMessage<UploadResponse>,
) {
    install(Resources)
    routing {
        post<UploadResource>(
            handleClientStream<UploadResource, UploadRequest, UploadResponse>(
                handlerFunc = { reqs, _ -> handler(reqs) },
                maxMessageSize = maxMessageSize,
            ),
        )
    }
}

private fun encodeJsonFrames(reqs: List<UploadRequest>): ByteArray {
    val printer = com.google.protobuf.util.JsonFormat.printer().omittingInsignificantWhitespace()
    val out = mutableListOf<Byte>()
    for (r in reqs) {
        val bytes = printer.print(r).toByteArray(Charsets.UTF_8)
        out.add(0)
        out.add(((bytes.size ushr 24) and 0xFF).toByte())
        out.add(((bytes.size ushr 16) and 0xFF).toByte())
        out.add(((bytes.size ushr 8) and 0xFF).toByte())
        out.add((bytes.size and 0xFF).toByte())
        out.addAll(bytes.toList())
    }
    // Send end-stream frame with empty JSON.
    out.add(0x02)
    out.add(0)
    out.add(0)
    out.add(0)
    out.add(2)
    out.add('{'.code.toByte())
    out.add('}'.code.toByte())
    return out.toByteArray()
}

private fun encodeFrames(reqs: List<UploadRequest>, endStream: Boolean = true): ByteArray {
    val out = mutableListOf<Byte>()
    for (r in reqs) {
        val bytes = r.toByteArray()
        out.add(0)
        out.add(((bytes.size ushr 24) and 0xFF).toByte())
        out.add(((bytes.size ushr 16) and 0xFF).toByte())
        out.add(((bytes.size ushr 8) and 0xFF).toByte())
        out.add((bytes.size and 0xFF).toByte())
        out.addAll(bytes.toList())
    }
    if (endStream) {
        out.add(0x02) // end-stream flag
        out.add(0)
        out.add(0)
        out.add(0)
        out.add(2)
        out.add('{'.code.toByte())
        out.add('}'.code.toByte())
    }
    return out.toByteArray()
}

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
        val payload = bytes.copyOfRange(i + ENVELOPE_HEADER_SIZE, i + ENVELOPE_HEADER_SIZE + length)
        frames.add(EnvelopeFrame(flags, payload))
        i += ENVELOPE_HEADER_SIZE + length
    }
    return frames
}

private fun HttpResponse.parseContentType(): ContentType? =
    headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
