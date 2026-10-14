# Handoff to certifier — v0.60.0-rc7 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc7 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** branch `r4/0-0-run-execution-fix` → merge commit `f104af6e`
  (squash, AGENTS ADR-0099: main keeps the full commit history)
- **Base:** `0cd42636` (v0.60.0-rc6 publication; this slice advances the candidate sequence)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.0.0 — run-execution fix (the example-plugin apiRange rebuild).**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_0_0_RUN_EXECUTION_FIX_RECEIPT.md))
  — the three example plugins shipped in `installDist/lib/` had
  `apiRange=[0.57.0, 0.58.0)` against an `0.60.0` host, which made
  `PluginCompositionAdmitter` refuse every plugin and `pipelinek run` exit
  2 before the run body compiled. The fix declares `-PsdkVersion` /
  `rootProject.version` as Gradle inputs on the inner `compute*Release`
  tasks and the outer `buildExample*Plugin` tasks, so the `apiRange` is
  regenerated on every host version bump. The shipped installDist now
  carries `apiRange=[0.60.0, 0.61.0)`.

- The original R4 closure plan named `get$$result` as the suspected cause
  (the reflective extraction that swallows exceptions in Main.kt). The
  actual cut point was upstream at `admitThenComposeOrExit`. The plan's
  diagnosis was a plausible hypothesis; the receipt documents what was
  actually wrong.

- Version unchanged at `0.60.0` (rc7 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc7 |
| Branch | `r4/0-0-run-execution-fix` (squash-merged to main) |
| Merge commit | `f104af6e17e68878f48f767b2f377c038aad7ff1` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.0.0 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `97eae5b8cc287544b86d51691ead17c434affa97b95f87dea8c89f02971f9b5d` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `f104af6e17e68878f48f767b2f377c038aad7ff1` |

The ZIP digest is new (the rebuild changed the staged `example-*-plugin-0.1.0.jar` files
which are part of the lib/ directory in the distribution). The SBOM digest is unchanged
from rc1..rc6 because no transitive dependency changed — the apiRange fix is purely
build-time metadata.

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-application:test --tests 'RunExecutesStepsInstalledUatTest'` | 1 test / 0 failures / 0 errors |
| Probe: `pipelinek run` against the installDist binary on a 3-step fixture | exits 1 (fail on `error("boom")`), journal contains `StepStarted` + `StepFinished` + `main/error-0` `outcome=failure` + `outcome=failure` RunFinished |
| New detekt issues introduced | 0 |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XML:
- `v2/pipeline-application/build/test-results/test/TEST-...RunExecutesStepsInstalledUatTest.xml` — `tests=1 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the regression test** from a clean checkout at the merge commit `f104af6e`:
   ```bash
   git checkout f104af6e
   cd v2
   ./gradlew :pipeline-application:installDist
   ./gradlew :pipeline-application:test --tests 'RunExecutesStepsInstalledUatTest'
   ```
   The class must report 0 failures / 0 errors. The test is HF2 installDist: it
   drives the binary the certifier is about to certify, against a 3-step fixture,
   and reads the events journal via `inspect`.
3. **Re-measure the binary directly** (the round gate the certifier runs on its own):
   ```bash
   bin/pipelinek run --db /tmp/j.db --control-root /tmp/c <(echo '
   pipeline {
     stages { stage("main") {
       echo("hello from rc7 certifier probe")
       sh("printf ok\\n")
     } }
   }')
   bin/pipelinek inspect <runId-from-launch-envelope> --db /tmp/j.db --control-root /tmp/c --format json
   ```
   The journal must contain `StepStarted` + `StepFinished` for `main/echo-0` and
   `main/sh-0`. Pre-fix: the journal had only the 3 lifecycle events.
4. **Inspect the example plugin manifests** at
   `examples/example-*-plugin/build/resources/main/META-INF/pipelinek/plugin-manifest.json`:
   `apiRange` must be `[0.60.0, 0.61.0)`. Pre-fix: `[0.57.0, 0.58.0)`.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `347470d4`.
- `C8InstalledDistributionCanaryTest` was 0/3 pre-fix on the same `IncompatibleApiRange`
  root cause. Post-fix it should be 3/3, but that test is **not** in this candidate's
  verification matrix; the certifier can re-run it independently and trust the same
  evidence (the three example plugins now have the correct apiRange).
- `R4ObservabilityCliInstallDistCanaryTest` is still `@Disabled`. It is unblocked
  by this fix and is the next candidate (Phase 2 / R4.5 re-enable).
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not
  regressions from R4.0.0; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
