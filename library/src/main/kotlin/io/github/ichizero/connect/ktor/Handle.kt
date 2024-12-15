package io.github.ichizero.connect.ktor

import com.connectrpc.ResponseMessage
import com.connectrpc.fold
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * handle is a utility function that bridges between Ktor routing handlers and Connect-Ktor Handler Interface.
 */
inline fun <reified Resource : Any, reified Req : Any, reified Res : Any> handle(
    noinline handlerFunc: suspend (request: Req, call: ApplicationCall) -> ResponseMessage<Res>,
): suspend RoutingContext.(Resource, Req) -> Unit = { _, request ->
    handlerFunc(request, call)
        .also { response ->
            response.headers.map { (key, value) ->
                value.map { call.response.headers.append(key, it) }
            }
            response.trailers.map { (key, value) ->
                value.map { call.response.headers.append("Trailer-$key", it) }
            }
        }.fold(
            onSuccess = { call.respond(it) },
            onFailure = {
                call.respondBytes(
                    bytes = it.toErrorJsonBytes(),
                    contentType = ContentType.Application.Json,
                    status = it.code.asHTTPStatusCode(),
                )
            },
        )
}
