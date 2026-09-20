# WU-LPR-077 — UatLocal005CorpusUntouchedTest CP-002 inventory lock-step

**Date**: 2026-09-20
**Status**: CLOSED
**Cycle base**: `214278fa` (WU-LPR-075)
**Branch**: `main`
**Module**: `pipeline-application` (`:pipeline-application:test`)

---

## Trigger

While validating WU-LPR-076 with a wider UAT bundle
(`--tests 'UatLocal*' 'UatDsl*' 'UatEvt*' 'UatStep*' 'UatParallel*' 'UatRetry*' 'UatTimeout*'`),
`UatLocal005CorpusUntouchedTest > CP-002` failed with:

```
AssertionFailedError: expected: <22> but was: <29>
```

CP-002 enforces a frozen-inventory guard for the `v2/compatibility/`
corpus. The previous receipt (WU-LPR-104) bumped the count to 22 after
adding fixture 23-readfile, but the corpus kept growing under the
parallel S2 / E1.1 cycles and now sits at 29 fixtures.

## Diagnosis

CP-001 already enforces byte-identity for the original 4 corpus files
(01, 03, 05, 06 against base commit `5405b7b5`). CP-002 only counted
fixtures — its sole job is to detect new fixture files **not yet
explicitly named** in `newFiles`, since unannounced fixtures would
slip past the byte-identity check.

The assertion was a literal `assertEquals(22, pipelineFiles.size, ...)`
that needed updating to 29 plus the same enumeration update.

(Note: a first attempt at 30 was rolled back after re-validating with
`ls v2/compatibility/*.pipeline.kts | wc -l` → 29. The original "30"
number was a misread.)

## Change

Single file: `UatLocal005CorpusUntouchedTest.kt` (+20 / -7).

- Raised `assertEquals(22, …)` to `assertEquals(29, …)`.
- Extended the `newFiles` enumeration with 24..30 (`24-utilities-roundtrip`,
  `25-yaml-roundtrip`, `26-find-files`, `27-zip-unzip`,
  `28-zip-slip-defense`, `29-mixed-utilities`,
  `30-artifact-query-bridge`).
- Updated KDoc and method name to `CP-002 corpus has exactly 29 valid
  fixture files after WU-LPR-077`.
- Fixed a syntax error introduced during the first patch attempt:
  a `/*.pipeline.kts` literal inside a KDoc broke the comment block
  (Kotlin parser interpreted `*/` mid-block). Replaced with prose
  (`ls v2/compatibility/ then filtering entries that end with
  .pipeline.kts | wc -l`) to avoid the wildcard in a comment.

## Verification

| Command | Outcome | SHA-256 |
|---------|---------|---------|
| `:pipeline-application:test --tests 'UatLocal005CorpusUntouchedTest' --rerun-tasks` | BUILD SUCCESSFUL · `tests=2 failures=0 errors=0` | `lpr077-corpus-untouched-3.log` |

## End-of-work-unit closure

```text
Reference implementation consulted: n/a — count-tracking assertion only.
Behaviour adopted: keep CP-002 inventory lock-step with `Files.list(...)`
  in `v2/compatibility/`. The frozen ML-R7 byte-identity guarantee remains
  enforced by CP-001.
Intentional deviations: none.
Security implications reviewed: n/a.
Tests demonstrating the contract:
  - UatLocal005CorpusUntouchedTest (CP-001 + CP-002 both green)
  - CompatibilityCorpusTest (30/0/0/0 from WU-LPR-075, still green)
```

— Receipt authored by SDDK orchestrator session `session_hare_*`.
