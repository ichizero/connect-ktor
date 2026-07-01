package io.github.ichizero.connect.ktor

import io.ktor.util.AttributeKey

/**
 * Attribute key used to expose Connect GET query parameters to RPC handler implementations.
 *
 * When a request is received via HTTP GET (Connect GET protocol), the GET handler stores the
 * raw query parameters in this attribute so that the handler can inspect them — for example to
 * populate `ConformancePayload.RequestInfo.connect_get_info`.
 *
 * The list contains one entry per distinct query-parameter name. Each entry is a pair of the
 * parameter name and the list of its values (typically a single-element list for GET params).
 */
val ConnectGetQueryParamsKey: AttributeKey<List<Pair<String, List<String>>>> =
    AttributeKey("io.github.ichizero.connect.ktor.ConnectGetQueryParams")
