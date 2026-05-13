package io.github.ichizero.connect.ktor.conformance

import com.connectrpc.conformance.v1.ConformanceServiceHandlerInterface
import com.connectrpc.conformance.v1.IdempotentUnaryRequest
import com.connectrpc.conformance.v1.UnaryRequest
import com.connectrpc.conformance.v1.UnimplementedRequest
import com.connectrpc.conformance.v1.conformanceService
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import io.github.ichizero.connect.ktor.ConnectGetJsonSerializer
import io.github.ichizero.connect.ktor.ConnectGetStrategies
import io.github.ichizero.connect.ktor.installConnectGetCodecs
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
import kotlin.reflect.KClass

internal val conformanceTypeRegistry: TypeRegistry = TypeRegistry.newBuilder()
    .add(UnaryRequest.getDescriptor())
    .add(IdempotentUnaryRequest.getDescriptor())
    .add(UnimplementedRequest.getDescriptor())
    .build()

/**
 * JSON serializer that uses [JsonFormat.printer] with the full [TypeRegistry], so that
 * `google.protobuf.Any` fields (e.g. `ConformancePayload.requests`) are serialised correctly.
 *
 * [com.connectrpc.extensions.GoogleJavaJSONStrategy] does NOT propagate the TypeRegistry to
 * its printer, which is why a custom serializer is required for the Connect GET path.
 */
private class ConformanceGetJsonSerializer(registry: TypeRegistry) : ConnectGetJsonSerializer {
    private val printer = JsonFormat.printer().usingTypeRegistry(registry)

    override fun <T : Any> serialize(value: T, clazz: KClass<T>): ByteArray =
        printer.print(value as Message).toByteArray(Charsets.UTF_8)
}

internal fun Application.conformanceModule(handler: ConformanceServiceHandlerInterface) {
    // Register custom JSON strategy with the full type registry for Connect GET (query-param) path.
    // Without this, google.protobuf.Any fields in responses (e.g. ConformancePayload.requests)
    // cannot be serialised to JSON because GoogleJavaJSONStrategy omits the registry from its
    // JsonFormat.printer() call.
    installConnectGetCodecs(
        ConnectGetStrategies(
            proto = GoogleJavaProtobufStrategy(),
            json = com.connectrpc.extensions.GoogleJavaJSONStrategy(conformanceTypeRegistry),
            jsonSerializer = ConformanceGetJsonSerializer(conformanceTypeRegistry),
        ),
    )
    install(Resources)
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
