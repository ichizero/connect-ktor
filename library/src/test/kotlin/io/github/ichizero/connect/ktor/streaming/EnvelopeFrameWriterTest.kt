package io.github.ichizero.connect.ktor.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray

class EnvelopeFrameWriterTest : FunSpec({
    test("writes 5 byte prefix followed by payload (no compression, no end-stream)") {
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val payload = byteArrayOf(0x01, 0x02, 0x03)
            channel.writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = payload))
            channel.flushAndClose()

            val bytes = channel.readRemaining().readByteArray()
            // flags=0x00, length=BE(3)=00 00 00 03, payload=01 02 03
            bytes shouldBe byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03)
        }
    }

    test("encodes length in big-endian 32 bit") {
        runTest {
            val channel = ByteChannel(autoFlush = true)
            // length 258 = 0x00000102
            val payload = ByteArray(258) { it.toByte() }
            channel.writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = payload))
            channel.flushAndClose()

            val bytes = channel.readRemaining().readByteArray()
            bytes[0] shouldBe 0x00.toByte()
            bytes[1] shouldBe 0x00.toByte()
            bytes[2] shouldBe 0x00.toByte()
            bytes[3] shouldBe 0x01.toByte()
            bytes[4] shouldBe 0x02.toByte()
            bytes.size shouldBe ENVELOPE_HEADER_SIZE + 258
        }
    }

    test("writes end-stream flag 0x02") {
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val payload = "{}".toByteArray()
            channel.writeEnvelopeFrame(
                EnvelopeFrame(flags = EnvelopeFlags.END_STREAM, payload = payload),
            )
            channel.flushAndClose()

            val bytes = channel.readRemaining().readByteArray()
            bytes[0] shouldBe 0x02.toByte()
            bytes[1] shouldBe 0x00.toByte()
            bytes[2] shouldBe 0x00.toByte()
            bytes[3] shouldBe 0x00.toByte()
            bytes[4] shouldBe 0x02.toByte()
            bytes.sliceArray(5 until bytes.size) shouldBe payload
        }
    }

    test("writes empty payload as length 0 frame") {
        runTest {
            val channel = ByteChannel(autoFlush = true)
            channel.writeEnvelopeFrame(EnvelopeFrame(flags = 0, payload = ByteArray(0)))
            channel.flushAndClose()

            val bytes = channel.readRemaining().readByteArray()
            bytes shouldBe byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)
        }
    }
})
