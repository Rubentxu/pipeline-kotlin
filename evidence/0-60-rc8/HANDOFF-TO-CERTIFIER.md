# Handoff to certifier — v0.60.0-rc8 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc8 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** direct commit `c6d49e88` on `main` (R4 / WU-R4.5 canary re-enable)
- **Base:** `e6cd2e3d` (R4 / WU-R4.7 CC-1 parser unification; this slice advances the candidate sequence)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.5 — cross-CLI observability-verbs canary re-enable against installDist.**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_5_INSTALLED_DIST_CANARY_RECEIPT.md))
  The canary was `@Disabled` since v0.60.0-rc5 because `pipelinek run` did not
  execute steps. R4 / WU-R4.0.0 (v0.60.0-rc7, `6af6e45c`) shipped the apiRange
  regeneration fix; the run body now executes and the journal receives step
  events. The R4.5 re-enable slice made four changes:

  1. The `pipelinek.launch/1` JSON envelope is on **stderr** in TEXT mode
     (OutputProtocol at `Main.kt:1251`). The original canary read stdout only
     and found nothing — TEXT mode is the default.
  2. The `[PipelineK] Run correlated: <uuid>` line on stdout carries the
     **correlationId**, not the runId. The two are distinct identities:
     the journal is keyed by runId; the correlationId threads a single run
     across control-plane + data-plane events. The canary was passing the
     correlationId to the four verbs, who then queried an empty slice of
     the journal. Three rows (parity, console refusal, version smoke) never
     actually queried the journal, so the wrong identity was silent.
  3. The parity row called `runFixture()` twice; each call runs the fixture
     afresh with a fresh runId, so verb output (keyed by runId) was never
     comparable. The parity test now populates the journal once and
     re-calls each verb twice on the same runId.
  4. The cross-check between the `[PipelineK] Run correlated:` line and the
     envelope's correlationId is now explicit: a drift fails loud — that
     drift is exactly the bug class this slice exists to catch.

- Version unchanged at `0.60.0` (rc8 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc8 |
| Commit | `c6d49e884a872a05d04e0d6a10261f3b69da775f` |
| Base | `e6cd2e3d` (R4 / WU-R4.7 CC-1 parser unification) |
| Tree state at commit | clean |
| Audit | R4 / WU-R4.5 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `f5671564b6fca12407a69fc581f0a4762b5e74c85813b2504aad90bf42e21d78` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `c6d49e884a872a05d04e0d6a10261f3b69da775f` |

The SBOM digest is unchanged from rc1..rc7 because no transitive dependency
changed — the R4.5 re-enable is purely test code, and the test class is not
packaged into the distribution ZIP. The ZIP digest is new because
`pipelinek-application-0.60.0.jar` carries the CC-1 refactored verb mains
(via `e6cd2e3d`).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `./gradlew :pipeline-application:test --tests 'R4ObservabilityCliInstallDistCanaryTest'` | 6 tests / 0 failures / 0 errors / 0 skipped |
| `./gradlew :pipeline-application:test --tests 'CliParserUnificationTest'` (CC-1) | 20 tests / 0 failures / 0 errors (carried from rc7 base) |
| New detekt issues introduced | 0 |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XML:
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.R4ObservabilityCliInstallDistCanaryTest.xml` — `tests=6 skipped=0 failures=0 errors=0 timestamp=2026-10-14T11:42:02.246Z`

CC-1 XML (carried):
- `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CliParserUnificationTest.xml` — 20/0/0/0

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the canary test** from a clean checkout at `c6d49e88`:
   ```bash
   git checkout c6d49e88
   cd v2
   ./gradlew :pipeline-application:installDist
   ./gradlew :pipeline-application:test --tests 'R4ObservabilityCliInstallDistCanaryTest'
   ```
   The class must report 6 tests / 0 failures / 0 errors. The test is HF2
   installDist: it drives the binary the certifier is about to certify,
   against a 3-step fixture (`echo` + `sh` + `error("boom")`), and reads
   the events journal + Output Plane via `inspect` / `events` / `observe` /
   `console`.
3. **Re-measure the binary directly**:
   ```bash
   bin/pipelinek run --db /tmp/j.db --control-root /tmp/c <(echo '
   pipeline {
     stages { stage("main") {
       echo("hello from rc8 certifier probe")
       sh("printf ok\\n")
       error("boom")
     } }
   }')
   ```
   The stderr must contain a `pipelinek.launch/1` JSON envelope with a
   `runId` and `correlationId` field. The text `Run correlated: <uuid>` line
   on stdout MUST carry the same uuid as the envelope's `correlationId`
   (the canary's cross-check enforces this).
4. **Inspect the canary's verb outputs**:
   ```bash
   RUN=$(bin/pipelinek run --db /tmp/j.db --control-root /tmp/c --isolated <script> 2>&1 | grep -oE 'runId":"[a-f0-9-]+' | head -1 | cut -d'"' -f3)
   # Or read the runId from the JSON envelope on stderr
   bin/pipelinek observe "$RUN" --db /tmp/j.db --control-root /tmp/c --view events --format jsonl
   bin/pipelinek events --db /tmp/j.db "$RUN" --kind StepFailed
   bin/pipelinek inspect "$RUN" --db /tmp/j.db --control-root /tmp/c --failed --format json
   ```
   All four must return non-empty output. The journal must contain
   `StepStarted` + `StepFinished` + `StepFailed` for the 3-step fixture.
5. **Verify the [PipelineK] Run correlated: drift** between the stderr
   envelope and the stdout line. The canary already enforces this; the
   certifier can re-measure by hand.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `e6cd2e3d`.
- `C8InstalledDistributionCanaryTest` was 0/3 pre-fix on the
  `IncompatibleApiRange` root cause. The R4.0.0 fix in rc7 unblocked it; the
  canary itself is not in this candidate's verification matrix (separate
  re-enable slice).
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not
  regressions from R4.5; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
