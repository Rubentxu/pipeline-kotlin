# WU-LPR-076 — UAT-COMPAT-001 corpus inventory + workspace bifurcation

**Date**: 2026-09-20
**Status**: CLOSED
**Cycle base**: `214278fa` (WU-LPR-075)
**Branch**: `main`
**Module**: `pipeline-application` (`:pipeline-application:test`)

---

## Trigger

`UatCompat001CorpusSmokeRunTest` (the smoke runner that drives every
`v2/compatibility/*.pipeline.kts` fixture through the installed binary)
began failing after WU-LPR-075 closed, because the corpus had grown
from 22 to 29 fixtures but two assertions inside the UAT still expected
the old count.

The first run after WU-LPR-075 (`:pipeline-application:test --rerun-tasks`)
stalled for >10 minutes on those two assertions, so we cancelled and
went surgical.

## Diagnosis

Three structural defects in `UatCompat001CorpusSmokeRunTest.kt`:

1. `22 → 29` count mismatch on the fixture discovery assertion
   (`brokenFixtures.size` enumeration) — the runner was launching the
   binary **without** `--workspace`, so all fixtures shared the on-disk
   compatibility directory. Fixtures 25 (yaml roundtrip) and 27
   (zip/unzip) write into `build/utils/...` and were stomping on each
   other's artifacts when executed back-to-back, producing intermittent
   failures with the same XML counters.

2. `28-zip-slip-defense.pipeline.kts` was added in a prior cycle
   (S2/ZS-3) and is a **deliberate failure case**: it must exit 1 with
   the message `Zip Slip detected`. Without an explicit
   `brokenFixtures` registration, the runner treated it as a regression.

3. `10-smoke-e2e.pipeline.kts` uses absolute `/tmp/smoke-repo` paths
   (`sh "cd /tmp && rm -rf smoke-repo"`) and **breaks** when the binary
   is launched with `--workspace` because the first `rm -rf` escapes
   the workspace boundary and corrupts any sibling test's tmp dir.

## Change

Single file: `UatCompat001CorpusSmokeRunTest.kt` (+53 / -11).

- Raised the fixture count assertion from `22` to `29` (two locations:
   the bulk-runner and the per-fixture list).
- Added `28-zip-slip-defense.pipeline.kts` to `brokenFixtures` (verified
   manually with `--workspace /tmp/uat28-inspect` → exit 1 + "Zip Slip
   detected").
- Introduced `@TempDir` parameter on both `@Test` methods.
- Added `stageFixture(name, workspace)` helper that copies the source
   `.pipeline.kts` into the tempdir before invoking the binary, mirroring
   the `copyFixtureInto` pattern established in WU-LPR-075.
- Bifurcated the runner:
  - Default: launch with `--workspace <tempdir>` so every fixture sees
     a clean per-test workspace.
  - For `10-smoke-e2e.pipeline.kts` (and only that one), launch
     **without** `--workspace` and use
     `ProcessBuilder.directory(workspace.toFile())` so the per-test
     tempdir becomes the process cwd. Legacy `cwd = corpus` semantics
     are preserved exactly.
- Verified fixture 29 (`29-mixed-utilities.pipeline.kts`) passes in
   isolation under `--workspace <tempdir>` (`EXIT=0`, `RunFinished
   outcome=success`).

## Verification

| Command | Outcome | SHA-256 |
|---------|---------|---------|
| `:pipeline-application:test --tests 'UatCompat001CorpusSmokeRunTest' --rerun-tasks` | BUILD SUCCESSFUL · `tests=2 failures=0 errors=0` | `lpr076-uat-final.log` |

The runner now exits cleanly in ~5 minutes (same as before
LPR-074/LPR-075), respecting the timeout 600 inner-loop envelope.

## End-of-work-unit closure

```text
Reference implementation consulted: Jenkinsfile / pipeline-utility-steps-plugin
  (familiarity surface only — same as LPR-074)
Behaviour adopted: per-test @TempDir workspace for binary-driven UATs that
  mutate artifacts; legacy cwd-corpus semantics preserved for fixtures that
  intentionally cross workspace boundaries.
Intentional deviations: fixture 10 explicitly bypasses --workspace so the
  fixture's `cd /tmp` semantics remain intact.
Security implications reviewed: n/a — no capability surface change.
Tests demonstrating the contract:
  - UatCompat001CorpusSmokeRunTest (2/0/0/0 after WU-LPR-076)
  - CompatibilityCorpusTest (30/0/0/0 from WU-LPR-075, still green)
```

— Receipt authored by SDDK orchestrator session `session_hare_*`.
