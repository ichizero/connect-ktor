package io.github.ichizero.connect.ktor.conformance

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads a size-delimited protobuf message from the given stream. The conformance
 * runner prepends each payload with a big-endian 32-bit length.
 */
fun <T : MessageLite> readDelimited(input: InputStream, parser: Parser<T>): T {
    val dis = DataInputStream(input)
    val size = dis.readInt()
    // The conformance binary only reads ServerCompatRequest from the trusted
    // runner's stdout, so this isn't a network-facing bound. It's a sanity
    // cap to surface framing drift with a clear message instead of a generic
    // OOM if the stream ever gets out of sync.
    require(size in 0..MAX_DELIMITED_SIZE) {
        "delimited message size out of range: $size (expected 0..$MAX_DELIMITED_SIZE)"
    }
    val buf = ByteArray(size)
    dis.readFully(buf)
    return parser.parseFrom(buf)
}

private const val MAX_DELIMITED_SIZE: Int = 16 * 1024 * 1024

/**
 * Writes a size-delimited protobuf message to the given stream using the same
 * framing as [readDelimited].
 */
fun writeDelimited(output: OutputStream, message: MessageLite) {
    val bytes = message.toByteArray()
    val dos = DataOutputStream(output)
    dos.writeInt(bytes.size)
    dos.write(bytes)
    dos.flush()
}
