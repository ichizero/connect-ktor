# Connect-Ktor documentation site

Fumapress (Fumadocs + Waku) site published to GitHub Pages at
https://ichizero.github.io/connect-ktor/.

## Commands

Root `package.json` aliases `docs-site` → `pnpm --filter=docs-site`.

| Command                | What it does                                           |
| ---------------------- | ------------------------------------------------------ |
| `pnpm docs-site dev`   | Local preview (`basePath` `/`)                         |
| `pnpm docs-site build` | Static export → `apps/docs-site/dist/public`           |
| `pnpm docs-site lint`  | `lint` → `lint:type` (`fumadocs-mdx` + `tsc --noEmit`) |
| `pnpm lint:format`     | Format check (`oxfmt --check`, workspace)              |
| `pnpm lint-fix`        | Write-format (`lint-fix:format` → `oxfmt`, workspace)  |

Equivalent without the alias: `pnpm --filter=docs-site <script>`.

### mise

```bash
mise run docs-site:build
mise run docs-site:lint
mise run fmt            # → pnpm lint-fix:format
mise run lint:format
```
