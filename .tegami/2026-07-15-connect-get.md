---
packages:
    connect-ktor: minor
---

## Support Connect GET for idempotent unary RPCs

Idempotent unary RPCs (protobuf `NO_SIDE_EFFECTS`) can now be served over HTTP GET
with the Connect query-parameter encoding. The code generator emits GET routes for
eligible methods, and custom JSON strategies (for example TypeRegistry for
`google.protobuf.Any`) can be registered via `installConnectGetCodecs`.

[PR #194](https://github.com/ichizero/connect-ktor/pull/194)
