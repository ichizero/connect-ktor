# Connect-Ktor

[![Maven Central Version](https://img.shields.io/maven-central/v/io.github.ichizero/connect-ktor)](https://central.sonatype.com/artifact/io.github.ichizero/connect-ktor)
![CI](https://github.com/ichizero/connect-ktor/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Connect-Ktor is a library developed as an extension of
[Connect-Kotlin](https://github.com/connectrpc/connect-kotlin)
for [Ktor](https://github.com/ktorio/ktor) servers.
It aims to gradually introduce the Connect Protocol into existing Ktor REST servers.

## Features

- connect-ktor
  - Serialize/Deserialize Protocol Buffers JSON messages with Connect-Kotlin.
  - Request validation support with [protovalidate](https://github.com/bufbuild/protovalidate).
- protoc-gen-connect-ktor
  - Generate Ktor route handler interfaces from Protocol Buffers service definitions.
    

## Usage

### Add dependencies

Add the conenct-ktor library to your build.gradle.kts.

```kotlin
dependencies {
    implementation("io.github.ichizero:connect-ktor:0.1.3")
}
```

### Generation

#### 1. Setup protoc-gen-connect-ktor

On Linux or macOS, install the plugin with [Homebrew](https://brew.sh/).

```bash
brew install ichizero/tap/protoc-gen-connect-ktor
```

Alternatively, you can download the plugin executable file from
[releases](https://github.com/ichizero/connect-ktor/releases)
and place it in your PATH.

#### 2. Add plugins to your buf.gen.yaml

```yaml
version: v2
clean: true
managed:
  enabled: true
plugins:
  - remote: buf.build/protocolbuffers/java
    out: path/to/code
  - remote: buf.build/protocolbuffers/kotlin
    out: path/to/code
  - remote: buf.build/connectrpc/kotlin
    out: path/to/code
  - local: protoc-gen-connect-ktor
    out: path/to/code
```

#### 3. Generate the Ktor route handler interfaces

```bash
buf generate
```

Generated handler interface is like below:

```kotlin
public interface ElizaServiceHandlerInterface {
    public suspend fun say(request: SayRequest, call: ApplicationCall): ResponseMessage<SayResponse>

    public object Procedures {
        @Resource("/connectrpc.eliza.v1.ElizaService/Say")
        public class Say
    }
}

public fun Route.elizaService(handler: ElizaServiceHandlerInterface) {
    post<ElizaServiceHandler.Procedures.Say, SayRequest>(handle(handler::say))
}
```

### Implementation

#### 1. Implement the generated handler interface 

```kotlin
object ElizaServiceHandler: ElizaServiceHandlerInterface {
    override suspend fun say(
        request: SayRequest,
        call: ApplicationCall,
    ): ResponseMessage<SayResponse> = ResponseMessage.Success(
        sayResponse { sentence = request.sentence },
        emptyMap(),
        emptyMap(),
    )
}
```

#### 2. Register the handler in the Ktor server

```kotlin
fun main() {
    embeddedServer(CIO, port = 8080) {
        install(Resources)
        routing {
            install(ContentNegotiation) {
                connectJson()
            }
            elizaService(ElizaServiceHandler)
        }
    }.start(wait = false)
}
```

### Request Validation with protovalidate

The plugin named ProtoRequestValidation is provided to validate the request message with protovalidate.
If the request message is invalid, the server will respond with a 400 Bad Request status code with details.

```protobuf
syntax = "proto3";

package stricteliza.v1;

import "buf/validate/validate.proto";

message SayRequest {
    string sentence = 1 [(buf.validate.field).string.max_len = 100];
}
```

```kotlin
fun main() {
    embeddedServer(CIO, port = 8080) {
        install(Resources)
        install(StatusPages) {
            exception<ProtoRequestValidationException> { call, cause ->
                call.respondBytes(
                    bytes = cause.toErrorJsonBytes(),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
            }
        }
        routing {
            install(ContentNegotiation) {
                connectJson()
            }
            install(ProtoRequestValidation)
            strictElizaService(StrictElizaServiceHandler)
        }
    }.start(wait = false)
}
```

## License

Offered under the [Apache 2 license](https://github.com/ichizero/connect-ktor/blob/main/LICENSE).

## Acknowledgements

I'm very grateful for the [Connect Protocol](https://github.com/connectrpc/connect-go),
[Connect-Kotlin](https://github.com/connectrpc/connect-kotlin) and its authors.
Their pioneering work and contributions to the open-source community made the development of Connect-Ktor possible.

I encourage you to try Connect-Ktor and experience a new level of communication with your Ktor server!
