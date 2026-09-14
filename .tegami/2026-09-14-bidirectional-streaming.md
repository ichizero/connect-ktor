---
packages:
  connect-ktor: minor
---

## Support Connect bidirectional-streaming RPCs

Connect-Ktor now generates and serves bidirectional-streaming RPCs. Half-duplex calls
work on CIO and Netty, while Netty over HTTP/2 supports full-duplex request and response
interleaving.

Generated handlers accept a `Flow<Req>` and return a `Flow<Res>`. The streaming path
handles envelope framing, request validation, per-message size limits, headers, trailers,
deadlines, and cancellation.

[PR #348](https://github.com/ichizero/connect-ktor/pull/348)
