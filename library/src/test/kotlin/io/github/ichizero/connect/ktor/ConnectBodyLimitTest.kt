package io.github.ichizero.connect.ktor

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class ConnectBodyLimitTest : FunSpec({
    context("ConnectBodyLimit") {
        test("request within limit is accepted") {
            testApplication {
                application {
                    routing {
                        route("/test") {
                            install(ConnectBodyLimit) { maxBytes = 100 }
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
                            install(ConnectBodyLimit) { maxBytes = 50 }
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
                            install(ConnectBodyLimit) { maxBytes = 10 }
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

        test("route outside ConnectBodyLimit scope is not affected") {
            testApplication {
                application {
                    routing {
                        route("/limited") {
                            install(ConnectBodyLimit) { maxBytes = 10 }
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
