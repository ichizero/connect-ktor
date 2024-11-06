# Connect-Ktor

[![Maven Central Version](https://img.shields.io/maven-central/v/io.github.ichizero/protoc-gen-connect-ktor)](https://central.sonatype.com/artifact/io.github.ichizero/protoc-gen-connect-ktor)
![CI](https://github.com/ichizero/connect-ktor/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Connect-Ktor is a library developed as an extension of
[Connect-Kotlin](https://github.com/connectrpc/connect-kotlin)
for [Ktor](https://github.com/ktorio/ktor) servers.
It aims to gradually introduce the Connect Protocol into existing Ktor REST servers.

## Features

- connect-ktor
  - Serialize/Deserialize Protocol Buffers JSON messages with Connect-Kotlin.
- protoc-gen-connect-ktor
  - Generate Ktor route handler interfaces from Protocol Buffers service definitions.
    

## Usage

### Add dependencies

Add the conenct-ktor library to your build.gradle.kts.

```kotlin
dependencies {
    implementation("io.github.ichizero:connect-ktor:0.0.2")
}
```

### Generation

#### 1. Setup protoc-gen-connect-ktor

Download plugin executable file named protoc-gen-connect-ktor from
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
public interface ElizaServiceHandler {
    public suspend fun say(request: SayRequest, call: ApplicationCall): ResponseMessage<SayResponse>

    public object Procedures {
        @Resource("/connectrpc.eliza.v1.ElizaService/Say")
        public class Say
    }
}

public fun Route.elizaService(handler: ElizaServiceHandler) {
    post<ElizaServiceHandler.Procedures.Say, SayRequest>(handle(handler::say))
}
```

### Implementation

#### 1. Implement the generated handler interface 

```kotlin
object ElizaServiceHandlerImpl : ElizaServiceHandler {
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
            elizaService(ElizaServiceHandlerImpl)
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
