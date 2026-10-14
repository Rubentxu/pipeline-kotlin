# Handoff to certifier — v0.60.0-rc2 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc2 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** PR #115 (branch `r4/wu-r4-4-utf8-decoder`) → merge commit `662ed6cb`
  (squash, AGENTS ADR-0099: main keeps the full commit history)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.4 — cross-frame UTF-8 decoder (PC-08)**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_4_CROSS_FRAME_UTF8_DECODER_RECEIPT.md))
  — Adds `StreamingUtf8Decoder` (stateful UTF-8 over a byte stream, manual FSM, surrogate pair
  for 4-byte sequences) and wires it into `pipelinek inspect --log-tail`. Closes **PC-08**.
- Version unchanged at `0.60.0` (rc2 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc2 |
| Branch | `r4/wu-r4-4-utf8-decoder` (squash-merged to main) |
| Merge commit | `662ed6cba69ea611cb8eb4e6a6b290655d0299e1` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.4 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `5c34ea834442fb2d483cb4e34adf6eef53dc9d4af3e93c96a45cd1c4b8f46b86` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `662ed6cba69ea611cb8eb4e6a6b290655d0299e1` |

The SBOM is the same digest as the v0.60.0-rc1 SBOM (no dependency change in this slice);
the ZIP digest is new (the new `StreamingUtf8Decoder` + 3 PC-08 join rows + receipt).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-output:test --tests 'StreamingUtf8DecoderTest'` | 7 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainInspectCliTest'` | 11 tests / 0 failures / 0 errors (8 prior + 3 PC-08 rows) |
| Pre-existing detekt baseline (4) | unchanged from base `347470d4`; 0 new issues |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs:
- `v2/pipeline-output/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.output.StreamingUtf8DecoderTest.xml` — `tests=7 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainInspectCliTest.xml` — `tests=11 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the gate** from a clean checkout at the merge commit `662ed6cb`:
   ```bash
   git checkout 662ed6cb
   cd v2
   ./gradlew :pipeline-output:test --tests 'StreamingUtf8DecoderTest' \
             :pipeline-application:test --tests 'MainInspectCliTest'
   ```
   Both classes must report 0 failures / 0 errors. The HF0 contract is the production
   entry point the harness crosses; a re-implementation is not a substitute (HARNESS FIDELITY LAW §1).
3. **Re-measure the R4 scenarios.** The decoder must reassemble a 4-byte emoji split
   across two `ObservationRecord.Output` records into one character (MainInspectCliTest row 10).
4. **Re-measure the pre-existing baseline.** The 4 pre-existing detekt issues in
   `:pipeline-output` and `:pipeline-application` must remain at the same count.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-output` and `:pipeline-application`
  (OutputProtocol.kt:98, LaunchReceiptModesTest.kt:276, OutputProtocolNegotiationTest.kt:150,
  ObsPc2ProducerSurvivalSpikeTest.kt:107) — out of scope at base `347470d4`.
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not regressions
  from WU-R4.4; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
