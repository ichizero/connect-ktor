---
packages:
    connect-ktor: minor
---

## Support Connect server-streaming RPCs

Server-streaming RPCs (`rpc X(Req) returns (stream Res)`) are now served end to end.
The code generator emits handlers that return a cold `Flow<Res>`, and
`handleServerStream` writes each emitted message as an envelope frame followed by an
end-of-stream frame. Response headers are flushed before the first message, a
`ConnectException` thrown by the flow becomes the end-of-stream error, and trailers can
be set through the new `call.connectResponseTrailers()`. When a client disconnects, the
handler's flow collector is cancelled instead of the failure being swallowed.

Services with server-streaming methods in their `.proto` will see a new member on the
generated handler interface — those methods used to be skipped by the generator.

[PR #278](https://github.com/ichizero/connect-ktor/pull/278)
