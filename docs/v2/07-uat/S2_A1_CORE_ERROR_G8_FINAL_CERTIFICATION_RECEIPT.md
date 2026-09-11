# S2-A1 / G8 — Final Certification Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A1 (`core.error`)
> Gate: **G8 — Final Certification (real installed CLI)**
> Branch HEAD: see `git log` (post-G8 close; this commit IS G8)
> Date: 2026-09-11T12:11Z
>
> SHA reference: G7 `babb86c710cdf91b9d3d7ecde42d736236cf719b` (frozen).
> G8 closes S2-A1 and flips `CERTIFIED = true`.

## 1. Purpose

G8 is the **real distribution evidence** for `core.error`. G7 certified the unit / contract
seams; G8 certifies that the public DSL → script compiler → canonical IR → production
registry → durable coordinator → installed CLI distribution path delivers exactly the
semantics G7 pinned.

G8 produces no new abstractions and no production code. G8 runs the canonical `.pipeline.kts`
through the installed `pipeline-application` binary twice against the **same** `--db` and
`--control-root`, observing fresh + replay behavior in real JSON-event output.

On G8 close: `CERTIFIED = true`. S2-A1 is fully closed.

## 2. Branch / Trunk / Binary

```text
branch        : cycle/lfc2-e1-s2-legacy-catalog-burn-down
trunk SHA     : c6783f9505db8f6dc065f28724e035fefe693210 (S1 integrated)
branch HEAD   : babb86c710cdf91b9d3d7ecde42d736236cf719b (G7 close)
binary path   : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
installDist   : ./gradlew -p v2 :pipeline-application:installDist → BUILD SUCCESSFUL (UP-TO-DATE)
binary exists : -rwxr-xr-x. 1 rubentxu rubentxu 10533 sep 11 13:04
```

## 3. Fresh execution (real CLI)

### 3.1 Command

```bash
DB="/tmp/g8-error-evidence/error.db"
CTL="/tmp/g8-error-evidence/control"
BIN="v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application"

"$BIN" run --db "$DB" --control-root "$CTL" v2/compatibility/15-error.pipeline.kts \
    > /tmp/g8-error-evidence/fresh.log 2>&1
```

Flags BEFORE the positional `.pipeline.kts` argument (per CLI grammar:
`Usage: pipeline <validate|run> [--db <path>] [--resume|--rerun] [--control-root <path>] <script>`).

No `--rerun` / `--resume`. No shared state with prior runs (fresh DB + control-root).

### 3.2 Exit code

```text
FRESH_RC = 1
```

ABORTS_PIPELINE → terminal failure → CLI exit 1.

### 3.3 Fresh observable events (JSON, sequence 1..8)

```json
{"kind":"CompilationStarted","sequence":1,...}
{"kind":"CompilationFinished","sequence":2,"cacheKey":{"value":"dcc986a7..."},"diagnostics":[]}
{"kind":"RunStarted","sequence":3,"scriptPath":"v2/compatibility/15-error.pipeline.kts"}
{"kind":"StageStarted","sequence":4,"stageIndex":0,"stageName":"error-step"}
{"kind":"StepStarted","sequence":5,"stageIndex":0,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error"}
{"kind":"StepFailed","sequence":6,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error","failureKind":"USER","message":"test error message"}
{"kind":"StepFinished","sequence":7,"stageIndex":0,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error"}
{"kind":"RunFinished","sequence":8,"outcome":"failure","diagnostics":[]}
```

Fresh acceptance checks:

```text
✓ CLI exit = 1
✓ StepStarted stepType = "error"
✓ exactly one handler-originated StepFailed
    failureKind = "USER"
    message    = "test error message"
✓ StepFinished stepType = "error"
✓ RunFinished outcome = "failure"
✓ NO replay-abort INFRASTRUCTURE failure in fresh run
```

The full run carries the canonical contract:

```text
CoreErrorStep
  → CoreErrorOutput
  → StepOutcome.Failure(USER "test error message")
  → ABORTS_PIPELINE
  → terminal failure (RunFinished.outcome="failure", CLI exit=1)
```

## 4. Replay execution (real CLI, same --db / --control-root)

### 4.1 Command (no --rerun, no --resume, same DB and CTL)

```bash
"$BIN" run --db "$DB" --control-root "$CTL" v2/compatibility/15-error.pipeline.kts \
    > /tmp/g8-error-evidence/replay.log 2>&1
```

### 4.2 Exit code

```text
REPLAY_RC = 1
```

### 4.3 Replay observable events (second `RunStarted` block, sequence 1..8)

```json
{"kind":"CompilationStarted","sequence":1,...,"occurredAt":"2026-09-11T12:08:27.864Z"}
{"kind":"CompilationFinished","sequence":2,"cacheKey":{"value":"dcc986a7..."},"diagnostics":[]}
{"kind":"RunStarted","sequence":3,"scriptPath":"v2/compatibility/15-error.pipeline.kts"}
{"kind":"StageStarted","sequence":4,"stageIndex":0,"stageName":"error-step"}
{"kind":"StepStarted","sequence":5,"stageIndex":0,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error"}
{"kind":"StepFailed","sequence":6,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error","failureKind":"INFRASTRUCTURE","message":"Replay aborted for 'b19ab0b3-d19f-4d01-89c0-ba96aaee7e5f-s0-0'"}
{"kind":"StepFinished","sequence":7,"stageIndex":0,"stepIndex":0,"stepName":"error-step/error-0","stepType":"error"}
{"kind":"RunFinished","sequence":8,"outcome":"failure","diagnostics":[]}
```

Replay acceptance checks:

```text
✓ CLI exit = 1
✓ ReplayPolicy.NEVER → handler NOT invoked again
✓ ReplayDecision.ABORT surfaced as typed StepFailed
    failureKind = "INFRASTRUCTURE"
    message    = "Replay aborted for 'b19ab0b3-d19f-4d01-89c0-ba96aaee7e5f-s0-0'"
✓ RunFinished outcome = "failure"
```

## 5. USER / INFRASTRUCTURE invariant verification

The correct handler-not-invoked invariant (per G7's discovery) is:

```text
fresh + replay:
  USER-kind StepFailed count            = 1   (handler ran ONCE on fresh)
  INFRASTRUCTURE replay-abort count     = 1   (typed replay-abort surface on replay)
```

Not `total StepFailed == 1` (handler ran twice + typed replay-abort would still be 1).

Python parse of `/tmp/g8-error-evidence/fresh.log` and `/tmp/g8-error-evidence/replay.log`
(filtered by `occurredAt >= 2026-09-11T12:08:27Z` to isolate the second execution):

```text
fresh  StepFailed count   = 1
  fresh : failureKind='USER'           message='test error message'

replay StepFailed count   = 1
  replay: failureKind='INFRASTRUCTURE' message="Replay aborted for 'b19ab0b3-d19f-4d01-89c0-ba96aaee7e5f-s0-0'"

USER-kind           StepFailed count = 1   (handler invoked ONCE)
INFRASTRUCTURE-kind StepFailed count = 1   (typed replay-abort surface)
total StepFailed events              = 2   (1 fresh + 1 replay, both real, both typed)
```

```text
✓ USER failure count = 1          (handler ran exactly once)
✓ INFRASTRUCTURE replay-abort count = 1  (typed carrier on the replay execution)
✓ no --rerun / --resume used      (default durable policy observed)
✓ no control-root change between runs
```

## 6. Gate scoreboard (re-run)

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'ErrorStepContractSuiteTest' \
                                                --tests 'CoreErrorStepUnitTest' \
                                                --tests 'CoreErrorRegistryPrimaryFitnessTest' \
                                                --rerun

TEST-dev.rubentxu.pipeline.v2.application.ErrorStepContractSuiteTest.xml       tests="17" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.application.CoreErrorStepUnitTest.xml            tests="20" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.application.CoreErrorRegistryPrimaryFitnessTest.xml tests="14" failures="0" errors="0"

$ ./gradlew -p v2 :pipeline-architecture-tests:test --tests 'S3ErrorLegacyRemovedFitnessTest' --rerun

TEST-dev.rubentxu.pipeline.v2.architecture.S3ErrorLegacyRemovedFitnessTest.xml tests="12" failures="0" errors="0"

$ ./gradlew -p v2 :pipeline-application:test --tests 'EchoStepContractSuiteTest' --rerun  (S1 regression sanity)

TEST-dev.rubentxu.pipeline.v2.application.EchoStepContractSuiteTest.xml        tests="17" failures="0" errors="0"
```

```text
ErrorStepContractSuiteTest           17 PASS / 1 N.A. / 0 FAIL   (G7 evidence, preserved)
CoreErrorStepUnitTest                20/20                       (G1 evidence, preserved)
CoreErrorRegistryPrimaryFitnessTest  14/14                       (G5 evidence, preserved)
S3ErrorLegacyRemovedFitnessTest      12/12                       (G6 evidence, preserved)
EchoStepContractSuiteTest            17/17                       (S1 regression sanity)

fresh CLI run                        exit=1,  1 USER StepFailed
replay CLI run                       exit=1,  1 INFRASTRUCTURE StepFailed
```

## 7. Counter snapshot (unchanged from G7)

```text
LEGACY_PLUGIN_IDS                    = 11   (CanonicalCoreStepDecoder.kt)
CanonicalCoreStepMetadata rows       = 11   (counter-tested by S3ErrorLegacyRemovedFitnessTest)
per-Step dispatchers                 = 11   (counter-tested by S3ErrorLegacyRemovedFitnessTest)
CoreErrorStep.definition in registry = YES  (counter-tested by CoreErrorRegistryPrimaryFitnessTest)
StructuralFamily(core.error)         = Registry
requiredCapabilities                 = emptySet()
effects                              = ABORTS_PIPELINE
replayPolicy                         = NEVER
```

## 8. Production code touched in G8

**None.** G8 ran the existing installed binary, observed behavior, and recorded evidence.
G8 changes only docs (this receipt + G7 SHA correction + `STEP_INVENTORY_LFC2E0.md`
update).

```text
git status --porcelain
M docs/v2/STEP_INVENTORY_LFC2E0.md
M docs/v2/07-uat/S2_A1_CORE_ERROR_G7_CONTRACT_CERTIFICATION_RECEIPT.md   (G7 SHA correction)
?? docs/v2/07-uat/S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md
```

## 9. State machine — S2-A1 closed

```text
REGISTERED         = true    (CoreErrorStep.definition in production registry)
REGISTRY_PRIMARY   = true    (closed at G5, sha 04138db0)
LEGACY_UNREACHABLE = true    (closed at G5, sha 04138db0)
LEGACY_REMOVED     = true    (closed at G6, sha 3945c87e)
CONTRACT_SUITE     = PASS    (closed at G7, sha babb86c7)
REAL_CLI_SCENARIO  = PASS    (closed at G8, this commit)
CERTIFIED          = true    ← flipped
```

## 10. G7 SHA traceability correction

Per user directive, G7 SHA is corrected in the G7 receipt and the chain G6 → G7 → G8 is
made auditable. No amend/rebase of historical commits.

| Gate | SHA | Action |
| --- | --- | --- |
| G0  | (cycle base, pre-trunk) | pre-existing failure baseline captured |
| G1  | a3cd… | registry seam proof (CoreErrorStep behind CoreStepRegistryFactory) |
| G2  | f4d8… | corpus migration (parity tests archived) |
| G3  | 7c2a… | parity snapshot (full table preserved, transient invariants archived) |
| G4  | a7941540 | readiness fitness (no flip) |
| G5  | 04138db0 | REGISTRY_PRIMARY flip (single production edit: `LEGACY_PLUGIN_IDS -= "core.error"`) |
| G5r | 15d0e791 | receipt counter correction (LEGACY_PLUGIN_IDS stays 11 through G6) |
| G6  | 3945c87e | LEGACY_REMOVED (mechanical deletion of `core.error` legacy representations) |
| G7  | babb86c7 | CONTRACT_SUITE PASS (ErrorStepContractSuiteTest 17/1N.A./0) |
| G8  | this commit | REAL_CLI_SCENARIO PASS + CERTIFIED = true |

## 11. Hand-off

S2-A1 fully closed. `core.error` is the second legacy key to reach `CERTIFIED + LEGACY_REMOVED`
after `core.echo` (S1) and `core.sh` (LB-02 S6.8 — note: `core.sh` is `LEGACY_REMOVED` but
NOT yet `CERTIFIED` per E49).

```text
Counters:
  legacy counters                     11 / 11 / 11
  CoreErrorStep certification         CERTIFIED
  CoreEchoStep certification          CERTIFIED   (S1)
  CoreShellStep certification         LEGACY_REMOVED  (LB-02 S6.8, awaiting S2-A-certification)
```

**STOP.**

Next slice requires a separate GO: **S2-A2 = `core.sleep`** (LEGACY_PLUGIN_IDS 11 → 10).
Special attention to cancellation, timeout, replay and temporal semantics, from G0
characterization onward.
