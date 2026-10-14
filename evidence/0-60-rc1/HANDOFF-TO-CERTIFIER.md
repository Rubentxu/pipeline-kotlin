# Handoff to certifier — v0.60.0-rc1 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc1 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** tag `v0.60.0-rc1` → commit `b5b5ffa7` (PR #110 merge commit; PR #108 + #109 already merged)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R3 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R3 / OBS-3 step 2** ([ADR-OBS-004-segment-frame-offset-index](../../docs/v2/04-adrs/ADR-OBS-004-segment-frame-offset-index.md),
  status `accepted`) — `framesOfRun(K, L)` is `O(log N + L)` for limit-bound reads via a sparse
  per-run offset index with one entry every `INDEX_STRIDE = 1000` frames. Closes **PC-05**.
- Version train advance 0.57.0 → 0.60.0 (minor bumps aggregated from R1..R3).
- Receipt: [docs/v2/07-uat/R3_STEP_2_RECEIPT.md](../../docs/v2/07-uat/R3_STEP_2_RECEIPT.md).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc1 |
| Branch | `release/v0.60.0-rc1` |
| Tag commit | `b5b5ffa7` (PR #110 merge commit) |
| Tree state at tag | clean (verified by `git ls-tree` at tag) |
| Audit | R3 / OBS-3 step 2 closed in this candidate |
| `candidateAdmission` | pass pending re-execution by the certifier (re-run against this tag) |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.5 MB | `cbd868280879da9409f3705025e316de2260bb92e031537445df029ec6146ae8` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `b5b5ffa7cfd955dd7311b9ceb93edc753f7ffd28` |

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts in the table above. Every line must
   match what the certifier computes locally.
2. **Re-run the gate** from a clean checkout at the tag commit `b5b5ffa7`:
   ```bash
   git checkout b5b5ffa7
   cd v2
   ./gradlew --no-daemon --no-parallel \
     :pipeline-output-store:test \
     --rerun-tasks
   ```
   Must complete with 133 tests / 0 failures / 0 errors / 2 skipped (the same numbers the
   producer measured for `b6a528d8`, the implementation commit).
3. **Re-measure the R3 scenarios** documented in
   [docs/v2/07-uat/R3_STEP_2_RECEIPT.md](../../docs/v2/07-uat/R3_STEP_2_RECEIPT.md):
   - rows 1..10 of `SegmentFrameIndexOffsetIndexTest`,
   - crash windows W1..W5 (unchanged code path),
   - cross-process XPROC-1 / XPROC-2 (unchanged code path),
   - the `after-fix — framesOfRun(K, 10) is O(L)` benchmark row.
4. **Sign the verdict** in `evidence/0-60-rc1/CERTIFIER-VERDICT.md` with the certifier's own
   identity and the SHAs above.

## What the certifier must NOT do

1. **Do not modify the artefacts in this directory.** The candidate is immutable. If a SHA
   mismatches, fail the verdict and report; do not rebuild locally and substitute your build
   for the producer's.
2. **Do not promote to GA.** `v0.60.0-rc1` is a release candidate, not a GA. A separate cycle
   owns the GA decision.
3. **Do not run UAT rows that were migrated to the harness** (per `RESPONSIBILITY_MIGRATION_ROADMAP.md`).
   They live in `pipelinek-release-harness` now; this repo no longer runs them.

## Known issues (open, NOT blocking certification)

- **Detekt pre-existing failures** at this SHA: `EventFollower.kt:104-106 MaxLineLength`,
  `OutputProtocol.kt:98`, `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`
  NewLineAtEndOfFile, `ObsPc2ProducerSurvivalSpikeTest.kt:107 LongMethod`. All five files
  existed in main before this PR (verified at base `347470d4`) and are NOT regressions from
  R3 step 2. The producer's `./gradlew check` therefore exits `BUILD FAILED` on the
  architecture-fitness gate. The producer measured module-level `:pipeline-output-store:test`
  as 133 tests / 0 failures / 0 errors / 2 skipped and pushed the candidate on that row only.
  Out of scope for this RC; owner: a separate batch.
- **CI / harness authority** still does not exist on this repo (ADR-0105; `G10 = NOT_RUN`).
  The PRODUCT-GATE therefore remains `BLOCKED_EXTERNAL`. Promotion to GA is the harness's
  decision; this repo does not declare `CERTIFIED` or `STABLE_PROMOTED`.

## Deviations from ADR-OBS-004 (re-stated from the receipt)

The implementation deviates from ADR-OBS-004 §3.3 step 1 in one substantive way: the cache is
keyed on the **offset-index file's mtime**, not the frames file's mtime. With a frames-mtime
key, the ADR's "rebuild on `mtime(index) < mtime(frames)`" rule would fire on every
sub-`INDEX_STRIDE` append and defeat the same ADR's `O(L)` cost claim. Index-mtime keys keep
sub-stride appends cache-hit, while stride-crossings invalidate. Rebuild on deletion /
partial-write recovery / cold start is preserved.

Two other smaller deviations (fileSize guard in `binarySeekIndex`; in-process threads for
row 10) are documented in the receipt §5.