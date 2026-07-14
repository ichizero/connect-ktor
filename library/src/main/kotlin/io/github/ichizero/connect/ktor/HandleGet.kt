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

/** Codecs supported by the Connect GET path; anything else is rejected with CODE_UNIMPLEMENTED. */
private val supportedGetEncodings: Set<String> = setOf("proto", "json")

/**
 * Responds with a Connect-protocol error payload.
 *
 * Connect error responses are always JSON regardless of request encoding.
 */
private suspend fun ApplicationCall.respondConnectError(error: ConnectException) {
    respondBytes(
        bytes = error.toErrorJsonBytes(),
        contentType = ContentType.Application.Json,
        status = error.code.asHTTPStatusCode(),
    )
}

private suspend fun ApplicationCall.respondConnectError(code: Code, message: String) {
    respondConnectError(ConnectException(code = code, message = message))
}

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
        call.respondConnectError(
            Code.INVALID_ARGUMENT,
            "missing or invalid 'connect' query parameter: expected 'v1', got '$connectVersion'",
        )
        return
    }

    // Validate encoding.
    val encoding = request.queryParameters["encoding"]
    if (encoding == null) {
        call.respondConnectError(Code.INVALID_ARGUMENT, "missing 'encoding' query parameter")
        return
    }
    // Connect spec: an unsupported codec MUST yield CODE_UNIMPLEMENTED together with the list
    // of codecs the server does support.
    if (encoding !in supportedGetEncodings) {
        call.respondConnectError(
            Code.UNIMPLEMENTED,
            "unsupported encoding: \"$encoding\" (supported: ${supportedGetEncodings.joinToString(", ")})",
        )
        return
    }

    // Validate that compression is either absent or 'identity'.
    // NOTE: the accept-encoding query param is ignored for now; revisit when adding gzip
    // response support.
    val compression = request.queryParameters["compression"]
    if (compression != null && compression != "identity") {
        call.respondConnectError(Code.UNIMPLEMENTED, "unsupported compression: $compression")
        return
    }

    // Decode the message payload.
    val messageParam = request.queryParameters["message"]
    if (messageParam == null) {
        call.respondConnectError(Code.INVALID_ARGUMENT, "missing 'message' query parameter")
        return
    }

    // Connect spec: "If the base64 query parameter is present, the value of the parameter should
    // be 1. Other values for the base64 parameter will simply be ignored." So treat only the exact
    // literal "1" as base64url; any other value (e.g. "0", "true", "foo") or its absence means the
    // message is NOT base64. This deliberately does NOT reject unexpected values with
    // INVALID_ARGUMENT — that would diverge from the spec and from the connect-go reference
    // implementation, which uses the same `query.Get("base64") == "1"` check.
    // See https://connectrpc.com/docs/protocol#unary-get-request.
    val isBase64 = request.queryParameters["base64"] == "1"
    // Connect spec: arbitrary binary proto payloads cannot survive a UTF-8 round-trip through
    // percent-decoded query values, so `encoding=proto` REQUIRES `base64=1`. Rejecting up-front
    // prevents silent payload corruption (would otherwise re-encode invalid bytes as U+FFFD).
    if (encoding == "proto" && !isBase64) {
        call.respondConnectError(
            Code.INVALID_ARGUMENT,
            "'encoding=proto' requires 'base64=1' (raw proto bytes are not UTF-8 safe)",
        )
        return
    }
    val messageBytes: ByteArray = try {
        if (isBase64) {
            // URL-safe base64 without padding (base64url).
            Base64.getUrlDecoder().decode(messageParam)
        } else {
            // JSON message: already percent-decoded by Ktor's query parsing.
            messageParam.toByteArray(Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        call.respondConnectError(
            Code.INVALID_ARGUMENT,
            "failed to decode 'message' query parameter: ${e.message}",
        )
        return
    }

    // Resolve serialization strategies from the application (respects custom TypeRegistry).
    val strategies = call.application.connectGetStrategies()

    // Deserialize the request message. `encoding` is guaranteed to be 'proto' or 'json' here
    // because unsupported codecs were rejected with CODE_UNIMPLEMENTED above.
    val req: Req = try {
        when (encoding) {
            "proto" -> strategies.proto.codec(reqClass).deserialize(Buffer().write(messageBytes))
            else -> strategies.json.codec(reqClass).deserialize(Buffer().write(messageBytes))
        }
    } catch (e: ConnectException) {
        call.respondConnectError(e)
        return
    } catch (e: Exception) {
        // Deliberately Exception, not Throwable: JVM Errors must escape, and this is a
        // non-suspending call so CancellationException cannot originate here.
        call.respondConnectError(Code.INVALID_ARGUMENT, "failed to deserialize request: ${e.message}")
        return
    }

    // Store raw query params in call attributes so handlers can inspect connect_get_info.
    call.attributes.put(ConnectGetQueryParamsKey, request.queryParameters)

    // Determine response Content-Type from the request encoding.
    val responseContentType = when (encoding) {
        "proto" -> ContentType("application", "proto")
        else -> ContentType.Application.Json
    }

    // Invoke the handler and write the response.
    handlerFunc(req, call)
        .also { response ->
            response.headers.forEach { (key, value) ->
                value.forEach { call.response.headers.append(key, it) }
            }
            response.trailers.forEach { (key, value) ->
                value.forEach { call.response.headers.append("Trailer-$key", it) }
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
                // Connect spec: error responses are always JSON regardless of request `encoding`.
                // See https://connectrpc.com/docs/protocol#error-end-stream — do NOT switch to
                // `application/proto` here even when the request asked for proto encoding.
                call.respondConnectError(it)
            },
        )
}
