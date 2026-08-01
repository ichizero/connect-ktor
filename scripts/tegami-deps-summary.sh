#!/usr/bin/env bash
# Summarize Dependabot/Renovate dependency bumps in GitHub Release note style.
#
# Output keeps the bump text from release notes, with PR refs shortened like
# feature changelog entries, e.g.:
#   ## Dependencies
#
#   * chore(deps): Bump ktor from 3.5.0 to 3.5.1 by @dependabot[bot] in [PR #231](https://github.com/ichizero/connect-ktor/pull/231)
#
# Paste under a Tegami entry body, or use --backfill to update published changelog MDX.
#
# Usage:
#   ./scripts/tegami-deps-summary.sh
#   ./scripts/tegami-deps-summary.sh --since v0.2.0
#   ./scripts/tegami-deps-summary.sh --since v0.1.10 --until v0.1.11
#   ./scripts/tegami-deps-summary.sh --from-release v0.1.10
#   ./scripts/tegami-deps-summary.sh --backfill
#   pnpm tegami:deps-summary
set -euo pipefail

REPO="${TEGAMI_DEPS_REPO:-ichizero/connect-ktor}"
CHANGELOG_DIR="${TEGAMI_DEPS_CHANGELOG_DIR:-apps/docs-site/changelog}"
PACKAGE_NAME="${TEGAMI_DEPS_PACKAGE:-connect-ktor}"

SINCE=""
UNTIL="HEAD"
FROM_RELEASE=""
BACKFILL=0
HEADING="## Dependencies"

usage() {
  cat <<'EOF'
Usage:
  tegami-deps-summary.sh [--since TAG] [--until REF]
  tegami-deps-summary.sh --from-release TAG
  tegami-deps-summary.sh --backfill

Options:
  --since TAG         Start tag (exclusive). Default: latest git tag matching v*.
  --until REF         End ref (inclusive for tags / tip). Default: HEAD.
  --from-release TAG  Print deps bullets from that GitHub Release body only.
  --backfill          For every v* release, insert/update ## Dependencies in
                      apps/docs-site/changelog/*.mdx (create missing deps-only files).
  -h, --help          Show this help.

Environment:
  TEGAMI_DEPS_REPO            GitHub repo (default: ichizero/connect-ktor)
  TEGAMI_DEPS_CHANGELOG_DIR   Changelog MDX dir (default: apps/docs-site/changelog)
  TEGAMI_DEPS_PACKAGE         Package name in frontmatter (default: connect-ktor)
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

latest_tag() {
  git tag -l 'v*' --sort=-v:refname | head -n1
}

# Shorten bare GitHub PR URLs to [PR #N](url), matching feature changelog style.
# Idempotent for lines that already use the short form.
shorten_pr_ref() {
  local line="$1"
  if [[ "$line" =~ in\ \[PR\ #[0-9]+\]\( ]]; then
    printf '%s\n' "$line"
    return
  fi
  printf '%s\n' "$line" | sed -E 's| in (https://github\.com/[^/]+/[^/]+/pull/([0-9]+))| in [PR #\2](\1)|'
}

normalize_deps_line() {
  # Accept "* ..." or "- ..." from release notes / PR titles; emit "* ...".
  local line="$1"
  line="${line//$'\r'/}"
  line="${line#"${line%%[![:space:]]*}"}"
  case "$line" in
    '*'*) line="$line" ;;
    '-'*) line="* ${line#- }" ;;
    *) line="* $line" ;;
  esac
  shorten_pr_ref "$line"
}

is_deps_line() {
  local line="$1"
  [[ "$line" =~ ^[[:space:]]*[\*\-][[:space:]]+chore\(deps\): ]] ||
    [[ "$line" =~ ^[[:space:]]*[\*\-][[:space:]]+.*[Bb]ump[[:space:]] ]] ||
    [[ "$line" =~ ^chore\(deps\): ]] ||
    [[ "$line" =~ ^[Bb]ump[[:space:]] ]]
}

extract_deps_from_release_body() {
  local body="$1"
  printf '%s\n' "$body" | while IFS= read -r line || [[ -n "$line" ]]; do
    if is_deps_line "$line"; then
      # Skip dependabot "first contribution" noise.
      if [[ "$line" =~ made\ their\ first\ contribution ]]; then
        continue
      fi
      normalize_deps_line "$line"
    fi
  done
}

print_section() {
  local lines="$1"
  if [[ -z "${lines//[$'\n']/}" ]]; then
    printf '(no dependency bumps found)\n' >&2
    return 1
  fi
  printf '%s\n\n' "$HEADING"
  printf '%s\n' "$lines"
}

deps_from_release() {
  local tag="$1"
  local body
  body="$(gh release view "$tag" --repo "$REPO" --json body -q .body)"
  extract_deps_from_release_body "$body"
}

# ISO-8601 timestamp for a ref (UTC Z), suitable for mergedAt comparisons.
ref_timestamp_utc() {
  local ref="$1"
  # %cI may be offset-local; normalize via git then python/date if needed.
  local raw
  raw="$(git log -1 --format=%cI "$ref" 2>/dev/null || true)"
  [[ -n "$raw" ]] || die "cannot resolve timestamp for ref: $ref"
  # Prefer python for portable timezone normalization.
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$raw" <<'PY'
import sys
from datetime import datetime, timezone
raw = sys.argv[1]
dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
print(dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
  else
    printf '%s\n' "$raw"
  fi
}

# List merged Dependabot/Renovate PRs with mergedAt in (since, until].
# Uses gh pr list (fast) instead of per-commit API calls.
deps_from_git_range() {
  local since="$1"
  local until="$2"
  local since_ts until_ts

  since_ts="$(ref_timestamp_utc "$since")"
  if [[ "$until" == "HEAD" ]] || ! git rev-parse -q --verify "refs/tags/${until}" >/dev/null 2>&1; then
    # Open-ended upper bound for working tree tip.
    until_ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  else
    until_ts="$(ref_timestamp_utc "$until")"
  fi

  require_cmd jq

  local json
  json="$(
    {
      gh pr list --repo "$REPO" --state merged --author 'app/dependabot' --limit 200 \
        --json number,title,url,mergedAt,author
      gh pr list --repo "$REPO" --state merged --author 'app/renovate' --limit 50 \
        --json number,title,url,mergedAt,author 2>/dev/null || printf '[]\n'
    } | jq -s 'add | unique_by(.number) | sort_by(.mergedAt)'
  )"

  printf '%s\n' "$json" | jq -r --arg since "$since_ts" --arg until "$until_ts" '
    def ts: sub("\\.[0-9]+Z$"; "Z");
    .[]
    | select(.mergedAt != null)
    | select((.mergedAt | ts) > ($since | ts))
    | select((.mergedAt | ts) <= ($until | ts))
    | select(.title | test("chore\\(deps\\)|(?i)bump "))
    | . as $pr
    | ($pr.author.login // "dependabot") as $login
    | (if ($login | test("renovate")) then "renovate[bot]"
       elif ($login | test("dependabot")) then "dependabot[bot]"
       else $login end) as $label
    | "* \($pr.title) by @\($label) in [PR #\($pr.number)](\($pr.url))"
  '
}

strip_deps_section() {
  # Remove an existing ## / ### Dependencies section (until next heading or EOF).
  awk '
    BEGIN { skip=0 }
    /^#{2,3} Dependencies[[:space:]]*$/ { skip=1; next }
    /^#{1,3}[[:space:]]/ && skip { skip=0 }
    !skip { print }
  '
}

version_from_tag() {
  local tag="$1"
  printf '%s\n' "${tag#v}"
}

find_existing_mdx_for_version() {
  local version="$1"
  local f
  for f in "$CHANGELOG_DIR"/*-v"${version}".mdx "$CHANGELOG_DIR"/*-"${version}".mdx; do
    if [[ -f "$f" ]]; then
      printf '%s\n' "$f"
      return 0
    fi
  done
  return 1
}

append_deps_to_mdx() {
  local file="$1"
  local deps_body="$2"
  local tmp
  tmp="$(mktemp)"
  strip_deps_section <"$file" | awk '
    { lines[NR]=$0 }
    END {
      # trim trailing blank lines
      end=NR
      while (end > 0 && lines[end] ~ /^[[:space:]]*$/) end--
      for (i=1; i<=end; i++) print lines[i]
    }
  ' >"$tmp"
  {
    cat "$tmp"
    printf '\n%s\n\n' "$HEADING"
    printf '%s\n' "$deps_body"
  } >"$file"
  rm -f "$tmp"
}

create_deps_only_mdx() {
  local tag="$1"
  local deps_body="$2"
  local published version path day iso
  published="$(gh release view "$tag" --repo "$REPO" --json publishedAt -q .publishedAt)"
  version="$(version_from_tag "$tag")"
  day="${published%%T*}"
  # Normalize to .000Z like existing files when seconds-only.
  if [[ "$published" =~ \.[0-9]+Z$ ]]; then
    iso="$published"
  else
    iso="${published/Z/.000Z}"
  fi
  path="${CHANGELOG_DIR}/${day}-${tag}.mdx"
  mkdir -p "$CHANGELOG_DIR"
  cat >"$path" <<EOF
---
title: Dependency updates
date: ${iso}
packages:
    ${PACKAGE_NAME}:
        version: ${version}
---

${HEADING}

${deps_body}
EOF
  printf 'created %s\n' "$path" >&2
}

backfill_all() {
  require_cmd gh
  require_cmd git
  mkdir -p "$CHANGELOG_DIR"

  local tag version deps_body path
  local tags
  tags="$(git tag -l 'v*' --sort=v:refname)"
  [[ -n "$tags" ]] || die "no v* tags found"

  while IFS= read -r tag; do
    [[ -n "$tag" ]] || continue
    version="$(version_from_tag "$tag")"
    deps_body="$(deps_from_release "$tag" || true)"
    if [[ -z "${deps_body//[$'\n']/}" ]]; then
      printf 'skip %s (no deps in release notes)\n' "$tag" >&2
      continue
    fi

    if path="$(find_existing_mdx_for_version "$version")"; then
      append_deps_to_mdx "$path" "$deps_body"
      printf 'updated %s\n' "$path" >&2
    else
      create_deps_only_mdx "$tag" "$deps_body"
    fi
  done <<<"$tags"
}

# --- args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --since)
      SINCE="${2:-}"
      [[ -n "$SINCE" ]] || die "--since requires a tag"
      shift 2
      ;;
    --until)
      UNTIL="${2:-}"
      [[ -n "$UNTIL" ]] || die "--until requires a ref"
      shift 2
      ;;
    --from-release)
      FROM_RELEASE="${2:-}"
      [[ -n "$FROM_RELEASE" ]] || die "--from-release requires a tag"
      shift 2
      ;;
    --backfill)
      BACKFILL=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

require_cmd git
require_cmd gh

if [[ "$BACKFILL" -eq 1 ]]; then
  backfill_all
  exit 0
fi

if [[ -n "$FROM_RELEASE" ]]; then
  lines="$(deps_from_release "$FROM_RELEASE")"
  print_section "$lines"
  exit 0
fi

if [[ -z "$SINCE" ]]; then
  SINCE="$(latest_tag)"
  [[ -n "$SINCE" ]] || die "no tags found; pass --since TAG"
fi

lines="$(deps_from_git_range "$SINCE" "$UNTIL")"
print_section "$lines"
