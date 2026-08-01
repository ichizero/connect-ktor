# Contributing to Connect-Ktor

Thanks for contributing. This guide covers the local workflow for the Kotlin/Gradle
library, the Go code generator, and the docs site.

## Prerequisites

- [mise](https://mise.jdx.dev/) (tool versions and tasks)
- JDK 21 (Gradle toolchain downloads it if needed)
- [pnpm](https://pnpm.io/) 11.x (docs site / Tegami)
- Go (for `protoc-gen-connect-ktor`; version pinned in `mise.toml`)

```bash
mise install   # installs tools and runs lefthook install via postinstall
pnpm install
```

If hooks are missing after a worktree create (or you skipped install), run
`mise run setup` (or `mise exec -- lefthook install`).

## Development

Useful mise tasks (`mise tasks ls` for the full list):

| Task                   | Purpose                            |
| ---------------------- | ---------------------------------- |
| `mise run build`       | Build library + Go plugin          |
| `mise run test`        | Generate + run Gradle / Go tests   |
| `mise run lint`        | Spotless, detekt, Go vet           |
| `mise run generate`    | Buf / protobuf codegen             |
| `mise run conformance` | Official Connect conformance suite |

Docs site (from repo root):

```bash
pnpm docs-site dev      # http://localhost:3000
pnpm docs-site build
pnpm docs-site lint
pnpm lint:format        # oxfmt check
pnpm lint-fix          # oxfmt write
```

Pre-commit hooks (lefthook) run Spotless/detekt, golangci-lint, and oxfmt (write-format
on staged JS/TS/Markdown/YAML/JSON/CSS/etc.). `mise install` wires hooks via the
`postinstall` hook (`mise run setup` is the same command). Skip locally with
`LEFTHOOK=0 git commit` if needed; CI still enforces the same checks.

## Changelog entries (required for releasable changes)

This repository uses [Tegami](https://tegami.fuma-nama.dev) for versioning. When a PR
changes a published package (`connect-ktor` / `protoc-gen-connect-ktor`), add a pending
changelog entry under `.tegami/` **before opening or updating the PR**.

CI (`tegami-pr`) posts a release preview comment (see [PR release preview](#pr-release-preview)).
Merges to `main` run `pnpm tegami ci`, which bumps `VERSION`, writes docs changelog MDX
under `apps/docs-site/changelog/`, and opens/updates the version PR. GitHub Releases
remain tag-driven (GoReleaser).

### Create an entry

Interactive (recommended):

```bash
pnpm tegami
```

Or create `.tegami/YYYY-MM-DD-<id>.md` by hand (see
[changelog format](https://tegami.fuma-nama.dev/changelog)):

```md
---
packages:
    connect-ktor: minor
---

## Support example feature

Short, user-facing description of what changed and why it matters.

[PR #123](https://github.com/ichizero/connect-ktor/pull/123)
```

Rules:

- YAML frontmatter must include `packages` with an explicit bump:
  `major` | `minor` | `patch` (this repo uses **explicit** style; see
  `scripts/tegami.mts`)
- Body is **freeform Markdown**. Do **not** add Changesets-style category headings
  such as `## Features` / `## Fixes`
- Convention for each change:
    1. `##` (h2) title
    2. Brief user-facing description
    3. PR reference: `PR #123` or `[PR #123](https://github.com/ichizero/connect-ktor/pull/123)`
- Body needs at least one `#` / `##` / `###` heading (Tegami requirement)
- Write notes for end users (not internal refactor chatter)
- Package name is `connect-ktor` (Gradle package)
- Do not edit `VERSION`, `.tegami/publish-lock.yaml`, or published
  `apps/docs-site/changelog/*.mdx` for routine PR work
- Maven coordinate pins in `README.md` and
  `apps/docs-site/content/getting-started.mdx`
  (`io.github.ichizero:connect-ktor:<version>`) are rewritten automatically
  when Tegami applies a version bump; do not hand-edit them for releases

Frontmatter carries only **semver bump** intent (`major` | `minor` | `patch`), plus
optional `replay` for re-emitting notes. Tegami does not group entries under
Features / Fixes; headings in the body are content, not a typed taxonomy.

Skip a Tegami entry only for docs-only, CI-only, or otherwise non-releasable changes.

### Dependency-only releases

Dependabot bumps that should ship as a patch release still need a Tegami entry.
Create `.tegami/YYYY-MM-DD-deps.md` with `packages.connect-ktor: patch` and a
`## Dependencies` body. Keep the bump text in GitHub Release note style, but
shorten PR refs to `[PR #N](...)`:

```md
---
packages:
    connect-ktor: patch
---

## Dependencies

- chore(deps): Bump ktor from 3.5.0 to 3.5.1 by @dependabot[bot] in [PR #231](https://github.com/ichizero/connect-ktor/pull/231)
```

Generate the bullets (since the latest `v*` tag by default):

```bash
pnpm tegami:deps-summary
# or
./scripts/tegami-deps-summary.sh
./scripts/tegami-deps-summary.sh --since v0.2.0
./scripts/tegami-deps-summary.sh --from-release v0.1.10   # extract from a past release
```

Paste the script output into the Tegami entry, then open the usual version PR
path (`pnpm tegami` / CI). Do not hand-edit published
`apps/docs-site/changelog/*.mdx` for routine deps work.

### PR release preview

On each `pull_request` (opened / synchronize / reopened), `.github/workflows/tegami-pr.yml`
runs the official Tegami CLI commands:

1. `pnpm tegami pr preview --artifact tegami-pr-preview.md`
2. `pnpm tegami pr comment tegami-pr-preview.md`

Official docs suggest splitting generate vs comment into two workflows via
`workflow_run`. This repo posts the comment in the same `pull_request` job instead:
`workflow_run` was removed after zizmor flagged it, while still using the same
`--artifact` + `pr comment` CLI surface. The job stays on `pull_request` (not
`pull_request_target`) with `contents: read` plus `pull-requests: write` only for
commenting. See [Tegami CI / PR preview](https://tegami.fuma-nama.dev/ci#pull-request-preview).

## Commit and PR titles

Follow [Conventional Commits](https://www.conventionalcommits.org/) for both:

- Git commit subjects (`feat:`, `fix:`, `docs:`, `chore:`, …)
- Pull request titles (same style; with squash-merge the PR title becomes the `main` commit)

Examples: `feat: support Connect GET`, `fix(library): reject unsupported GET encodings`.

## Pull requests

1. Fork / branch from `main`
2. Make the change + tests
3. Add a `.tegami/` entry when the change is releasable
4. Open a PR with a Conventional Commits-style title plus the template body
5. Ensure CI is green (`ci`, docs-site, tegami-pr preview)

## Security

Do not open public issues for vulnerabilities. Follow [SECURITY.md](./SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](./LICENSE).
