package io.github.ichizero.connect.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationReceivePipeline

/**
 * Intercepts the receive pipeline's `Transform` phase.  The handler returns the transformed body
 * to proceed with, or `null` to finish the receive pipeline (used after the handler has already
 * responded to the call).
 *
 * Ktor runs all `Transform` interceptors in a fixed order: application-scoped plugins first (in
 * install order), then route-scoped plugins (in install order).  Both the `Compression` plugin's
 * request decode and `ContentNegotiation`'s deserialization live in this phase, so a route-scoped
 * plugin using this hook observes the decoded body as long as it is installed before
 * `ContentNegotiation`.
 */
internal object ReceiveBodyTransform : Hook<suspend (call: ApplicationCall, body: Any) -> Any?> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (call: ApplicationCall, body: Any) -> Any?,
    ) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.Transform) { body ->
            when (val transformed = handler(call, body)) {
                null -> finish()
                else -> proceedWith(transformed)
            }
        }
    }
}
