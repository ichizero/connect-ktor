package io.github.ichizero.ktor.serialization.connect

import com.google.protobuf.TypeRegistry
import io.ktor.http.ContentType
import io.ktor.serialization.Configuration

object ConnectContentType {
    internal val Json = ContentType.Application.Json
    internal val ConnectJson = ContentType("application", "connect+json")

    internal val Proto = ContentType("application", "proto")
    internal val ConnectProto = ContentType("application", "connect+proto")
}

internal val contentTypeConnectJson = ContentType("application", "connect+json")

/**
 * Registers `application/json` and `application/connect+json` content type
 * to the [ContentNegotiation] plugin using [ConnectJsonConverter].
 */
fun Configuration.connectJson(typeRegistry: TypeRegistry = TypeRegistry.getEmptyTypeRegistry()) {
    // For unary RPCs
    register(ConnectContentType.Json, ConnectJsonConverter(typeRegistry))
    // For bidirectional and server streaming RPCs
    register(ConnectContentType.ConnectJson, ConnectJsonConverter(typeRegistry))
}

/**
 * Registers `application/proto` and `application/connect+proto` content type
 * to the [ContentNegotiation] plugin using [ConnectJsonConverter].
 */
fun Configuration.connectProto() {
    register(ConnectContentType.Proto, ConnectProtoConverter()) // For unary RPCs
    register(ConnectContentType.ConnectProto, ConnectProtoConverter()) // For bidirectional and server streaming RPCs
}
