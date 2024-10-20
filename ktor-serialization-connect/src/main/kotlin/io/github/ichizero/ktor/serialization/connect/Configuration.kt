package io.github.ichizero.ktor.serialization.connect

import io.ktor.http.ContentType
import io.ktor.serialization.Configuration

internal val contentTypeConnectJson = ContentType("application", "connect+json")

/**
 * Registers the `application/connect+json` content type
 * to the [ContentNegotiation] plugin using [ConnectJsonConverter].
 */
fun Configuration.connectJson() {
    register(ContentType.Application.Json, ConnectJsonConverter()) // For unary RPCs
    register(contentTypeConnectJson, ConnectJsonConverter()) // For bidirectional and server streaming RPCs
}
