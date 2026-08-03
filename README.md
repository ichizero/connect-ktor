# Connect-Ktor

Documentation: https://ichizero.github.io/connect-ktor/

[![Maven Central Version](https://img.shields.io/maven-central/v/io.github.ichizero/connect-ktor)](https://central.sonatype.com/artifact/io.github.ichizero/connect-ktor)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![CI](https://github.com/ichizero/connect-ktor/actions/workflows/ci.yml/badge.svg)
![CodeRabbit Pull Request Reviews](https://img.shields.io/coderabbit/prs/github/ichizero/connect-ktor?utm_source=oss&utm_medium=github&utm_campaign=ichizero%2Fconnect-ktor&labelColor=171717&color=FF570A&link=https%3A%2F%2Fcoderabbit.ai&label=CodeRabbit+Reviews)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/ichizero/connect-ktor/badge)](https://scorecard.dev/viewer/?uri=github.com/ichizero/connect-ktor)

Connect-Ktor extends [Connect-Kotlin](https://github.com/connectrpc/connect-kotlin)
for [Ktor](https://github.com/ktorio/ktor) servers so you can introduce the
[Connect Protocol](https://connectrpc.com/docs/protocol) beside existing REST
routes.

It is an **unofficial** community library (not published by the ConnectRPC
organization). For Connect itself and Connect-Kotlin clients, prefer the official
[connectrpc.com](https://connectrpc.com/) docs.

## Features

- **connect-ktor** — Protobuf JSON/binary codecs via Connect-Kotlin, client-streaming
  RPCs with envelope framing, optional [protovalidate](https://github.com/bufbuild/protovalidate)
- **protoc-gen-connect-ktor** — generates Ktor route handler interfaces (unary +
  client-streaming `Flow<Req>`)

Plugins (Connect GET, body limits, compression guards, and more), engine notes,
and longer guides live in the [documentation site](https://ichizero.github.io/connect-ktor/).

## Conformance

Connect-Ktor runs the official
[connectrpc/conformance](https://github.com/connectrpc/conformance) suite.
Summary (details and footnotes:
[docs](https://ichizero.github.io/connect-ktor/conformance/)):

| Feature               | Option                       | CIO  | Netty |
| --------------------- | ---------------------------- | :--: | :---: |
| Protocol              | Connect                      |  ✅  |  ✅   |
|                       | gRPC / gRPC-Web              |  ❌  |  ❌   |
| HTTP                  | 1.1                          |  ✅  |  ✅   |
|                       | 2                            |  ❌  |  ✅   |
|                       | 3                            |  ❌  |  ❌   |
| Codec                 | Proto / JSON                 |  ✅  |  ✅   |
| Compression           | identity / gzip              |  ✅  |  ✅   |
|                       | br / zstd / deflate / snappy |  ❌  |  ❌   |
| Streams               | unary / client-stream        |  ✅  |  ✅   |
|                       | server / bidi                |  ❌  |  ❌   |
| TLS / mTLS            |                              |  ❌  |  ✅   |
| Trailers              |                              |  ✅  |  ✅   |
| Connect GET           |                              | ✅\* |  ✅   |
| Message receive limit | unary                        |  ✅  |  ✅   |

\* CIO Connect GET fails cases that rely on duplicate request headers (upstream
CIO limitation). Both engines currently fail gzip client-stream cases (per-message
streaming compression unimplemented).

```bash
mise run conformance
```

## Quick start

```kotlin
dependencies {
    implementation("io.github.ichizero:connect-ktor:0.3.0")
}
```

Install the generator (`brew install ichizero/tap/protoc-gen-connect-ktor` or a
[release](https://github.com/ichizero/connect-ktor/releases) binary), then generate:

```yaml
# buf.gen.yaml (excerpt)
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

```bash
buf generate
```

Implement the generated handler and register it beside REST:

```kotlin
object ElizaServiceHandler : ElizaServiceHandlerInterface {
    override suspend fun say(
        request: SayRequest,
        call: ApplicationCall,
    ): ResponseMessage<SayResponse> = ResponseMessage.Success(
        sayResponse { sentence = request.sentence },
        emptyMap(),
        emptyMap(),
    )
}

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(Resources)
        routing {
            get("/health") { call.respondText("ok") }
            install(ContentNegotiation) {
                connectJson()
            }
            elizaService(ElizaServiceHandler)
        }
    }.start(wait = true)
}
```

Full walkthrough: [Getting started](https://ichizero.github.io/connect-ktor/getting-started/).

## Local development

This repository uses [mise](https://mise.jdx.dev/) for tooling and tasks, plus
[lefthook](https://github.com/evilmartians/lefthook) for pre-commit hooks.

```bash
mise install    # tools + lefthook install (mise postinstall)
mise tasks ls   # build, test, lint, generate, conformance, setup, …
```

Docs site and formatting from the repo root (`docs-site` is an alias for
`pnpm --filter=docs-site`):

```bash
pnpm install
pnpm docs-site dev      # local preview
pnpm docs-site build    # static export
pnpm docs-site lint     # typecheck (lint → lint:type)
pnpm lint:format        # format check (oxfmt --check)
pnpm lint-fix          # write-format (lint-fix → lint-fix:format)
```

## Verifying release artifacts

Release archives for `protoc-gen-connect-ktor` ship cosign sigstore bundles and
SLSA provenance. See the release notes and:

```sh
cosign verify-blob \
  --new-bundle-format \
  --bundle connect-ktor_Linux_x86_64.tar.gz.sigstore.json \
  --certificate-identity-regexp "^https://github.com/ichizero/connect-ktor/\\.github/workflows/release\\.yml@(refs/heads/main|refs/tags/v.*)$" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  connect-ktor_Linux_x86_64.tar.gz

gh attestation verify connect-ktor_Linux_x86_64.tar.gz --repo ichizero/connect-ktor
```

## License

Offered under the [Apache 2 license](https://github.com/ichizero/connect-ktor/blob/main/LICENSE).

## Acknowledgements

Thanks to the authors of the [Connect Protocol](https://github.com/connectrpc/connect-go)
and [Connect-Kotlin](https://github.com/connectrpc/connect-kotlin).
