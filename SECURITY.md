# Security Policy

## Supported Versions

Only the latest release of connect-ktor is supported. When a vulnerability
is fixed, the fix ships in the next patch release; older releases are not
back-patched. Users are expected to upgrade to the latest release to pick
up security fixes.

| Version         | Supported          |
| --------------- | ------------------ |
| Latest release  | :white_check_mark: |
| Older releases  | :x:                |

## Reporting a Vulnerability

Please report suspected vulnerabilities **privately** via GitHub's
[Private Vulnerability Reporting](https://github.com/ichizero/connect-ktor/security/advisories/new)
on this repository. Do **not** open a public issue, pull request, or
discussion for security-sensitive reports.

When filing a report, include as much of the following as you can:

- A description of the issue and its potential impact.
- The affected version(s) and configuration (Ktor engine, JDK, OS).
- Reproduction steps or a proof-of-concept.
- Any known mitigations or workarounds.

## Disclosure Process and Timelines

connect-ktor is maintained on a best-effort basis by a single
maintainer. The targets below reflect that and may slip for reports
that arrive during travel or other extended outages.

- **Acknowledgement: within 7 days** (best effort) of receiving the
  report.
- **Status updates: at least every 30 days** while the report is open.
- **Disclosure: within 90 days** of the initial report. We will work
  with the reporter to release a fix and publish a GitHub Security
  Advisory before this deadline whenever possible. If a fix is not
  feasible within this window, we will coordinate an extension with
  the reporter.

Credit will be given to reporters in the published advisory unless they
prefer to remain anonymous.
