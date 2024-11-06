package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import io.ktor.http.HttpStatusCode

/**
 * Convert [Code] to [HttpStatusCode].
 *
 * [Connect Protocol Reference](https://connectrpc.com/docs/protocol#error-codes)
 */
fun Code.asHTTPStatusCode(): HttpStatusCode = when (this) {
    Code.CANCELED -> HttpStatusCode(value = 499, description = "Client Closed Request")
    Code.UNKNOWN -> HttpStatusCode.InternalServerError
    Code.INVALID_ARGUMENT -> HttpStatusCode.BadRequest
    Code.DEADLINE_EXCEEDED -> HttpStatusCode.GatewayTimeout
    Code.NOT_FOUND -> HttpStatusCode.NotFound
    Code.ALREADY_EXISTS -> HttpStatusCode.Conflict
    Code.PERMISSION_DENIED -> HttpStatusCode.Forbidden
    Code.RESOURCE_EXHAUSTED -> HttpStatusCode.TooManyRequests
    Code.FAILED_PRECONDITION -> HttpStatusCode.BadRequest
    Code.ABORTED -> HttpStatusCode.Conflict
    Code.OUT_OF_RANGE -> HttpStatusCode.BadRequest
    Code.UNIMPLEMENTED -> HttpStatusCode.NotImplemented
    Code.INTERNAL_ERROR -> HttpStatusCode.InternalServerError
    Code.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    Code.DATA_LOSS -> HttpStatusCode.InternalServerError
    Code.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
}
