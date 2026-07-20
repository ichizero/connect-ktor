package io.github.ichizero.connect.ktor

import io.ktor.http.Parameters
import io.ktor.util.AttributeKey

/**
 * Attribute key used to expose Connect GET query parameters to RPC handler implementations.
 *
 * When a request is received via HTTP GET (Connect GET protocol), the GET handler stores the
 * request's query [Parameters] verbatim in this attribute so that the handler can inspect them —
 * for example to populate `ConformancePayload.RequestInfo.connect_get_info`. The presence of this
 * attribute signals that the request arrived via Connect GET. Consumers can use `entries()`,
 * `forEach`, or `getAll(name)` to read the values.
 */
val ConnectGetQueryParamsKey: AttributeKey<Parameters> =
    AttributeKey("io.github.ichizero.connect.ktor.ConnectGetQueryParams")
