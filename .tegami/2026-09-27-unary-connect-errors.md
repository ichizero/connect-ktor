---
packages:
  connect-ktor: patch
---

## Align unary Connect errors and validate GET requests

Unary POST and Connect GET now return consistent Connect errors and preserve error metadata. Connect GET also validates decoded requests. Validation failures and unexpected handler exceptions reach the application's Ktor `StatusPages` configuration so applications can choose their responses.

[PR #380](https://github.com/ichizero/connect-ktor/pull/380)
