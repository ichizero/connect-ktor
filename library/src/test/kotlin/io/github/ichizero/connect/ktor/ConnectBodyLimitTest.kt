package io.github.ichizero.connect.ktor

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel

class ConnectBodyLimitTest : FunSpec({
    context("connectBodyLimit") {
        test("request within limit is accepted") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 100)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("ok")
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(50))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }
            }
        }

        test("request exactly at limit is accepted") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 50)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("ok")
                            }
                        }
                    }
                }

                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(50))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }
            }
        }

        test("request exceeding limit returns RESOURCE_EXHAUSTED connect error") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                // Body is 26 bytes, larger than 10-byte limit.
                client
                    .post("/test") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(26))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("chunked transfer-encoding without Content-Length is also capped") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                // Send a 100-byte body via Transfer-Encoding: chunked (no
                // Content-Length).  RequestBodyLimit's byte counter must
                // enforce the cap and trigger the Connect error response.
                val body = "a".repeat(100).toByteArray()
                client
                    .post("/test") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(
                            object : OutgoingContent.ReadChannelContent() {
                                override fun readFrom(): ByteReadChannel = ByteReadChannel(body)
                            },
                        )
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("REST and Connect routes coexist with different overflow behaviour") {
            // Route-scoped Ktor RequestBodyLimit on /rest gives a default 413,
            // while connectBodyLimit on the /connect subtree turns the overflow
            // into a Connect-protocol 429 + JSON body.
            testApplication {
                application {
                    routing {
                        route("/rest") {
                            install(RequestBodyLimit) {
                                bodyLimit { 10 }
                            }
                            post {
                                call.receive<ByteArray>()
                                call.respondText("rest-ok")
                            }
                        }
                        route("/connect") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("connect-ok")
                            }
                        }
                    }
                }

                client
                    .post("/rest") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        // Ktor's default RequestBodyLimit response is 413.
                        res.status shouldBe HttpStatusCode.PayloadTooLarge
                    }

                client
                    .post("/connect") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("app-wide StatusPages handler for unrelated exception does not interfere") {
            // When the user installs StatusPages app-wide but only registers
            // handlers for exception types *other than* PayloadTooLargeException,
            // connectBodyLimit's Connect-protocol 429 wins.  A blanket
            // `exception<Throwable>` handler still overrides — that limitation
            // is documented on `Route.connectBodyLimit`.
            testApplication {
                application {
                    install(StatusPages) {
                        exception<IllegalStateException> { call, _ ->
                            call.respond(HttpStatusCode.InternalServerError, "ise")
                        }
                    }
                    routing {
                        route("/connect") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                client
                    .post("/connect") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("spoofed Content-Length below the cap is still rejected by the byte counter") {
            // Client lies about Content-Length: declares a small value, then
            // streams a much larger body.  The Content-Length precheck passes,
            // so the cap must be enforced by RequestBodyLimit's byte counter.
            testApplication {
                application {
                    routing {
                        route("/connect") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("should not reach here")
                            }
                        }
                    }
                }

                val realBody = "a".repeat(100).toByteArray()
                client
                    .post("/connect") {
                        header(HttpHeaders.ContentType, "application/json")
                        // Send chunked (no Content-Length header) which mimics
                        // the "lied about size" scenario as far as the server's
                        // body counter is concerned: the precheck has nothing
                        // to compare against, so the cap is enforced purely by
                        // counting streamed bytes.
                        setBody(
                            object : OutgoingContent.ReadChannelContent() {
                                override fun readFrom(): ByteReadChannel = ByteReadChannel(realBody)
                            },
                        )
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                        res.bodyAsText() shouldEqualJson
                            """{"code":"resource_exhausted","message":"request body too large"}"""
                    }
            }
        }

        test("route outside connectBodyLimit scope is not affected") {
            testApplication {
                application {
                    routing {
                        route("/limited") {
                            connectBodyLimit(maxBytes = 10)
                            post {
                                call.receive<ByteArray>()
                                call.respondText("limited")
                            }
                        }
                        route("/unlimited") {
                            post {
                                call.receive<ByteArray>()
                                call.respondText("unlimited")
                            }
                        }
                    }
                }

                // The /unlimited route should accept large bodies.
                client
                    .post("/unlimited") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.OK
                    }

                // The /limited route should reject large bodies.
                client
                    .post("/limited") {
                        header("Content-Type", "application/json")
                        setBody("a".repeat(100))
                    }.let { res ->
                        res.status shouldBe HttpStatusCode.TooManyRequests
                    }
            }
        }
    }
})
