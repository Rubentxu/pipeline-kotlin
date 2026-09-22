# WU-RP-010 — PublishHTML E2E coverage of UAT-RP-005 (round 1, test-only)

**Date:** 2026-09-22T06:56Z. **Status:** PASS_GREEN_LOCAL (round 1). **Base SHA:** e95b3d41. **Head SHA:** this commit (TBD; pending push + CI). **Branch:** main.

## What

Added a new test class `PublishHtmlOperationsAdapterUatTest` in `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/` with **4 E2E tests** that exercise the production `PublishHtmlOperationsAdapter.publish(input)` end-to-end. None of the existing `CorePublishHtmlStepContractSuiteTest` tests (13) covered the handler — they exercise only the StepContract surface (key, descriptor, codecs, capabilities, registry). This is the first adapter-level coverage for the `core.publishHTML` step.

## Why

`PRODUCTION_READY_UAT_MATRIX.md` UAT-RP-005 mandates four invariants for the publishHTML step:
1. The user-provided `index.html` in the workspace's reportDir must NOT be overwritten.
2. The generated index.html must not collide (with the user's index.html in the archive, or with malformed filenames).
3. The hash of the FINAL entries must be archived and the manifest must be intact.
4. Replay must produce identical fingerprints across two consecutive `publish(input)` calls.

The orchestrator's WU-RP-010 characterization report (`/tmp/wu-rp-010-report.md`, on disk 2026-09-22T06:55Z) classified:
- Invariant 1: PASS_UNPROVEN → now PASS_PROVEN (test 1).
- Invariant 2: UNTRIAGED → partially PASS_PROVEN (tests 2 + 3 cover determinism; HTML-special-char escape remains for WU-RP-011).
- Invariant 3: FAIL_PROVEN (no manifest file written by the adapter) → still FAIL_PROVEN. Resolution requires production-code change to add `MANIFEST.json`. **Scheduled for a follow-up WU pending operator decision** (the archive layout is a security boundary per AGENTS.md §5 and a public API per STEP_PLUGIN_CERTIFICATION.md C16).
- Invariant 4: PASS_UNPROVEN → now PASS_PROVEN (tests 2 + 3).

The 4 invariants now decompose into:
- 3 invariants covered by test-only additions (this WU).
- 1 invariant requiring production-code change (deferred; operator approval required to add `MANIFEST.json` to the archive layout).

## What changed

Single new test file:

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/PublishHtmlOperationsAdapterUatTest.kt`

Tests added (all 4 PASS locally in 0.055 s):
1. `user-provided index html in workspace reportDir is NOT overwritten` — UAT-RP-005 row 1. Pre-creates `<workspace>/<reportDir>/index.html` with sentinel bytes; invokes `publish`; asserts workspace file is byte-identical after publish and that other workspace files (`report.html`) still exist.
2. `replay produces identical archive index html fingerprint` — UAT-RP-005 row 2 (determinism). Invokes `publish(input)` twice on the same controlDirRoot/workspace; asserts SHA256 of `<archiveRoot>/index.html` is identical both times AND that the target path is identical.
3. `replay per-entry sha256 reported in event is identical across publishes` — UAT-RP-005 row 4 (replay stability). Two consecutive `publish` calls; asserts every `HtmlReportEntry.{relPath, sha256, sizeBytes}` matches in both calls (sorted by relPath for stable comparison).
4. `per-entry sha256 matches sha256 of final bytes in archive` — UAT-RP-005 row 3 (hash of FINAL content). Single `publish`; for every entry, recomputes SHA256 from disk and asserts it equals the value in the `HtmlReportPublished` event.

## What did NOT change

- Zero production code (`PublishHtmlOperationsAdapter.kt`, `Capabilities.kt`, `PublishHtmlSanitiser.kt`, `Main.kt`).
- Zero other test files.
- No build files, no workflow YAML, no Gradle properties, no branch protection, no settings.
- No receipts modified. All historical receipts remain immutable.
- No archive layout change. No new files written to the archive by production.
- No release, no SDKMAN publish.

## Verification (per AGENTS.md rule 23: result truth is the JUnit XML, not exit code)

| Level | Command | Exit | XML aggregate | Time | Note |
|---|---|---|---|---|---|
| L0 compile | `cd v2 && timeout 600 ./gradlew :pipeline-application:compileTestKotlin --no-daemon --quiet` | 0 | — | 18 s | Background task 148210a17d. |
| L1 new test class | `cd v2 && timeout 300 ./gradlew :pipeline-application:test --tests 'PublishHtmlOperationsAdapterUatTest' --no-daemon` | 0 | 4 tests, 0 failures, 0 errors | 0.13 s | Background task 171627an5h. XML `TEST-dev.rubentxu.pipeline.v2.application.PublishHtmlOperationsAdapterUatTest.xml` timestamp 2026-09-22T06:56:24.579Z. |
| L2 sibling regression | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'PublishHtmlOperationsAdapterUatTest' --tests 'CorePublishHtmlStepContractSuiteTest' --tests 'Lpr011SecretRedactionTranscriptUatTest' --tests 'Lpr011r2SecretRedactionAtRestUatTest' --no-daemon` | 0 | 34 tests, 0 failures, 0 errors | 1m 6s | Background task 201552wa75. New class runs in 55 ms; zero impact on existing families. |

## Residual risk

None for round 1. The single open residual is the **archive MANIFEST.json requirement** (UAT-RP-005 row 3 partial). It is an open item, not a regression: production behaves exactly as it did before round 1; round 1 only added evidence of WHAT production does. The orchestrator has classified this as `NEEDS_FIX` and flagged it for operator decision per AGENTS.md §5 (security boundary).

## Follow-up

1. CI verification of this commit via `gh run list --limit 1` after `git push origin main`.
2. SESSION_POINTER + WORK_JOURNAL updated (already in `.agent/`).
3. **Operator decision** on whether to open WU-RP-010 round 2 to add `MANIFEST.json` to the archive (production-code change; archive layout; AGENTS.md §5 ADR trigger).
4. After round-2 sign-off, proceed to WU-RP-011 (HTML injection coverage of `buildIndexHtml(relPath)`); WU-RP-012 (stash symlink safety — independent of publishHTML); WU-RP-013 (StepContractSuite G7 reconciliation for `core.publishHTML`).