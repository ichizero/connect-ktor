package io.github.ichizero.connect.ktor.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EnvelopeTest : FunSpec({
    test("EnvelopeFlags constants match Connect protocol spec") {
        // connect-go: flagEnvelopeCompressed = 0b00000001
        EnvelopeFlags.COMPRESSED shouldBe 0b0000_0001.toByte()
        // connect-go: connectFlagEnvelopeEndStream = 0b00000010
        EnvelopeFlags.END_STREAM shouldBe 0b0000_0010.toByte()
    }

    test("ENVELOPE_HEADER_SIZE is 5 bytes (1 byte flags + 4 byte length)") {
        ENVELOPE_HEADER_SIZE shouldBe 5
    }

    test("isEndStream is true when END_STREAM bit is set") {
        EnvelopeFrame(flags = 0b0000_0010, payload = ByteArray(0)).isEndStream shouldBe true
        EnvelopeFrame(flags = 0b0000_0011, payload = ByteArray(0)).isEndStream shouldBe true
        EnvelopeFrame(flags = 0b0000_0000, payload = ByteArray(0)).isEndStream shouldBe false
        EnvelopeFrame(flags = 0b0000_0001, payload = ByteArray(0)).isEndStream shouldBe false
    }

    test("isCompressed is true when COMPRESSED bit is set") {
        EnvelopeFrame(flags = 0b0000_0001, payload = ByteArray(0)).isCompressed shouldBe true
        EnvelopeFrame(flags = 0b0000_0011, payload = ByteArray(0)).isCompressed shouldBe true
        EnvelopeFrame(flags = 0b0000_0000, payload = ByteArray(0)).isCompressed shouldBe false
        EnvelopeFrame(flags = 0b0000_0010, payload = ByteArray(0)).isCompressed shouldBe false
    }
})
