# REST and Connect on one Ktor application

This runnable example keeps `GET /users/{id}` while adding the unary Connect procedure `example.users.v1.UserService/GetUser`. Both routes call the same `UserService`. The in-memory record and `demo-token` are sample data; replace the token check with your application's existing credential validator.

## Run and verify

From the repository root, with JDK 21, Go, and Buf installed:

```sh
mkdir -p protoc-gen-connect-ktor/out
(cd protoc-gen-connect-ktor && go build -o out/protoc-gen-connect-ktor ./cmd/protoc-gen-connect-ktor)
./gradlew :examples:rest-connect:test
./gradlew :examples:rest-connect:run
```

The last command listens on port 8080. In another terminal:

```sh
curl -i -H 'Authorization: Bearer demo-token' http://localhost:8080/users/42
curl -i -H 'Authorization: Bearer demo-token' http://localhost:8080/users/missing
curl -i http://localhost:8080/users/42
curl -i -X POST http://localhost:8080/example.users.v1.UserService/GetUser \
  -H 'Authorization: Bearer demo-token' -H 'Content-Type: application/json' \
  -d '{"id":"42"}'
curl -i -X POST http://localhost:8080/example.users.v1.UserService/GetUser \
  -H 'Authorization: Bearer demo-token' -H 'Content-Type: application/json' \
  -d '{"id":"missing"}'
curl -i -X POST http://localhost:8080/example.users.v1.UserService/GetUser \
  -H 'Content-Type: application/json' -d '{"id":"42"}'
```

The test suite checks both entrances with `testApplication`, including success, missing users, and missing credentials. It also verifies that unauthenticated requests never call `UserService`.

## Mapping and responses

`User` is the business value. The REST route converts it to `UserDto`; the Connect handler converts it to `GetUserResponse`. Keep these conversions at the transport boundary so the service does not depend on either wire format.

| Case                          | REST                                        | Connect                                                                                                                                |
| ----------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| User `42`                     | HTTP 200, `{"id":"42","displayName":"Ada"}` | HTTP 200, `{"id":"42","displayName":"Ada"}` with JSON requests, or a protobuf response when requested with `Accept: application/proto` |
| Unknown user                  | HTTP 404, `user not found`                  | HTTP 404, `{"code":"not_found","message":"user not found"}`                                                                            |
| Missing or invalid credential | HTTP 401, `unauthorized`                    | HTTP 401, `{"code":"unauthenticated","message":"authentication required"}`                                                             |

The single Ktor `Authentication` provider protects both routes. `StatusPages` turns its 401 response into a Connect error only for the RPC path. In a real application, reuse the existing named provider and its token or session validator. Keep TLS enabled when sending bearer credentials outside localhost.

## Add Connect without removing REST

1. Keep the existing REST route and business service. Define `GetUser` in `proto/example/users/v1/users.proto` and generate the Java protobuf types and Connect-Ktor route with `buf generate` (the Gradle module runs it before compilation).
2. Implement `UserServiceHandlerInterface` as an adapter over the same `UserService`. Convert the returned `User` to `GetUserResponse`; return Connect `not_found` for an absent user. Leave the REST DTO and its response contract in place.
3. Put `userService(GetUserHandler(users))` under the same `authenticate("existing-auth")` block as the REST route. Register the Connect JSON and protobuf converters. Adapt the authentication 401 to the Connect error format at the HTTP boundary.
4. Run the six route tests and the example commands above. Direct new callers to the RPC URL while existing REST callers keep using `/users/{id}`.

To roll back, stop directing new callers to the RPC URL and remove `userService(GetUserHandler(users))` from the authenticated route. Remove the Connect-specific 401 branch after Connect traffic has stopped. The REST route and `UserService` continue to work without changing their contracts.
