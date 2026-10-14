# Handoff to certifier — v0.61.0-rc1 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.61.0-rc1 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** squash-merge of PR #123 (CON-2.3) and PR #124 (CON-2.2) into `main`
- **Base:** `5b436d65` (v0.60.0-rc8 evidence; this slice advances the train from 0.60.0 to 0.61.0)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

This candidate is the R4 closure — all five planned phases (R4.0.0, R4.5, R4.6, R4.7/CC-1, CON-2.2,
CON-2.3) are on `main`. The train advances from `0.60.0` to `0.61.0` because the train-across
releases has accumulated enough surface change to merit a new minor.

### Phases included in this candidate

1. **R4 / WU-R4.0.0 — run-execution fix** (already in `5b436d65`/v0.60.0-rc7).
   Example plugins ship with `apiRange=[0.60.0, 0.61.0)`. Without this, every `pipelinek run`
   exits 2 before the run body compiles.

2. **R4 / WU-R4.5 — cross-CLI observability-verbs canary re-enable**
   (already in `5b436d65`/v0.60.0-rc8). 6/6 canary tests pass. Receipt
   `docs/v2/07-uat/R4_WU_R4_5_INSTALLED_DIST_CANARY_RECEIPT.md` is **CERTIFIED**.

3. **R4 / WU-R4.6 — `console --frames`** (already in `5b436d65`/v0.60.0-rc6).
   Per-frame records for the JSONL path. Foundation for both CON-2.2 and CON-2.3.

4. **R4 / WU-R4.7 — CC-1 parser unification** (already in `5b436d65`/v0.60.0-rc7).
   The four `Main*Cli` verb admission parsers unified under a single `CliParser` entry point.
   All refusal prefixes (`console-refused:`, `evt-refusal-v1:`, `evt-refusals-v1:`,
   `evt-stalled-v1:`, `Error:`, `inspect:`, `observe:`) preserved byte-for-byte.

5. **R4 / WU-CON-2.2 — `console --stage/--step/--kind/--run` content filters** (PR #124, `eda7dd56`).
   Four new flags on the per-frame path. Receipt
   `docs/v2/07-uat/CON_2_2_CONSOLE_FILTERS_RECEIPT.md` is **IMPLEMENTED_UNCERTIFIED** —
   awaits the certifier's `CERTIFIED` verdict. 24/24 unit tests pass.

6. **R4 / WU-CON-2.3 — `console --follow` tail mode** (PR #123, `9f3a151b`).
   Bounded polling on the per-frame path. Receipt
   `docs/v2/07-uat/CON_2_3_CONSOLE_FOLLOW_RECEIPT.md` is **IMPLEMENTED_UNCERTIFIED** —
   awaits the certifier's `CERTIFIED` verdict. 22/22 unit tests pass.

### Known limitation in CON-2.2

The `--kind` filter is admitted but treated as a pass-through. The per-frame path
(`FrameIndexedObservationOutputReader`) does not record event kinds, so wiring `--kind
<event-kind>` to the events journal requires an events-→-opId resolution that the WU-R4.6
receipt names as the next slice. The contract this slice fixes ("empty axis is a no-op,
non-empty axis is a conjunct") is what the next slice extends without breaking today's
matches. The certifier SHOULD flag this if `--kind` is a contractual requirement; otherwise
the limitation is documented in the receipt and the next slice is the right place to address
it.

## Source of truth

| Item | Value |
|---|---|
| Train | 0.61.0-rc1 |
| Base | `5b436d65` (v0.60.0-rc8) |
| Tip | `eda7dd56` (squash-merge of PR #124) |
| PR #123 (CON-2.3) | `9f3a151b` (merged before CON-2.2; CON-2.2 rebased on top) |
| PR #124 (CON-2.2) | `eda7dd56` (rebased on PR #123, 80/80 tests pass after rebase) |
| Tree state at tip | clean |
| Audit | R4 closure complete; CON-2.2 + CON-2.3 ready for certifier review |

## Artefacts (SHA-256, from `SHA256SUMS`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `b2ffccfba9ec6b96b3a74845d8ad523c3fe92da893912e9893e6c29aef2e470e` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `eda7dd56e9d3418f918e44d6fd153eee645beb57` |

The SBOM digest is unchanged from rc1..rc8 because no transitive dependency changed —
the R4 closure is purely code (and one test class that is not packaged into the distribution
ZIP). The ZIP digest is new because the merged `pipelinek-application-0.60.0.jar` carries
the CON-2.2 + CON-2.3 verb mains.

The asset filename is `pipelinek-0.60.0.zip` and the asset basename in the handoff is also
`pipelinek-0.60.0`. The `0.61.0-rc1` candidate train's product identity is `0.60.0` (asset
and version are unchanged); the `rc1` and `0.61.0-rc1` are the candidate sequence and
release train metadata, not the product version. **The asset's product identity is
`pipelinek-0.60.0`; the candidate's release-train is `0.61.0-rc1`.**

## Verification (producer-measured)

| Check | Result |
|---|---|
| `./gradlew :pipeline-application:test --tests 'R4ObservabilityCliInstallDistCanaryTest'` | 6 tests / 0 failures / 0 errors / 0 skipped (CERTIFIED) |
| `./gradlew :pipeline-application:test --tests 'ConsoleFollowTest'` | 22 tests / 0 failures / 0 errors (CON-2.3) |
| `./gradlew :pipeline-application:test --tests 'ConsoleFrameFiltersTest'` | 24 tests / 0 failures / 0 errors (CON-2.2) |
| `./gradlew :pipeline-application:test --tests 'CliParserUnificationTest'` | 20 tests / 0 failures / 0 errors (CC-1) |
| Compile check on CON-2.2+CON-2.3 merge | PASS |
| New detekt issues introduced | 0 (CC-1 carried 0; CON-2.2 + CON-2.3 not yet linted separately) |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs (all fresh at `eda7dd56`):
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.R4ObservabilityCliInstallDistCanaryTest.xml` — `tests=6 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ConsoleFollowTest.xml` — `tests=22 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ConsoleFrameFiltersTest.xml` — `tests=24 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CliParserUnificationTest.xml` — `tests=20 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the four test classes** from a clean checkout at `eda7dd56`:
   ```bash
   git checkout eda7dd56
   cd v2
   ./gradlew :pipeline-application:installDist
   ./gradlew :pipeline-application:test \
     --tests 'R4ObservabilityCliInstallDistCanaryTest' \
     --tests 'ConsoleFollowTest' \
     --tests 'ConsoleFrameFiltersTest' \
     --tests 'CliParserUnificationTest'
   ```
   All four classes must report 0 failures / 0 errors. Totals: 6 + 22 + 24 + 20 = 72 tests.
3. **Re-measure the binary directly** — exercise the four new flags on the installDist binary:
   ```bash
   bin/pipelinek run --db /tmp/j.db --control-root /tmp/c <(echo '
   pipeline {
     stages { stage("main") {
       echo("hello from rc1 certifier probe")
       sh("printf ok\\n")
       error("boom")
     } }
   }')
   # CON-2.3 — follow mode
   bin/pipelinek console --control-dir /tmp/c <runId> <opId> --frames --follow --format jsonl
   # CON-2.2 — content filters (re-run the same fixture's opId)
   bin/pipelinek console --control-dir /tmp/c <runId> <opId> --frames \
     --stage main --step main/echo-0 --format jsonl
   ```
   All three must exit 0 and emit non-empty JSONL on stdout.
4. **Verify the [PipelineK] Run correlated: drift** is still caught — the R4.5 canary enforces
   this; the certifier can re-measure by hand.
5. **Spot-check the kind filter limitation** (CON-2.2). Run with `--kind StepStarted`; the
   JSONL output is the same as without the flag (pass-through). This is documented in the
   receipt as a known limitation. Flag in the verdict if `--kind` is contractual for the
   release; otherwise the limitation is acceptable for rc1.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `5b436d65`.
- CON-2.2 kind-filter pass-through (see above).
- CON-2.3 installDist canary could not assert on frame content because the Output Plane
  population for `sh` steps in installDist is an upstream concern (per the CON-2.3 receipt).
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not
  regressions from the R4 closure; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
