package io.github.ichizero.connect.ktor

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import io.github.ichizero.connect.ktor.streaming.connectTimeoutMs
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Only expiry of this RPC's budget becomes a Connect error; other cancellation propagates. */
internal suspend fun <T> withConnectTimeout(timeout: Duration?, block: suspend () -> T): T {
    if (timeout == null) return block()
    // Box nullable results so a successful null is distinguishable from timeout expiry.
    val result = withTimeoutOrNull(timeout) { TimeoutResult(block()) }
        ?: throw ConnectException(code = Code.DEADLINE_EXCEEDED, message = "deadline exceeded")
    return result.value
}

private data class TimeoutResult<T>(val value: T)

/** The unary budget covers handler execution, after Ktor has decoded the request. */
@PublishedApi
internal suspend fun <Res : Any> ApplicationCall.invokeUnaryHandler(
    handler: suspend () -> ResponseMessage<Res>,
): ResponseMessage<Res> = try {
    withConnectTimeout(connectTimeoutMs()?.milliseconds, handler)
} catch (cause: ConnectException) {
    ResponseMessage.Failure(cause, emptyMap(), cause.metadata)
}
