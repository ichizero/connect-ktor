---
packages:
  connect-ktor: minor
---

## Configure streaming receive limits on generated routes

Generated service registration functions now accept a `maxMessageSize` argument
for all streaming request messages. Existing registrations keep the 4 MiB default.
Regenerate your service to use the overload; unary request limits are unchanged.
