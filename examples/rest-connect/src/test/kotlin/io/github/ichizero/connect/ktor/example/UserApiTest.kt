package io.github.ichizero.connect.ktor.example

import io.github.ichizero.connect.ktor.example.proto.GetUserResponse
import io.github.ichizero.connect.ktor.example.proto.getUserRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

private const val CONNECT_PATH = "/example.users.v1.UserService/GetUser"

class UserApiTest : FunSpec({
    val requestedIds = mutableListOf<String>()
    val users = UserService { id ->
        requestedIds += id
        if (id == "42") User("42", "Ada") else null
    }

    beforeTest { requestedIds.clear() }

    test("REST returns its DTO through the shared service") {
        testApplication {
            application { userApi(users) }
            val response = client.get("/users/42") { header(HttpHeaders.Authorization, "Bearer demo-token") }
            response.status shouldBe HttpStatusCode.OK
            Json.decodeFromString<UserDto>(response.bodyAsText()) shouldBe UserDto("42", "Ada")
            requestedIds shouldBe listOf("42")
        }
    }

    test("Connect returns its proto through the shared service") {
        testApplication {
            application { userApi(users) }
            val response = client.post(CONNECT_PATH) {
                header(HttpHeaders.Authorization, "Bearer demo-token")
                header(HttpHeaders.ContentType, "application/proto")
                header(HttpHeaders.Accept, "application/proto")
                setBody(getUserRequest { id = "42" }.toByteArray())
            }
            response.status shouldBe HttpStatusCode.OK
            GetUserResponse.parseFrom(response.bodyAsBytes()).let {
                it.id shouldBe "42"
                it.displayName shouldBe "Ada"
            }
            requestedIds shouldBe listOf("42")
        }
    }

    test("REST returns 404 for a missing user") {
        testApplication {
            application { userApi(users) }
            val response = client.get("/users/missing") { header(HttpHeaders.Authorization, "Bearer demo-token") }
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldBe "user not found"
            requestedIds shouldBe listOf("missing")
        }
    }

    test("Connect returns not_found for a missing user") {
        testApplication {
            application { userApi(users) }
            val response = client.post(CONNECT_PATH) {
                header(HttpHeaders.Authorization, "Bearer demo-token")
                header(HttpHeaders.ContentType, "application/proto")
                setBody(getUserRequest { id = "missing" }.toByteArray())
            }
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldContain "\"code\":\"not_found\""
            requestedIds shouldBe listOf("missing")
        }
    }

    test("REST rejects a missing credential before calling the service") {
        testApplication {
            application { userApi(users) }
            val response = client.get("/users/42")
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldBe "unauthorized"
            requestedIds shouldBe emptyList()
        }
    }

    test("Connect rejects a missing credential with unauthenticated") {
        testApplication {
            application { userApi(users) }
            val response = client.post(CONNECT_PATH) {
                header(HttpHeaders.ContentType, "application/proto")
                setBody(getUserRequest { id = "42" }.toByteArray())
            }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "\"code\":\"unauthenticated\""
            requestedIds shouldBe emptyList()
        }
    }
})
