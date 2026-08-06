#!/usr/bin/env bash
# Extract Tegami-curated release notes for a version from CHANGELOG.md.
#
# Usage:
#   ./scripts/tegami-release-notes.sh [VERSION]
#   ./scripts/tegami-release-notes.sh 0.3.0
#   ./scripts/tegami-release-notes.sh v0.3.0
#
# Prints the body under "## connect-ktor@VERSION" (without that heading).
# Intended for: goreleaser release --release-notes <(./scripts/tegami-release-notes.sh …)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHANGELOG="${TEGAMI_CHANGELOG_PATH:-$ROOT/CHANGELOG.md}"

version="${1:-}"
if [[ -z "$version" ]]; then
  if [[ -f "$ROOT/VERSION" ]]; then
    version="$(tr -d '[:space:]' <"$ROOT/VERSION")"
  else
    echo "usage: $0 <version>" >&2
    exit 2
  fi
fi
version="${version#v}"

if [[ ! -f "$CHANGELOG" ]]; then
  echo "changelog not found: $CHANGELOG" >&2
  exit 1
fi

awk -v ver="$version" '
  BEGIN { heading = "## connect-ktor@" ver }
  $0 == heading { found = 1; next }
  found && /^## / { exit }
  found { print }
  END {
    if (!found) {
      printf "no CHANGELOG.md section for connect-ktor@%s\n", ver > "/dev/stderr"
      exit 1
    }
  }
' "$CHANGELOG"
