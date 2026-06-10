package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.conformance.v1.ClientStreamRequest
import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.IdempotentUnaryRequest
import com.connectrpc.conformance.v1.UnaryRequest
import com.connectrpc.conformance.v1.UnimplementedRequest
import com.connectrpc.conformance.v1.conformanceService
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.google.protobuf.TypeRegistry
import io.github.ichizero.connect.ktor.streaming.ConnectStreamingStrategies
import io.github.ichizero.connect.ktor.streaming.installConnectStreamingCodecs
import io.github.ichizero.ktor.serialization.connect.connectProto
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentTypeWithQuality
import io.ktor.server.request.contentType
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal val conformanceTypeRegistry: TypeRegistry = TypeRegistry.newBuilder()
    .add(UnaryRequest.getDescriptor())
    .add(IdempotentUnaryRequest.getDescriptor())
    .add(UnimplementedRequest.getDescriptor())
    .add(ClientStreamRequest.getDescriptor())
    .build()

internal fun Application.conformanceModule(handler: ConformanceServiceHandlerInterface) {
    install(Resources)
    // Streaming JSON responses contain `google.protobuf.Any` (RequestInfo.requests); the JSON
    // strategy needs the same TypeRegistry as the unary path or encoding fails with
    // "Cannot find type for url: ..." for ClientStreamRequest et al.
    installConnectStreamingCodecs(
        ConnectStreamingStrategies(
            proto = GoogleJavaProtobufStrategy(),
            json = ConformanceJsonStrategy(conformanceTypeRegistry),
        ),
    )
    install(ContentNegotiation) {
        val jsonConverter = ConformanceJsonConverter(conformanceTypeRegistry)
        register(ContentType.Application.Json, jsonConverter)
        register(ContentType("application", "connect+json"), jsonConverter)
        connectProto()

        // The Connect protocol does not require clients to send an Accept
        // header — the response Content-Type mirrors the request. Surface
        // the request Content-Type to ContentNegotiation so that the right
        // converter is selected for the response body.
        accept { call, items ->
            if (items.isNotEmpty()) {
                items
            } else {
                val ct = call.request.contentType()
                if (ct == ContentType.Any) items else listOf(ContentTypeWithQuality(ct))
            }
        }
    }
    routing {
        conformanceService(handler)
    }
}

internal fun awaitPort(engine: ApplicationEngine): Int = runBlocking {
    // resolvedConnectors() suspends until the engine has bound its listeners,
    // so a single call is sufficient. Wrap in a timeout to surface a clear
    // error if the engine fails to start (e.g. port permission errors).
    val connectors = withTimeout(10_000) { engine.resolvedConnectors() }
    connectors.firstOrNull()?.port ?: error("Ktor server reported no connectors")
}
