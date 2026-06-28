<!--
Reference the relevant issue with "Closes #N" on the first line if applicable.
Drop any section that does not apply. Keep tables for two or more rows;
inline a single bullet otherwise.
-->

## Summary

<!--
2–6 sentences. Lead with what changes for users / downstream callers,
then why this change is needed (the problem, the constraint, the
motivating use case). Avoid restating the diff — that goes below.
Mention stacked / dependent PRs at the end.
-->

## What changed

<!--
Bulleted, file-grouped overview. One sub-bullet per non-obvious change.
Prefer code-fenced identifiers (`functionName`, `path/to/file.kt`) so
reviewers can grep. Include behavioural details, not just file names.
-->

-

## Decision points

<!--
For each non-obvious choice in this PR, capture the question that came
up, the decision, and the reason. This is where reviewers learn *why*
the diff looks the way it does, and where future maintainers learn
which branches of the design tree were considered. Skip the table if
nothing was contested.

Example row:
| Why pin actions to commit SHA, not tag? | Tag-based pins can be moved silently | A compromised tag could redirect to a malicious commit; SHA pins are immutable. |
-->

| Question | Decision | Why |
|---|---|---|

## Commits

<!--
One row per commit on the branch, in topological order. Helps reviewers
read the PR commit-by-commit when the diff is large.

Example row:
| `abc1234 fix(api): reject empty Authorization headers` | Closes the auth-bypass path from #456. |
-->

| Commit | Purpose |
|---|---|

## Test plan

<!--
Checklist of verifications. Use [x] for things already done locally and
[ ] for things that only run in CI or post-merge (release tag push,
production deploy, scanner ingestion, etc.). Be specific — "tests pass"
is not a test plan; "./gradlew :library:test passes 60 tests including
new streaming framing coverage" is.
-->

- [ ]
