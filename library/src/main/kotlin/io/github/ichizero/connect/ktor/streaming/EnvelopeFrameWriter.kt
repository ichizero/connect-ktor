package io.github.ichizero.connect.ktor.streaming

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully

/**
 * Write a single Connect envelope frame (5 byte prefix + payload) to the channel and flush.
 *
 * Frame boundaries are flushed individually so streaming consumers see each frame as soon as
 * it is produced. For client streaming the total flush count is small (1 data + 1 end-stream),
 * so this is not a hot path.
 */
internal suspend fun ByteWriteChannel.writeEnvelopeFrame(frame: EnvelopeFrame) {
    val len = frame.payload.size
    val header = ByteArray(ENVELOPE_HEADER_SIZE).apply {
        this[0] = frame.flags
        this[1] = ((len ushr 24) and 0xFF).toByte()
        this[2] = ((len ushr 16) and 0xFF).toByte()
        this[3] = ((len ushr 8) and 0xFF).toByte()
        this[4] = (len and 0xFF).toByte()
    }
    writeFully(header)
    if (len > 0) writeFully(frame.payload)
    flush()
}
