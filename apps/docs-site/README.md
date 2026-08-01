# Connect-Ktor documentation site

Fumapress (Fumadocs + Waku) site published to GitHub Pages at
https://ichizero.github.io/connect-ktor/.

## Scripts

From the repository root (pnpm workspace):

```bash
pnpm install
pnpm docs:dev      # local preview (basePath `/`)
pnpm docs:build    # static export to apps/docs-site/dist/public
pnpm docs:types
pnpm fmt           # format repo Markdown/JSON/YAML + docs-site sources
```

Or with mise:

```bash
mise run docs-site:build
```
