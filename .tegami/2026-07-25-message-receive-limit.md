---
packages:
    connect-ktor: minor
---

## Support message receive size limits

`Route.connectBodyLimit(maxBytes)` enforces a maximum inbound message size for
unary Connect RPCs and returns `resource_exhausted` when the limit is exceeded.
Compressed requests are measured after decompression so gzip bombs cannot bypass
the cap.

[PR #195](https://github.com/ichizero/connect-ktor/pull/195)
