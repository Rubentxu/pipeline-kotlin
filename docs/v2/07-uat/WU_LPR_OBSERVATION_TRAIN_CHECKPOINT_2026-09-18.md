# Observation/Performance Train — Cumulative Checkpoint (2026-09-18)

**Train:** WU-LPR-040 → 041 → 042 → 043 (Observation/Performance)
**Base at train start:** `881829fc` (after prior train push + ID-reconciliation `f2b86404` + WU-LPR-080A `cffd231d`)
**Development head at close:** `fdbeb433`
**Origin/main:** `fdbeb433` (all slices pushed individually; trunk-integrated)

## Commits (in order)

| Commit | WU | Content |
|---|---|---|
| `cffd231d` | WU-LPR-080A | SDKMAN Vendor Onboarding checklist (administrative, parallel; no code) |
| `ab20403a` | WU-LPR-040 | Observation/perf harness: events D1..D6 + process output P1..P6 (harness only) |
| `d965bd7b` | WU-LPR-041 | SqliteEventStore durable sequence repair (seed counters from `MAX(sequence)` GROUP BY) |
| `f5299cad` | WU-LPR-042 | SqliteEventStore in-place perf: single-writer, batched COMMITs, flush barrier |
| `fdbeb433` | WU-LPR-043 | Console hot path decoupling (bounded Channel + renderer coroutine) + observation gate + pre-existing pin repairs |

## Released baseline / head

- `released_baseline`: `d2fe73c6` (prior train close on origin/main before this train's 080A slice)
- `development_head`: `fdbeb433`
- `workspace_version`: unchanged this train (no version bump; no release tag — internal train per continuity policy)
- `actual_release_tag`: none (product release train is the NEXT train: certification ledger → real project → distZip → GitHub Release → SDKMAN)
- `release_sha` / `binary_sha256`: N/A this train (no distribution artifact produced)

## Gate evidence

- `:pipeline-events:test`: 139/139 green (fresh XML; includes LPR-000, LPR-040 harness, LPR-041 repair, LPR-042 perf)
- `:pipeline-step-sdk:runtime:test`: 187/187 green (fresh XML; includes P1..P6 harness after hot-path change)
- Repaired pin classes (application): CoreDeleteDirStepUnitTest, CoreIsUnixStepUnitTest, CoreArchiveArtifactsStepUnitTest — green
- `ReconciliationStatusOnlyTest` (application consumer): green

## Performance results (headline)

| Metric | Before | After |
|---|---|---|
| Event append/event | 476–513µs | **11–16µs (30–40x)** |
| 100k events wall | 47.7s | **1.4s** |
| Slow sink (P4) | coupled drain | **decoupled, ms=8** |
| 100 MiB stdout (P1) | 141ms | 194ms (no regression) |
| Sequence reopen bug | maxSeq restart, 500 dups | **fixed: dups=0, maxSeq=1000** |

## Observation gate (WU-LPR-043 verdict)

**6 PASS / 1 NOT DONE / 1 PARTIAL.**
- NOT DONE: streaming secret redaction (product seam; P5 characterization recorded)
- PARTIAL: EchoOutputCaptured duplication removal (parity-proof-gated per trunk law; tracked WU-LPR-042-FOLLOWUP)
Both are product semantics outside the observation/performance scope. Performance plane is closed.

## New debt / follow-ups

1. Streaming secret redaction before durable transcript (product; P5 evidence in receipt 040)
2. EchoOutputCaptured large-payload duplication removal after compat/parity proof
3. CompatibilityCorpus fixture05/fixture14 pre-existing failures (base-verified at 881829fc worktree; known family, out of scope both trains)

## Next

Per continuity policy, STOP here: observation train closed. Next train is
the product-oriented release train (certification ledger → real Gradle/
Maven project via installDist → distZip → GitHub Release → WU-LPR-080/
080A SDKMAN), pending human checkpoint review.
