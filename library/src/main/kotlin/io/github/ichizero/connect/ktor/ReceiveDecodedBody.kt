package io.github.ichizero.connect.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel

/**
 * Intercepts the request body after it has been decoded but before anything transforms it.
 *
 * The handler runs in a dedicated receive-pipeline phase inserted just before `Transform`, which
 * is where every body-consuming plugin lives: Ktor's built-in transformers (`receive<ByteArray>()`,
 * `receive<InputStream>()`, …) and `ContentNegotiation`'s deserialization.  Ktor's `Compression`
 * plugin decodes in its own `ContentDecoding` phase, also inserted before `Transform`; because it
 * is installed at the application scope it inserts first, so this phase lands between the two.
 *
 * The upshot is that the handler always receives a decompressed [ByteReadChannel], whatever the
 * caller ends up receiving the body as, and regardless of where `ContentNegotiation` is installed.
 */
internal object ReceiveDecodedBody :
    Hook<suspend (call: ApplicationCall, body: ByteReadChannel) -> ByteReadChannel> {

    private val phase = PipelinePhase("ConnectBodyLimit")

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (call: ApplicationCall, body: ByteReadChannel) -> ByteReadChannel,
    ) {
        pipeline.receivePipeline.insertPhaseBefore(ApplicationReceivePipeline.Transform, phase)
        pipeline.receivePipeline.intercept(phase) { body ->
            // Defensive: the phase runs ahead of every transformer, so the body is always the
            // raw channel.  Anything else means Ktor changed its pipeline layout — pass it
            // through untouched rather than failing the request.
            if (body is ByteReadChannel) proceedWith(handler(call, body))
        }
    }
}
