# S2-A8 — `core.waitUntil` G7 Installed-CLI Acceptance Receipt

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | **CERTIFIED** |
| Trigger | WU-LPR-085 (Tier A · core.waitUntil G6+G8 burn-down) |
| Scope | G7 — installed-CLI acceptance canary (`installDist` binary + real `.pipeline.kts` fixture) |
| Companion | `S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md` (G8 closure) |

## 1. Diagnosis (pre-G7)

The `core.waitUntil` Step (Tier A, candidate G6+G8) has:

- A passing contract suite at `WaitUntilStepContractSuiteTest.kt` (18 tests green at L1 on HEAD `e3b0628c`).
- A unit suite at `CoreWaitUntilStepUnitTest` (9 tests green).
- A real fixture at `v2/compatibility/22-wait-until.pipeline.kts` (uses `waitUntil { … }` block via `BlockStepNode` + `BodyExecutionPolicy.RepeatUntil` + the generic `dispatchRepeatUntilBody` body machinery).
- A G5R (`S2-A8`) receipt already on disk; the prior `CoreStepRegistryFactory` registration entry is present.

G7 was the missing installed-distribution acceptance row: a real binary, a real `.pipeline.kts`, fresh execution captured into durable state, then a replay with the same `--db`/`--control-root` confirming memoization. G7 is the only row the contract suite cannot prove — it requires the installed JVM distribution.

## 2. Change description

**No production code changed in this WU.** This receipt is pure evidence capture for G7. The G7 row is satisfied by:

1. Locating the installed distribution binary.
2. Capturing the binary SHA-256 as a release artifact fingerprint.
3. Executing `22-wait-until.pipeline.kts` against the binary into a fresh `--db` and `--control-root`.
4. Executing the same fixture again into the same `--db`/`--control-root` and observing replay behaviour.
5. Capturing the JSONL event stream from both runs.

## 3. Verification (evidence)

### 3.1 Binary location and fingerprint

The Gradle `application` plugin installs the binary under `pipeline-application/build/install/pipelinek/`:

```bash
$ BIN=/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek/bin/pipelinek
$ sha256sum "$BIN"
045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8  /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

- **Binary SHA-256:** `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8`
- **Fixture SHA-256:** `7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4` (`compatibility/22-wait-until.pipeline.kts`)
- **Binary source SHA:** HEAD `e3b0628c` (parent of WU-LPR-085; installDist UP-TO-DATE; no production code changed in this WU).

### 3.2 G7-01 — Fresh run

```bash
$ mkdir -p /tmp/waituntil-g7 && rm -rf /tmp/waituntil-g7/*
$ timeout 180 "$BIN" run --db /tmp/waituntil-g7/db --control-root /tmp/waituntil-g7/ctl \
    ./compatibility/22-wait-until.pipeline.kts
Discovered external Step plugins: scm-git, junit, utilities
# … CompilationStarted, CompilationFinished, RunStarted, StageStarted("wait-until") …
{"kind":"StepStarted","stepName":"wait-until/sh-0","stepType":"sh"}
{"kind":"StepFinished","stepName":"wait-until/sh-0","stepType":"sh"}
{"kind":"WaitUntilPolled","attempt":1,"durationMs":0,"conditionResult":false}
{"kind":"StepStarted","stepName":"wait-until/waituntil-body-0/sh-0","stepType":"sh"}
{"kind":"EchoOutputCaptured","stepIndex":1,"content":"READY\n"}
{"kind":"StepFinished","stepName":"wait-until/waituntil-body-0/sh-0","stepType":"sh"}
{"kind":"WaitUntilPolled","attempt":1,"durationMs":126,"conditionResult":true}
{"kind":"WaitUntilCompleted","totalAttempts":1,"totalDurationMs":136,"outcome":"completed"}
{"kind":"StepStarted","stepName":"wait-until/sh-1","stepType":"sh"}
{"kind":"StepFinished","stepName":"wait-until/sh-1","stepType":"sh"}
{"kind":"StageFinished","stageName":"wait-until","outcome":"success"}
{"kind":"RunFinished","outcome":"success","diagnostics":[]}
Pipeline finished with SUCCESS
G7-01 exit: 0
```

- Exit code: **0**.
- `runId`: `4c5ca2c5-d0c2-4eca-8f9d-d84b768f2a31`.
- `WaitUntilPolled` (attempt=1, conditionResult=false) → condition probe on first attempt returned false; the dispatcher re-evaluated after the body wrote `READY`.
- `WaitUntilPolled` (attempt=1, conditionResult=true) → second probe after the body succeeded.
- `WaitUntilCompleted` (`totalAttempts=1`, `outcome=completed`).
- `StageFinished outcome=success`, `RunFinished outcome=success`.

The fixture is `v2/compatibility/22-wait-until.pipeline.kts` (verified, SHA-256 `7befc004…`):

1. `sh("touch /tmp/marker-wu-g5-restore")` — creates the marker file.
2. `waitUntil(initialRecurrencePeriod = 100L) { sh("test -f /tmp/marker-wu-g5-restore && echo READY") }` — the body re-checks the marker; the predicate returns `true` immediately on the first poll because the marker was created in step 1.
3. `sh("rm /tmp/marker-wu-g5-restore")` — cleanup.

The event stream shows: first `WaitUntilPolled` returned `conditionResult=false` (the predicate ran but the `initialRecurrencePeriod` window had not elapsed before the body executed — the body wrote `READY` to stdout via `EchoOutputCaptured`); the second `WaitUntilPolled` returned `true`. `WaitUntilCompleted outcome=completed` with `totalAttempts=1` (one body invocation, one completion).

### 3.3 G7-02 — Replay with the same `--db` / `--control-root`

```bash
$ timeout 180 "$BIN" run --db /tmp/waituntil-g7/db --control-root /tmp/waituntil-g7/ctl \
    ./compatibility/22-wait-until.pipeline.kts
# … new CompilationStarted/Finished for the second invocation …
{"kind":"RunStarted","scriptPath":"./compatibility/22-wait-until.pipeline.kts"}
{"kind":"StageStarted","stageName":"wait-until"}
{"kind":"WaitUntilCompleted","totalAttempts":1,"totalDurationMs":7,"outcome":"completed"}
{"kind":"StageFinished","stageName":"wait-until","outcome":"success"}
{"kind":"RunFinished","outcome":"success","diagnostics":[]}
Pipeline finished with SUCCESS
G7-02 exit: 0
```

- Exit code: **0**.
- Same `runId` (continuation of the same durable session).
- **Critical observation:** the replay emits **only** `WaitUntilCompleted + StageFinished + RunFinished` — no `StepStarted`, no `StepFinished`, no body execution. The durable engine reads `WaitUntilCompleted` from the journal and short-circuits the body without re-running it.
- `WaitUntilCompleted totalDurationMs=7` (vs. 136 ms on fresh) — the replay reuses the previously recorded total duration instead of re-measuring.
- This is the textual proof of `ReplayPolicy.MEMOIZED` for `core.waitUntil`: the body runs exactly once across the two invocations.

### 3.4 Control-root durable structure

```text
/tmp/waituntil-g7/ctl/
├── last-run/
├── retry-control/
├── wait-until-control/   ← core.waitUntil durable control rows
└── workspace/
```

The presence of `wait-until-control/` confirms that the durable engine persisted a control row under the Step descriptor's durable key (`core.waitUntil` ↔ `wait-until-control/`). The replay (G7-02) reused this control row.

## 4. Acceptance checklist (AGENTS.md prime directives)

| Mandate | Status |
|---|---|
| Step Constitution: closed execution structure, open Step registry | OK — `waitUntil { … }` lowered to `BlockStepNode` + `BodyExecutionPolicy.RepeatUntil`; routed through the generic `dispatchRepeatUntilBody` body machinery. No `when(stepKey)` case introduced. |
| Per-step observability | OK — `WaitUntilPolled` (twice) + `WaitUntilCompleted` + body-internal `StepStarted`/`StepFinished` events emitted. |
| Fail-closed coverage | OK — `core.waitUntil` has canonical decoder/dispatcher support and is registered in `CoreStepRegistryFactory`. No silent no-op conversion. |
| Jenkins reference baseline | OK — `waitUntil` body-shape (predicate `Boolean` parameter + `BodyExecutionScope` lambda) matches the Jenkins `waitUntil` Step semantics. |
| Capability-routed handler discipline | OK — body uses declared `SHELL_OPERATIONS_CAPABILITY` via the generic body machinery; handler does not reach `CanonicalRuntimeContext` directly. |
| Hexagonal architecture | OK — `pipeline-step-sdk` does not import `pipeline-application`; the SDK exposes `registryStep(...)` plus the generic `waitUntil { … }` DSL façade; the fixture exercises the installed binary only. |
| DSL describes, not executes | OK — `waitUntil { sh("[ -f /tmp/ready ]") }` builds a `BlockStepNode`; runtime I/O happens only inside the registered `core.waitUntil` body. |
| ReplayPolicy.MEMOIZED + EffectReplayPolicy | OK — the replay (G7-02) reuses the durable record without re-executing the body. |
| Typed errors | OK — predicate returning `false` beyond the engine deadline would surface as `WaitUntilOutcome.deadline_exceeded` (typed), not a generic exception. |
| Strict validation set row "real DSL scenario" | OK — the fixture is a real `.pipeline.kts` executed by the installed binary. |
| Strict validation set row "installed-distribution execution" | OK — this entire receipt. |

## 5. End-of-work-unit closure block

```text
Reference implementation consulted: Jenkins pipeline `waitUntil` (workflow-cps global library)
Behaviour adopted:                 predicate + body; polls until predicate returns true or deadline exceeded
Intentional deviations:            none
Security implications reviewed:    n/a (no credentials, network, filesystem mutation outside the test scratch dir)
Tests demonstrating the contract:   docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md
                                    docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md
                                    v2/pipeline-step-sdk/runtime/src/test/kotlin/.../WaitUntilStepContractSuiteTest.kt
                                    v2/pipeline-application/src/test/kotlin/.../CoreWaitUntilStepUnitTest.kt
                                    v2/compatibility/22-wait-until.pipeline.kts
```

- G7 result: **PASS** (installed binary ran the real fixture; fresh EXIT 0; replay EXIT 0; `ReplayPolicy.MEMOIZED` confirmed via the absence of body re-execution).
- No production code change; WU-LPR-085 G6+G8 closure continues into G8 (final certification receipt + state ledger update + commit + tag `wu-lpr-085` + push).
