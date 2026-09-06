package io.github.ichizero.connect.ktor.streaming

import java.io.ByteArrayOutputStream

// Independent wire helpers: do not use the production reader/writer to build test expectations.
internal fun encodeTestFrame(payload: ByteArray, flags: Byte = 0): ByteArray = byteArrayOf(
    flags,
    ((payload.size ushr 24) and 0xFF).toByte(),
    ((payload.size ushr 16) and 0xFF).toByte(),
    ((payload.size ushr 8) and 0xFF).toByte(),
    (payload.size and 0xFF).toByte(),
) + payload

internal fun decodeTestFrames(bytes: ByteArray): List<EnvelopeFrame> {
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

internal fun encodeTestFrames(payloads: List<ByteArray>, endStream: Boolean = true): ByteArray =
    ByteArrayOutputStream().apply {
        payloads.forEach { write(encodeTestFrame(it)) }
        if (endStream) write(encodeTestFrame("{}".toByteArray(Charsets.UTF_8), flags = 2))
    }.toByteArray()
