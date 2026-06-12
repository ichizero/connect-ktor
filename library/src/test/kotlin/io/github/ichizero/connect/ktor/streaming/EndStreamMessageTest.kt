package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import okio.ByteString.Companion.toByteString

class EndStreamMessageTest : FunSpec({
    test("encodes successful end-stream with metadata only") {
        val bytes = buildEndStreamPayload(
            trailers = mapOf("trailer-key" to listOf("value-1", "value-2")),
            error = null,
        )

        String(bytes) shouldEqualJson """
            { "metadata": { "trailer-key": ["value-1", "value-2"] } }
        """
    }

    test("encodes successful end-stream with no metadata as empty object") {
        // connect-go MarshalEndStream emits {} when both error and trailer are absent
        val bytes = buildEndStreamPayload(trailers = emptyMap(), error = null)
        String(bytes) shouldEqualJson "{}"
    }

    test("encodes error end-stream with code and message") {
        val bytes = buildEndStreamPayload(
            trailers = emptyMap(),
            error = ConnectException(code = Code.INVALID_ARGUMENT, message = "bad input"),
        )

        String(bytes) shouldEqualJson """
            { "error": { "code": "invalid_argument", "message": "bad input" } }
        """
    }

    test("encodes error end-stream with details") {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val bytes = buildEndStreamPayload(
            trailers = emptyMap(),
            error = ConnectException(
                code = Code.FAILED_PRECONDITION,
                message = "violation",
            ).withErrorDetails(
                GoogleJavaJSONStrategy().errorDetailParser(),
                listOf(
                    ConnectErrorDetail(
                        type = "example.v1.Detail",
                        payload = payload.toByteString(),
                    ),
                ),
            ),
        )

        String(bytes) shouldEqualJson """
            {
              "error": {
                "code": "failed_precondition",
                "message": "violation",
                "details": [
                  { "type": "example.v1.Detail", "value": "AQID" }
                ]
              }
            }
        """
    }

    test("encodes both metadata and error") {
        val bytes = buildEndStreamPayload(
            trailers = mapOf("retry-after" to listOf("30")),
            error = ConnectException(code = Code.UNAVAILABLE, message = "try later"),
        )

        String(bytes) shouldEqualJson """
            {
              "error": { "code": "unavailable", "message": "try later" },
              "metadata": { "retry-after": ["30"] }
            }
        """
    }

    test("normalizes trailer keys to lowercase per Connect spec") {
        // Connect spec: metadata field keys follow HTTP/2 convention (lowercase). Inputs that
        // mix case (e.g. user code passing "X-Checksum") must be normalized on the wire.
        val bytes = buildEndStreamPayload(
            trailers = mapOf(
                "X-Checksum" to listOf("deadbeef"),
                "Retry-After" to listOf("30"),
            ),
            error = null,
        )

        String(bytes) shouldEqualJson """
            { "metadata": { "x-checksum": ["deadbeef"], "retry-after": ["30"] } }
        """
    }

    test("merges values when two input keys normalize to the same lowercase form") {
        // If callers happen to supply both "X-Foo" and "x-foo", the wire output must
        // preserve all values under a single normalized key (last-wins would silently drop data).
        val bytes = buildEndStreamPayload(
            trailers = mapOf(
                "X-Foo" to listOf("a"),
                "x-foo" to listOf("b"),
            ),
            error = null,
        )

        String(bytes) shouldEqualJson """
            { "metadata": { "x-foo": ["a", "b"] } }
        """
    }

    test("omits message field when null") {
        val bytes = buildEndStreamPayload(
            trailers = emptyMap(),
            error = ConnectException(code = Code.UNKNOWN, message = null),
        )

        String(bytes) shouldEqualJson """{ "error": { "code": "unknown" } }"""
    }
})
