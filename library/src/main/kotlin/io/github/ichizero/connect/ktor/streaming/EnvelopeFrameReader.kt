package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discardExact
import io.ktor.utils.io.readByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.EOFException

/**
 * Read Connect protocol envelope frames from this channel as a cold [Flow].
 *
 * Behavior:
 * - Emits each frame as soon as its 5 byte prefix + payload is fully read.
 * - Stops after emitting a frame with [EnvelopeFlags.END_STREAM] set, even if more bytes follow.
 * - Completes normally if the channel closes without an end-stream frame (Connect allows the
 *   request side to close without an explicit end-stream).
 * - Throws [ConnectException] with [Code.INVALID_ARGUMENT] when the prefix or payload is truncated.
 * - Throws [ConnectException] with [Code.RESOURCE_EXHAUSTED] when a frame's length exceeds
 *   [maxMessageSize]. The declared payload bytes are discarded first to keep downstream readers
 *   from stalling on un-drained channel bytes.
 *
 * Mirrors connect-go `envelopeReader.Read` (envelope.go).
 */
internal fun ByteReadChannel.readEnvelopeFrames(maxMessageSize: Int): Flow<EnvelopeFrame> = flow {
    val channel = this@readEnvelopeFrames

    while (true) {
        val header = when (val result = channel.readEnvelopeHeader()) {
            HeaderReadResult.CleanEof -> return@flow
            is HeaderReadResult.Header -> result
        }

        if (header.length < 0) {
            throw ConnectException(
                code = Code.INVALID_ARGUMENT,
                message = "protocol error: negative envelope length",
            )
        }
        if (header.length > maxMessageSize) {
            // Discard the declared payload bytes to avoid leaving the channel in a bad state.
            runCatching { channel.discardExact(header.length.toLong()) }
            throw ConnectException(
                code = Code.RESOURCE_EXHAUSTED,
                message = "message size ${header.length} exceeds configured max $maxMessageSize",
            )
        }

        val payload = if (header.length == 0) {
            ByteArray(0)
        } else {
            try {
                channel.readByteArray(header.length)
            } catch (e: EOFException) {
                throw ConnectException(
                    code = Code.INVALID_ARGUMENT,
                    message = "protocol error: incomplete envelope payload (expected ${header.length} bytes)",
                    exception = e,
                )
            }
        }

        val frame = EnvelopeFrame(header.flags, payload)
        emit(frame)
        if (frame.isEndStream) break
    }
}

/**
 * Outcome of attempting to read a 5 byte envelope prefix.
 *
 * The distinction between "no bytes were available" ([CleanEof]) and "some bytes arrived but
 * not a full prefix" (signaled by throwing [ConnectException] from [readEnvelopeHeader]) is the
 * difference between a peer that closed cleanly and a peer that produced a truncated frame.
 */
private sealed interface HeaderReadResult {
    data object CleanEof : HeaderReadResult
    data class Header(val flags: Byte, val length: Int) : HeaderReadResult
}

private suspend fun ByteReadChannel.readEnvelopeHeader(): HeaderReadResult {
    // awaitContent(1) returns false only when the channel is closed with no further bytes —
    // a clean end of stream. Any byte received here commits us to reading a full 5 byte prefix.
    if (!awaitContent(min = 1)) return HeaderReadResult.CleanEof

    val bytes = try {
        readByteArray(ENVELOPE_HEADER_SIZE)
    } catch (e: EOFException) {
        throw ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "protocol error: incomplete envelope header",
            exception = e,
        )
    }
    return HeaderReadResult.Header(flags = bytes[0], length = parseBigEndianLength(bytes))
}

private fun parseBigEndianLength(header: ByteArray): Int =
    ((header[1].toInt() and 0xFF) shl 24) or
        ((header[2].toInt() and 0xFF) shl 16) or
        ((header[3].toInt() and 0xFF) shl 8) or
        (header[4].toInt() and 0xFF)
