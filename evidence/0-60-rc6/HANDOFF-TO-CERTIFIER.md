# Handoff to certifier — v0.60.0-rc6 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc6 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** PR #120 (branch `r4/wu-r4-6-console-per-frame`) → merge commit `0cd42636`
  (squash, AGENTS ADR-0099: main keeps the full commit history)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.6 — `console --frames` (per-frame records for the JSONL path)**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_6_CONSOLE_PER_FRAME_RECEIPT.md))
  — adds the per-frame read path. The new `ConsoleReadRequest.Frames` closed ADT, the
  `IncompatibleWithFrames` typed refusal, the `--frames` / `--frame-limit` / `--after-ordinal`
  admission, the per-frame read through `FrameIndexedObservationOutputReader.readOutput`, and
  the new `out-frame-ord-v1:<ordinal>` continuation token (distinct from
  `out-cursor-v1:<stream>:<offset>`). The WU-R4.3 synthetic-merged-page workaround for JSONL
  is removed; the per-frame path emits one real-frame record per line.
- **Drive-by:** `LiveOutputDrainTest` was missing the WU-R4.1.3 `readTail(runId, opId, tailBytes)`
  override (the test had been failing to compile since rc5). Added the missing method
  (no behavioural change).
- Version unchanged at `0.60.0` (rc6 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc6 |
| Branch | `r4/wu-r4-6-console-per-frame` (squash-merged to main) |
| Merge commit | `0cd426365a9702bdcdc692d448620880353c3b6b` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.6 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `969fd7613b1a6cfbfb0e1852a2b53dc22929b9b5cd209a28e3b3747b2a7bed01` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `0cd426365a9702bdcdc692d448620880353c3b6b` |

The SBOM digest is unchanged from rc1/rc2/rc3/rc4/rc5 (no dependency change in this slice);
the ZIP digest is new (the new `ConsoleReadRequest.Frames` variant + `IncompatibleWithFrames`
refusal + 3 new flags + `readFrames` / `emitFrames` dispatch + 14 new tests).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-application:test --tests 'MainConsoleCliPerFrameTest'` | 14 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainConsoleFormatTest'` | 9 tests / 0 failures / 0 errors (WU-R4.3 contract unchanged) |
| `:pipeline-application:test --tests 'ConsoleReadServiceTest'` | 12 tests / 0 failures / 0 errors (byte path unchanged) |
| `:pipeline-application:test --tests 'MainInspectCliLogTailOpTest'` | 6 tests / 0 failures / 0 errors (WU-R4.1.3 contract unchanged) |
| `:pipeline-application:test --tests 'LiveOutputDrainTest'` | 7 tests / 0 failures / 0 errors (drive-by fix landed) |
| New detekt issues introduced | 0 |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs:
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleCliPerFrameTest.xml` — `tests=14 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleFormatTest.xml` — `tests=9 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...ConsoleReadServiceTest.xml` — `tests=12 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainInspectCliLogTailOpTest.xml` — `tests=6 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...LiveOutputDrainTest.xml` — `tests=7 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the targeted tests** from a clean checkout at the merge commit `0cd42636`:
   ```bash
   git checkout 0cd42636
   cd v2
   ./gradlew :pipeline-application:test --tests 'MainConsoleCliPerFrameTest' \
             :pipeline-application:test --tests 'MainConsoleFormatTest' \
             :pipeline-application:test --tests 'ConsoleReadServiceTest' \
             :pipeline-application:test --tests 'MainInspectCliLogTailOpTest' \
             :pipeline-application:test --tests 'LiveOutputDrainTest'
   ```
   Every class must report 0 failures / 0 errors. The HF0 / HF1 contract is the production
   entry point the harness crosses; a re-implementation is not a substitute (HARNESS FIDELITY
   LAW §1).
3. **Re-measure the per-frame read primitive**: `FrameIndexedObservationOutputReader.readOutput`
   filtered by opId must produce a real-frame page (one `ObservationRecord.Output` per real
   `OutputFrame`, in observation order).
4. **Re-measure the CLI binding**: `--frames` admits; `--frames + --range` / `--max-bytes` /
   `--after-cursor` refuse; `--frame-limit N` round-trips for `N > 0`; `--after-ordinal N`
   round-trips for `N >= -1`; the JSONL emitter produces one line per real frame carrying
   `ordinal` / `from` / `to` / `channel` / `operation`; the TEXT emitter produces the
   concatenated bytes plus a frame-ordinal continuation token on stderr.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `347470d4`.
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not regressions
  from WU-R4.6; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.
- The pre-existing run-execution defect that breaks `C8InstalledDistributionCanaryTest` and
  the WU-R4.5 canary is unchanged. WU-R4.6 is testable in-process (the per-frame read
  primitive only needs a populated `OutputFrameIndex` + byte store) and is independent.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
