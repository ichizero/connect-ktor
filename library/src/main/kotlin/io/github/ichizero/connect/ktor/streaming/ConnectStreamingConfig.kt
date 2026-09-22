package io.github.ichizero.connect.ktor.streaming

/** Configuration for streaming RPC request payloads, independent of whole-body limits. */
class ConnectStreamingConfig {
    /** Maximum bytes in each request message, excluding its envelope prefix. Must be positive. */
    var maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE
}
