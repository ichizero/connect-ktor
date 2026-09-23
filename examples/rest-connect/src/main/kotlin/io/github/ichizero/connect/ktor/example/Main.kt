package io.github.ichizero.connect.ktor.example

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import io.github.ichizero.connect.ktor.example.proto.GetUserRequest
import io.github.ichizero.connect.ktor.example.proto.GetUserResponse
import io.github.ichizero.connect.ktor.example.proto.UserServiceHandlerInterface
import io.github.ichizero.connect.ktor.example.proto.getUserResponse
import io.github.ichizero.connect.ktor.example.proto.userService
import io.github.ichizero.connect.ktor.toErrorJsonBytes
import io.github.ichizero.ktor.serialization.connect.connectJson
import io.github.ichizero.ktor.serialization.connect.connectProto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CONNECT_PATH = "/example.users.v1.UserService/GetUser"

// The token stands in for the application's existing credential validator.
private const val DEMO_TOKEN = "demo-token"

data class User(val id: String, val displayName: String)

fun interface UserService {
    fun getUser(id: String): User?
}

@Serializable
data class UserDto(val id: String, val displayName: String)

fun User.toDto() = UserDto(id, displayName)

fun User.toProto(): GetUserResponse = getUserResponse {
    id = this@toProto.id
    displayName = this@toProto.displayName
}

class GetUserHandler(private val users: UserService) : UserServiceHandlerInterface {
    override suspend fun getUser(
        request: GetUserRequest,
        call: ApplicationCall,
    ): ResponseMessage<GetUserResponse> = users.getUser(request.id)?.let { user ->
        ResponseMessage.Success(user.toProto(), emptyMap(), emptyMap())
    } ?: ResponseMessage.Failure(
        ConnectException(code = Code.NOT_FOUND, message = "user not found"),
        emptyMap(),
        emptyMap(),
    )
}

fun Application.userApi(users: UserService) {
    install(Resources)
    install(Authentication) {
        bearer("existing-auth") {
            realm = "users"
            authenticate { credential ->
                if (credential.token == DEMO_TOKEN) UserIdPrincipal("demo-user") else null
            }
        }
    }
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, _ ->
            if (call.request.path() == CONNECT_PATH) {
                call.respondBytes(
                    ConnectException(Code.UNAUTHENTICATED, "authentication required").toErrorJsonBytes(),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            } else {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            }
        }
    }
    routing {
        install(ContentNegotiation) {
            connectJson()
            connectProto()
        }
        authenticate("existing-auth") {
            get("/users/{id}") {
                val id = requireNotNull(call.parameters["id"])
                val user = users.getUser(id)
                if (user == null) {
                    call.respondText("user not found", status = HttpStatusCode.NotFound)
                } else {
                    call.respondText(Json.encodeToString(user.toDto()), ContentType.Application.Json)
                }
            }
            userService(GetUserHandler(users))
        }
    }
}

fun main() {
    val users = UserService { id -> if (id == "42") User("42", "Ada") else null }
    embeddedServer(CIO, port = 8080) { userApi(users) }.start(wait = true)
}
