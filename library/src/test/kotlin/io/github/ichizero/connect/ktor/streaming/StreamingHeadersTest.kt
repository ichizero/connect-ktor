package io.github.ichizero.connect.ktor.streaming

import com.connectrpc.ConnectException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class StreamingHeadersTest : FunSpec({
    suspend fun probe(
        headers: Map<String, String> = emptyMap(),
        probe: ApplicationCall.() -> String,
    ): Pair<HttpStatusCode, String> {
        var status = HttpStatusCode.OK
        var body = ""
        testApplication {
            routing {
                get("/probe") {
                    try {
                        call.respondText(call.probe())
                    } catch (e: ConnectException) {
                        call.respond(HttpStatusCode.BadRequest, e.code.codeName)
                    }
                }
            }
            val resp: HttpResponse = client.get("/probe") {
                headers.forEach { (k, v) -> header(k, v) }
            }
            status = resp.status
            body = resp.bodyAsText()
        }
        return status to body
    }

    test("accepts absent connect-protocol-version") {
        probe {
            validateConnectStreamingHeaders()
            "OK"
        }.first shouldBe HttpStatusCode.OK
    }

    test("accepts connect-protocol-version: 1") {
        probe(mapOf("connect-protocol-version" to "1")) {
            validateConnectStreamingHeaders()
            "OK"
        }.first shouldBe HttpStatusCode.OK
    }

    test("rejects connect-protocol-version: 2 with INVALID_ARGUMENT") {
        val (status, body) = probe(mapOf("connect-protocol-version" to "2")) {
            validateConnectStreamingHeaders()
            "OK"
        }
        status shouldBe HttpStatusCode.BadRequest
        body shouldBe "invalid_argument"
    }

    test("rejects connect-content-encoding: gzip with UNIMPLEMENTED") {
        val (status, body) = probe(mapOf("connect-content-encoding" to "gzip")) {
            validateConnectStreamingHeaders()
            "OK"
        }
        status shouldBe HttpStatusCode.BadRequest
        body shouldBe "unimplemented"
    }

    test("accepts connect-content-encoding: identity") {
        probe(mapOf("connect-content-encoding" to "identity")) {
            validateConnectStreamingHeaders()
            "OK"
        }.first shouldBe HttpStatusCode.OK
    }

    test("connectTimeoutMs returns null when header absent") {
        probe { connectTimeoutMs().toString() }.second shouldBe "null"
    }

    test("connectTimeoutMs returns parsed long when header present") {
        probe(mapOf("connect-timeout-ms" to "1500")) {
            connectTimeoutMs().toString()
        }.second shouldBe "1500"
    }

    test("connectTimeoutMs ignores non-positive and malformed values") {
        probe(mapOf("connect-timeout-ms" to "0")) { connectTimeoutMs().toString() }.second shouldBe "null"
        probe(mapOf("connect-timeout-ms" to "-10")) { connectTimeoutMs().toString() }.second shouldBe "null"
        probe(mapOf("connect-timeout-ms" to "abc")) { connectTimeoutMs().toString() }.second shouldBe "null"
    }
})
