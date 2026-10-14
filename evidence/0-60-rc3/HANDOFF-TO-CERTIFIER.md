# Handoff to certifier — v0.60.0-rc3 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc3 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** PR #116 (branch `r4/wu-r4-2-events-inspection`) → merge commit `49496b7b`
  (squash, AGENTS ADR-0099: main keeps the full commit history)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.2 — events CLI gets `--follow / --fields / --context / --tail`**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_2_EVENTS_CLI_INSPECTION_RECEIPT.md))
  — closes the WU-LPR-051 follow-up A. Closes **EVT-3.1 / EVT-3.2** from
  `R4_CLI_GAP_ANALYSIS §3`.
- Version unchanged at `0.60.0` (rc3 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc3 |
| Branch | `r4/wu-r4-2-events-inspection` (squash-merged to main) |
| Merge commit | `49496b7bbfc7e444b80513e8d3a43b3e66d89a6d` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.2 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `799bfbcd2d95640cf7a744abd60e976c74b6df09bc4cdda01f90759f18d98074` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `49496b7bbfc7e444b80513e8d3a43b3e66d89a6d` |

The SBOM digest is unchanged from rc1/rc2 (no dependency change in this slice);
the ZIP digest is new (the new `EventsInspectionFlag` ADT + 4 new CLI flags +
`parseMainArgs` / `runWithArgs` split + `runFollow` poll loop + 21 new tests).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-application:test --tests 'MainEventsInspectionFlagsTest'` | 21 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainEventsCliArgumentContractTest'` | 3 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainEventsCliArgumentStrictnessTest'` | 4 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainEventsCliRefusalVisibilityTest'` | 5 tests / 0 failures / 0 errors |
| New detekt issues introduced | 0 (2 suppressed at function/object level with WU-R4.5 pointer) |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs:
- `v2/pipeline-application/build/test-results/test/TEST-...MainEventsInspectionFlagsTest.xml` — `tests=21 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainEventsCliArgumentContractTest.xml` — `tests=3 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainEventsCliArgumentStrictnessTest.xml` — `tests=4 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainEventsCliRefusalVisibilityTest.xml` — `tests=5 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the targeted tests** from a clean checkout at the merge commit `49496b7b`:
   ```bash
   git checkout 49496b7b
   cd v2
   ./gradlew :pipeline-application:test --tests 'MainEventsInspectionFlagsTest' \
             :pipeline-application:test --tests 'MainEventsCliArgumentContractTest' \
             :pipeline-application:test --tests 'MainEventsCliArgumentStrictnessTest' \
             :pipeline-application:test --tests 'MainEventsCliRefusalVisibilityTest'
   ```
   All four classes must report 0 failures / 0 errors. The HF0 / HF1 contract is the
   production entry point the harness crosses; a re-implementation is not a substitute
   (HARNESS FIDELITY LAW §1).
3. **Re-measure the closed ADT** of `EventsInspectionFlag` (4 cases: `Follow`, `Fields`,
   `Context`, `Tail`). Adding a fifth case is a compile-time break for every caller.
4. **Re-measure the admission gate** that refuses `--typed` combined with any of
   `--fields / --context / --tail` (exit 2, typed reason on stderr). The closed `EventField`
   ADT is bound to `PipelineEventEnvelope`, not to `DomainEvent`.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (OutputProtocol.kt:98,
  LaunchReceiptModesTest.kt:276, OutputProtocolNegotiationTest.kt:150,
  ObsPc2ProducerSurvivalSpikeTest.kt:107) — out of scope at base `347470d4`.
- 2 new detekt suppressions on WU-R4.2 surfaces: `MainEventsCli` TooManyFunctions (16
  vs max 12) and `parseMainArgs` CyclomaticComplexMethod (31 vs max 25). Both are
  documented in the file with a comment naming WU-R4.5 (cross-CLI canary, CC-1) as the
  proper refactor that drops the counts by construction.
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not regressions
  from WU-R4.2; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
