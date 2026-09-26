package io.github.ichizero.connect.ktor

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey

internal val ConnectUnaryRouteKey = AttributeKey<Unit>("ConnectUnaryRoute")

/** Marks a generated unary POST route so validation errors use Connect responses. */
val ConnectUnaryRoute = createRouteScopedPlugin("ConnectUnaryRoute") {
    onCall { call -> call.attributes.put(ConnectUnaryRouteKey, Unit) }
}
