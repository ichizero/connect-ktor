---
packages:
  connect-ktor: minor
---

## Configure streaming receive limits with a route-scoped plugin

Install `ConnectStreaming` on a Ktor route to configure `maxMessageSize` for
client, server, and bidirectional streaming request messages. Child routes can
override the setting; explicit handler limits take precedence. Without the plugin,
the limit remains 4 MiB. Unary request limits and generated source are unchanged.
Recompile existing generated source against the updated library to use the plugin.
