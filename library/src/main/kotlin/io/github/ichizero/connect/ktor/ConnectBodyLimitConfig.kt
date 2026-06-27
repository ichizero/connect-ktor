package io.github.ichizero.connect.ktor

/**
 * A config for the [ConnectBodyLimit] plugin.
 */
internal class ConnectBodyLimitConfig {
    var maxBytes: Long = Long.MAX_VALUE
}
