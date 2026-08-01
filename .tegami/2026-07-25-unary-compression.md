---
packages:
    connect-ktor: minor
---

## Support unary compression negotiation

Unary Connect RPCs negotiate request compression through Ktor's Compression plugin.
`UnaryCompressionGuard` validates `Content-Encoding` before the body is read and
rejects unsupported encodings with `Code.UNIMPLEMENTED`.

[PR #193](https://github.com/ichizero/connect-ktor/pull/193)
