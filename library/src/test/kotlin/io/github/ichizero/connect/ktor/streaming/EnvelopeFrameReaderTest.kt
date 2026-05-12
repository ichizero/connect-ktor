package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class EnvelopeFrameReaderTest : FunSpec({
    test("reads a single data frame") {
        runTest {
            val channel = byteChannelOf(
                // flags=0x00, length=3, payload=01 02 03
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03),
            )

            val frames = channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()

            frames.size shouldBe 1
            frames[0].flags shouldBe 0x00.toByte()
            frames[0].payload shouldBe byteArrayOf(0x01, 0x02, 0x03)
        }
    }

    test("reads multiple data frames followed by end-stream frame") {
        runTest {
            val channel = byteChannelOf(
                // 3 data frames + 1 end-stream
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0xAA.toByte()),
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x02, 0xBB.toByte(), 0xCC.toByte()),
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
                byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x02, '{'.code.toByte(), '}'.code.toByte()),
            )

            val frames = channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()

            frames.size shouldBe 4
            frames[0].payload shouldBe byteArrayOf(0xAA.toByte())
            frames[1].payload shouldBe byteArrayOf(0xBB.toByte(), 0xCC.toByte())
            frames[2].payload shouldBe ByteArray(0)
            frames[3].isEndStream shouldBe true
            frames[3].payload shouldBe "{}".toByteArray()
        }
    }

    test("stops emitting after end-stream frame even if more bytes follow") {
        runTest {
            val channel = byteChannelOf(
                byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00),
                // garbage after end-stream (should be ignored)
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            )

            val frames = channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()

            frames.size shouldBe 1
            frames[0].isEndStream shouldBe true
        }
    }

    test("completes normally when channel closes before any frame (no end-stream required)") {
        runTest {
            val channel = ByteChannel().also { it.flushAndClose() }
            val frames = channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()
            frames.size shouldBe 0
        }
    }

    test("completes normally when channel closes after data frames without end-stream") {
        // connect-go behavior: client may close without sending end-stream on the request side.
        runTest {
            val channel = byteChannelOf(
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x42),
            )
            val frames = channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()
            frames.size shouldBe 1
            frames[0].payload shouldBe byteArrayOf(0x42)
        }
    }

    test("throws INVALID_ARGUMENT when envelope header is truncated") {
        runTest {
            val channel = byteChannelOf(byteArrayOf(0x00, 0x00, 0x00))
            val ex = shouldThrow<ConnectException> {
                channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()
            }
            ex.code shouldBe Code.INVALID_ARGUMENT
        }
    }

    test("throws INVALID_ARGUMENT when payload is truncated") {
        runTest {
            // length declares 4 but only 2 bytes follow
            val channel = byteChannelOf(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x04, 0x01, 0x02))
            val ex = shouldThrow<ConnectException> {
                channel.readEnvelopeFrames(DEFAULT_MAX_MESSAGE_SIZE).toList()
            }
            ex.code shouldBe Code.INVALID_ARGUMENT
        }
    }

    test("throws RESOURCE_EXHAUSTED when length exceeds maxMessageSize") {
        runTest {
            // length = 100, max = 10
            val channel = byteChannelOf(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x64) + ByteArray(100))
            val ex = shouldThrow<ConnectException> {
                channel.readEnvelopeFrames(maxMessageSize = 10).toList()
            }
            ex.code shouldBe Code.RESOURCE_EXHAUSTED
        }
    }
})

private suspend fun byteChannelOf(vararg chunks: ByteArray): ByteReadChannel {
    val channel = ByteChannel(autoFlush = true)
    for (c in chunks) channel.writeFully(c)
    channel.flushAndClose()
    return channel
}
