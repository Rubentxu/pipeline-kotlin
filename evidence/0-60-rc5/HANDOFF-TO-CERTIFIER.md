# Handoff to certifier — v0.60.0-rc5 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc5 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** PR #118 (branch `r4/wu-r4-1-3-log-tail-op`) → merge commit `12f49af9`
  (squash, AGENTS ADR-0099: main keeps the full commit history)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.1.3 — `inspect --log-tail-op <opId>` (per-operation console tail)**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_1_3_LOG_TAIL_OP_RECEIPT.md))
  — adds the per-op tail read primitive + opt-in CLI binding. Per-step auto-binding
  is documented as a follow-up (requires opId resolution from the durable control
  journals).
- Version unchanged at `0.60.0` (rc5 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc5 |
| Branch | `r4/wu-r4-1-3-log-tail-op` (squash-merged to main) |
| Merge commit | `12f49af9e918a322cbe48ac33dd5a52c2e08ef6a` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.1.3 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `b5cb1ce78ec5a80f61fc28b3d5770d61fa7c5161c9fabdf6d6f11ca0568777a3` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `12f49af9e918a322cbe48ac33dd5a52c2e08ef6a` |

The SBOM digest is unchanged from rc1/rc2/rc3/rc4 (no dependency change in this slice);
the ZIP digest is new (the new `readTail(runId, opId, N)` overload + per-op filter
+ `--log-tail-op` parser branch + 6 new tests).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-application:test --tests 'MainInspectCliLogTailOpTest'` | 6 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainInspectCliTest'` | 11 tests / 0 failures / 0 errors |
| New detekt issues introduced | 0 |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs:
- `v2/pipeline-application/build/test-results/test/TEST-...MainInspectCliLogTailOpTest.xml` — `tests=6 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainInspectCliTest.xml` — `tests=11 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the targeted tests** from a clean checkout at the merge commit `12f49af9`:
   ```bash
   git checkout 12f49af9
   cd v2
   ./gradlew :pipeline-application:test --tests 'MainInspectCliLogTailOpTest' \
             :pipeline-application:test --tests 'MainInspectCliTest'
   ```
   Both classes must report 0 failures / 0 errors. The HF0 / HF1 contract is the
   production entry point the harness crosses; a re-implementation is not a substitute
   (HARNESS FIDELITY LAW §1).
3. **Re-measure the per-op read primitive**: `FrameIndexedObservationOutputReader.readTail(runId, opId, N)`
   returns the last N bytes of frames whose stream id carries the opId as a middle segment.
   A future slice composes the per-step auto-binding against this primitive.
4. **Re-measure the CLI binding**: `--log-tail-op <opId>` scopes the tail; `--log-tail-op`
   with no value or blank value is refused at exit 2 with a typed reason on stderr.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `347470d4`.
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not regressions
  from WU-R4.1.3; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
