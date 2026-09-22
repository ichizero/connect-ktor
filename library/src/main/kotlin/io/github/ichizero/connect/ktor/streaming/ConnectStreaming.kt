package io.github.ichizero.connect.ktor.streaming

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey

/**
 * Configure streaming receive limits on a route and its children without changing generated code.
 * A child installation overrides its parent. Explicit handler limits take precedence.
 * This plugin never reads or buffers the request body and does not affect unary RPCs.
 */
val ConnectStreaming = createRouteScopedPlugin("ConnectStreaming", ::ConnectStreamingConfig) {
    val maxMessageSize = pluginConfig.maxMessageSize
    require(maxMessageSize > 0) { "maxMessageSize must be positive" }
    onCall { call ->
        call.attributes.put(streamingMessageLimitKey, maxMessageSize)
    }
}

private val streamingMessageLimitKey = AttributeKey<Int>("ConnectStreamingMessageLimit")

@PublishedApi
internal fun ApplicationCall.streamingMaxMessageSize(): Int =
    attributes.getOrNull(streamingMessageLimitKey) ?: DEFAULT_MAX_MESSAGE_SIZE
