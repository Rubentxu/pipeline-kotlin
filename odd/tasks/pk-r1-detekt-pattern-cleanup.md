# PK-R1 — detekt PATTERN cleanup (MatchingDeclarationName)

## Goal

Close the 3 deferred PATTERN-class detekt issues identified in
`docs/v2/07-uat/evidence/v0.51.0-rc2/detekt-triage.md`. The rule is
`MatchingDeclarationName`: file name does not match the primary class declared in
it. Resolution by rename only — no rule suppression, no `// noinspection`, no
`build.gradle.kts` detekt excludes, no `detekt.yml` edits.

## Why

The PK-R1 detekt triage (`docs/v2/07-uat/evidence/v0.51.0-rc2/detekt-triage.md`)
landed 30 INHIBIT/QUICK-FIX commits and deferred 3 `MatchingDeclarationName`
findings as PATTERN — meaning the fix direction needed a single decision rather
than per-file churn. The decision taken here: rename the FILE to match the
CLASS (preserves external callers, keeps the published class surface stable).

## Where

- `v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/EventRecordReadPortAdapters.kt` → `EventRecordReadPortStoreAdapter.kt`
- `v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/control/CancelAdapters.kt` → `RuntimeControlPortStoreAdapter.kt`
- `v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/IntrospectionAdapters.kt` → `RuntimeIntrospectionPortStoreAdapter.kt`

## Scope

Rename 3 files. No class rename, no caller update, no public API change. No new
tests. One work-unit commit per rename.

## Constraints

- No `// noinspection`, no `@Suppress` for this rule, no `detekt.yml` edits.
- Pure `git mv`. No `Edit` calls on the renamed file content.
- `:pipeline-application:test` and project-wide `:detekt` must remain green
  after each commit and at the end.

## Actionable checklist

- [ ] T1 — Rename `EventRecordReadPortAdapters.kt` → `EventRecordReadPortStoreAdapter.kt`. Verify `:pipeline-events-store:detekt` shows 0 findings on the renamed file. Commit.
- [ ] T2 — Rename `CancelAdapters.kt` → `RuntimeControlPortStoreAdapter.kt`. Verify `:pipeline-runtime:detekt` shows 0 findings on the renamed file. Commit.
- [ ] T3 — Rename `IntrospectionAdapters.kt` → `RuntimeIntrospectionPortStoreAdapter.kt`. Verify `:pipeline-runtime:detekt` shows 0 findings on the renamed file. Commit.
- [ ] T4 — Project-wide `./gradlew detekt --console=plain --no-daemon` shows 0 findings total.
- [ ] T5 — `./gradlew :pipeline-application:test --no-daemon` green with totals reported.

## Acceptance criteria

- All 3 PATTERN rows from `detekt-triage.md` resolved (no `MatchingDeclarationName` on the renamed files).
- 3 work-unit commits on `pk-r1/detekt-pattern-cleanup`.
- Project-wide detekt: 0 findings.
- `:pipeline-application:test`: green.

## Branch

`pk-r1/detekt-pattern-cleanup` (from `main` HEAD).