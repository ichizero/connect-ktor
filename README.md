# Connect-Ktor

[![Maven Central Version](https://img.shields.io/maven-central/v/io.github.ichizero/connect-ktor)](https://central.sonatype.com/artifact/io.github.ichizero/connect-ktor)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![CI](https://github.com/ichizero/connect-ktor/actions/workflows/ci.yml/badge.svg)
![CodeRabbit Pull Request Reviews](https://img.shields.io/coderabbit/prs/github/ichizero/connect-ktor?utm_source=oss&utm_medium=github&utm_campaign=ichizero%2Fconnect-ktor&labelColor=171717&color=FF570A&link=https%3A%2F%2Fcoderabbit.ai&label=CodeRabbit+Reviews)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/ichizero/connect-ktor/badge)](https://scorecard.dev/viewer/?uri=github.com/ichizero/connect-ktor)

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

## Connect Protocol support matrix

The following matrix summarises which axes of the [Connect protocol
conformance suite](https://github.com/connectrpc/conformance) connect-ktor
currently exercises. Anything marked ❌ is out of scope today; see the
[Roadmap](#connect-protocol-roadmap) section below for the reasons.

| Feature | Option | Supported |
|---|---|:-:|
| Protocol | `PROTOCOL_CONNECT` | ✅ |
|  | `PROTOCOL_GRPC` | ❌ |
|  | `PROTOCOL_GRPC_WEB` | ❌ |
| HTTP version | `HTTP_VERSION_1` (HTTP/1.1) | ✅ |
|  | `HTTP_VERSION_2` (HTTP/2 / h2c) | ✅ (Netty only) |
|  | `HTTP_VERSION_3` (HTTP/3 over QUIC) | ❌ |
| Codec | `CODEC_PROTO` (`application/proto`) | ✅ |
|  | `CODEC_JSON` (`application/json`) | ✅ |
| Compression | `COMPRESSION_IDENTITY` | ✅ |
|  | `COMPRESSION_GZIP` / `BR` / `ZSTD` / `DEFLATE` / `SNAPPY` | ❌ |
| Stream type | `STREAM_TYPE_UNARY` | ✅ |
|  | `STREAM_TYPE_CLIENT_STREAM` | ❌ |
|  | `STREAM_TYPE_SERVER_STREAM` | ❌ |
|  | `STREAM_TYPE_HALF_DUPLEX_BIDI_STREAM` | ❌ |
|  | `STREAM_TYPE_FULL_DUPLEX_BIDI_STREAM` | ❌ |
| TLS | `supports_tls` | ✅ (Netty only) |
|  | `supports_tls_client_certs` (mTLS) | ✅ (Netty only) |
| Trailers | `supports_trailers` (sent as `Trailer-*` headers on unary responses) | ✅ |
| Connect GET | `supports_connect_get` (idempotent unary via HTTP GET) | ✅ (Netty fully; CIO passes except the two `Connect with GET/.../success` cases that exercise duplicate `X-Conformance-Test` headers — same CIO upstream limitation as the `Duplicate Metadata` / `Basic` entries in `conformance/known-failing-cio.txt`) |
| Message receive limit | `supports_message_receive_limit` | ❌ |

Verified Ktor engines:

| Engine | HTTP versions | Notes |
|---|---|---|
| `io.ktor.server.cio.CIO` | HTTP/1.1 (plaintext only) | CIO upstream does not implement HTTPS (`UnsupportedOperationException: CIO Engine does not currently support HTTPS`), and has no HTTP/2 server support. 10 cases are known-failing — chiefly because CIO collapses duplicate request headers into one (including the two `Connect with GET/.../success` cases) — and are pinned in `conformance/known-failing-cio.txt`. |
| `io.ktor.server.netty.Netty` | HTTP/1.1 + HTTP/2 (h2c & h2 over TLS), plus mTLS | The conformance bootstrap turns on `enableHttp2` + `enableH2c` for the plaintext connector and an `sslConnector` driven by the certs supplied in `ServerCompatRequest.server_creds` (plus `client_tls_cert` for mTLS). ALPN negotiates h2 over TLS automatically. 4 cases are known-failing — all four `Connect Unexpected Requests/**/unexpected-compression` permutations across HTTP/1.1 + HTTP/2 × TLS off/on — and are pinned in `conformance/known-failing-netty.txt`. |

Run the suite locally with:

```bash
task conformance
```

which builds the `:conformance` subproject, installs
`connectconformance`, and runs the suite once per engine using the
`config-<engine>.yaml` and `known-failing-<engine>.txt` files in
`conformance/`.

### Connect protocol roadmap

These are intentionally outside the current matrix and would require
additional work in the library and/or protoc plugin:

- **Streaming RPCs** — `protoc-gen-connect-ktor` only emits unary
  `post<Resource, Req>` routes today. Adding client/server/bidi streams
  requires both generator changes and a streaming framing layer in the
  library.
- **gRPC / gRPC-Web** — connect-ktor speaks Connect only. Supporting
  gRPC additionally requires Length-Prefixed-Message framing and a
  trailer-only error response path.
- **HTTP/2 (CIO) and HTTP/3** — the CIO engine has no HTTP/2 server
  support upstream; HTTP/3 needs QUIC plus TLS, which Ktor does not
  ship out of the box.
- **TLS / mTLS on CIO** — CIO upstream throws
  `UnsupportedOperationException` for HTTPS. Use Netty if you need
  TLS termination at the Ktor layer; otherwise terminate TLS in
  front of CIO.
- **Connect GET (idempotent unary)** — implemented. Methods annotated
  with `option idempotency_level = NO_SIDE_EFFECTS;` get a `GET` route
  in addition to `POST`. Query-parameter decoding (`connect=v1`,
  `encoding=proto|json`, `message=<base64url>`) is handled by
  `handleGet<Resource, Req, Res>(handler::method)` in the library.
- **Compression negotiation** — gzip/br/zstd/deflate/snappy require
  Ktor's `Compression` plugin and Connect-aware
  `Content-Encoding`/`Accept-Encoding` validation. Today an unsupported
  encoding is silently accepted, which is the only category of known
  failure on Netty (4 permutations across HTTP/1.1 + HTTP/2 × TLS
  off/on).
- **`message_receive_limit` enforcement** — the conformance runner
  passes a max body size; the server would need to enforce it before
  parsing the body.


## Usage

### Add dependencies

Add the conenct-ktor library to your build.gradle.kts.

```kotlin
dependencies {
    implementation("io.github.ichizero:connect-ktor:0.1.11")
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

#### 3. (Connect GET) Keep POST and GET codecs in sync

The Connect GET path (idempotent unary RPCs annotated with
`option idempotency_level = NO_SIDE_EFFECTS;`) does not go through
`ContentNegotiation` — there is no request body to negotiate on — so it
resolves its codecs via `installConnectGetCodecs` instead. With the defaults
(`connectJson()` and no `installConnectGetCodecs`) nothing extra is needed.
However, if you pass a custom `TypeRegistry` to `connectJson(...)` — for
example because your messages contain `google.protobuf.Any` fields — you must
register the same registry for the GET path as well; forgetting this makes GET
requests fail at runtime when (de)serialising those `Any` fields:

```kotlin
fun main() {
    val typeRegistry = TypeRegistry.newBuilder()
        .add(SayRequest.getDescriptor())
        .build()

    embeddedServer(CIO, port = 8080) {
        install(Resources)
        // GET path: query-parameter decoding + manual response serialisation.
        installConnectGetCodecs(
            ConnectGetStrategies(
                proto = GoogleJavaProtobufStrategy(),
                json = GoogleJavaJSONStrategy(typeRegistry),
            ),
        )
        routing {
            install(ContentNegotiation) {
                // POST path: body-based content negotiation.
                connectJson(typeRegistry)
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

## Verifying release artifacts

Releases for the `protoc-gen-connect-ktor` Go binaries and the `library` JARs publish supply-chain
metadata so consumers can verify provenance and integrity.

### Verify the protoc-gen-connect-ktor archive signature with cosign

Each release archive ships with a sigstore bundle (`*.sigstore.json`) produced by
[cosign](https://github.com/sigstore/cosign) keyless signing via GitHub OIDC. The bundle
embeds the signature, certificate, and transparency-log inclusion proof:

```sh
cosign verify-blob \
  --new-bundle-format \
  --bundle connect-ktor_Linux_x86_64.tar.gz.sigstore.json \
  --certificate-identity-regexp "^https://github.com/ichizero/connect-ktor/\\.github/workflows/release\\.yml@refs/tags/v.*$" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  connect-ktor_Linux_x86_64.tar.gz
```

### Verify SLSA build provenance

Every release archive (Go binaries and library JARs) is published with a SLSA build provenance
attestation. Verify it with the GitHub CLI:

```sh
gh attestation verify connect-ktor_Linux_x86_64.tar.gz \
  --repo ichizero/connect-ktor
```

## License

Offered under the [Apache 2 license](https://github.com/ichizero/connect-ktor/blob/main/LICENSE).

## Acknowledgements

I'm very grateful for the [Connect Protocol](https://github.com/connectrpc/connect-go),
[Connect-Kotlin](https://github.com/connectrpc/connect-kotlin) and its authors.
Their pioneering work and contributions to the open-source community made the development of Connect-Ktor possible.

I encourage you to try Connect-Ktor and experience a new level of communication with your Ktor server!
