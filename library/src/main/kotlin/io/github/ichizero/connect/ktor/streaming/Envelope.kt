package io.github.ichizero.connect.ktor.streaming

/**
 * Bit flags used in the first byte of a Connect protocol envelope.
 *
 * See [Connect Protocol Reference](https://connectrpc.com/docs/protocol#streaming-request)
 * and connect-go `flagEnvelopeCompressed` / `connectFlagEnvelopeEndStream`.
 */
internal object EnvelopeFlags {
    /** Payload is compressed using the codec advertised by `Connect-Content-Encoding`. */
    const val COMPRESSED: Byte = 0b0000_0001

    /** Final frame of a stream. Payload is always JSON ([EndStreamPayload]) regardless of message codec. */
    const val END_STREAM: Byte = 0b0000_0010
}

/** Size of the envelope prefix: 1 byte flags + 4 byte big-endian length. */
internal const val ENVELOPE_HEADER_SIZE: Int = 5

/** Default upper bound on a single envelope payload length. Mirrors gRPC's typical 4 MiB default. */
const val DEFAULT_MAX_MESSAGE_SIZE: Int = 4 * 1024 * 1024

/**
 * A single Connect protocol envelope frame.
 *
 * Wire format:
 * ```
 * +--------+----------------+---------------------+
 * | flags  | length (BE u32)| payload (N bytes)   |
 * | 1 byte | 4 byte         | length bytes        |
 * +--------+----------------+---------------------+
 * ```
 */
internal data class EnvelopeFrame(
    val flags: Byte,
    val payload: ByteArray,
) {
    val isEndStream: Boolean
        get() = (flags.toInt() and EnvelopeFlags.END_STREAM.toInt()) != 0

    val isCompressed: Boolean
        get() = (flags.toInt() and EnvelopeFlags.COMPRESSED.toInt()) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnvelopeFrame) return false
        return flags == other.flags && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * flags.toInt() + payload.contentHashCode()
}
