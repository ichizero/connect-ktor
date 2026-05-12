package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import com.connectrpc.fold
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingContext
import okio.Buffer
import java.util.Base64
import kotlin.reflect.KClass

/**
 * handleGet is a utility function that decodes a Connect GET request (query-parameter encoding)
 * and bridges it to the same Connect-Ktor handler interface used by the POST path.
 *
 * Connect GET requests encode the request message in query parameters:
 * - `connect=v1` (required version marker)
 * - `encoding=proto|json` (required codec)
 * - `message=<payload>` (required, base64url or percent-encoded depending on `base64` flag)
 * - `base64=1` (optional; if present the message is URL-safe base64 without padding)
 * - `compression=identity|gzip|...` (optional; only `identity` is currently supported)
 *
 * See [Connect protocol — Unary GET Request](https://connectrpc.com/docs/protocol#unary-get-request).
 */
inline fun <reified Resource : Any, reified Req : Any, reified Res : Any> handleGet(
    noinline handlerFunc: suspend (request: Req, call: ApplicationCall) -> ResponseMessage<Res>,
): suspend RoutingContext.(Resource) -> Unit {
    val reqClass = Req::class
    val resClass = Res::class
    return { _ ->
        handleGetCall(call = call, reqClass = reqClass, resClass = resClass, handlerFunc = handlerFunc)
    }
}

@PublishedApi
internal suspend fun <Req : Any, Res : Any> handleGetCall(
    call: ApplicationCall,
    reqClass: KClass<Req>,
    resClass: KClass<Res>,
    handlerFunc: suspend (request: Req, call: ApplicationCall) -> ResponseMessage<Res>,
) {
    val request = call.request

    // Validate the Connect version marker.
    val connectVersion = request.queryParameters["connect"]
    if (connectVersion != "v1") {
        val error = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "missing or invalid 'connect' query parameter: expected 'v1', got '$connectVersion'",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.INVALID_ARGUMENT.asHTTPStatusCode(),
        )
        return
    }

    // Validate encoding.
    val encoding = request.queryParameters["encoding"]
    if (encoding == null) {
        val error = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "missing 'encoding' query parameter",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.INVALID_ARGUMENT.asHTTPStatusCode(),
        )
        return
    }

    // Validate that compression is either absent or 'identity'.
    val compression = request.queryParameters["compression"]
    if (compression != null && compression != "identity") {
        val error = ConnectException(
            code = Code.UNIMPLEMENTED,
            message = "unsupported compression: $compression",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.UNIMPLEMENTED.asHTTPStatusCode(),
        )
        return
    }

    // Decode the message payload.
    val messageParam = request.queryParameters["message"]
    if (messageParam == null) {
        val error = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "missing 'message' query parameter",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.INVALID_ARGUMENT.asHTTPStatusCode(),
        )
        return
    }

    val isBase64 = request.queryParameters["base64"] == "1"
    val messageBytes: ByteArray = try {
        if (isBase64) {
            // URL-safe base64 without padding (base64url).
            Base64.getUrlDecoder().decode(messageParam)
        } else {
            // Raw query value (already percent-decoded by Ktor's query parsing).
            messageParam.toByteArray(Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        val error = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "failed to decode 'message' query parameter: ${e.message}",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.INVALID_ARGUMENT.asHTTPStatusCode(),
        )
        return
    }

    // Resolve serialization strategies from the application (respects custom TypeRegistry).
    val strategies = call.application.connectGetStrategies()

    // Deserialize the request message.
    val req: Req = try {
        when (encoding) {
            "proto" -> strategies.proto.codec(reqClass).deserialize(Buffer().write(messageBytes))

            "json" -> strategies.json.codec(reqClass).deserialize(Buffer().write(messageBytes))

            else -> throw ConnectException(
                code = Code.INVALID_ARGUMENT,
                message = "unsupported encoding: $encoding (must be 'proto' or 'json')",
            )
        }
    } catch (e: ConnectException) {
        call.respondBytes(
            bytes = e.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = e.code.asHTTPStatusCode(),
        )
        return
    } catch (e: Throwable) {
        val error = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "failed to deserialize request: ${e.message}",
        )
        call.respondBytes(
            bytes = error.toErrorJsonBytes(),
            contentType = ContentType.Application.Json,
            status = Code.INVALID_ARGUMENT.asHTTPStatusCode(),
        )
        return
    }

    // Store raw query params in call attributes so handlers can inspect connect_get_info.
    val queryParams: List<Pair<String, List<String>>> = request.queryParameters.entries()
        .map { (name, values) -> name to values.toList() }
    call.attributes.put(ConnectGetQueryParamsKey, queryParams)

    // Determine response Content-Type from the request encoding.
    val responseContentType = when (encoding) {
        "proto" -> ContentType("application", "proto")
        else -> ContentType.Application.Json
    }

    // Invoke the handler and write the response.
    handlerFunc(req, call)
        .also { response ->
            response.headers.map { (key, value) ->
                value.map { call.response.headers.append(key, it) }
            }
            response.trailers.map { (key, value) ->
                value.map { call.response.headers.append("Trailer-$key", it) }
            }
        }.fold(
            onSuccess = { res ->
                // Serialize the response manually because there is no request body from which
                // ContentNegotiation could derive the response Content-Type for GET requests.
                val resBytes = when (encoding) {
                    "proto" -> strategies.proto.codec(resClass).serialize(res).readByteArray()

                    else -> strategies.jsonSerializer?.serialize(res, resClass)
                        ?: strategies.json.codec(resClass).serialize(res).readByteArray()
                }
                call.respondBytes(bytes = resBytes, contentType = responseContentType)
            },
            onFailure = {
                call.respondBytes(
                    bytes = it.toErrorJsonBytes(),
                    contentType = ContentType.Application.Json,
                    status = it.code.asHTTPStatusCode(),
                )
            },
        )
}
