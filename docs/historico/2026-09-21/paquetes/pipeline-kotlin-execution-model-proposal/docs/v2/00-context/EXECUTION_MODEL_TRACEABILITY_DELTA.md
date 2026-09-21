# Execution Model Traceability Delta

This file is a merge checklist. It does not replace the repository's canonical
`TRACEABILITY.md`.

## 1. New authority chain

| Concern | Authority |
|---|---|
| architectural reset | ADR-0065 |
| runtime/replay | DURABLE_KOTLIN_EXECUTION |
| shell semantics | JENKINS_SH_CONTRACT |
| failures/cancellation | FAILURE_INTERRUPTION_MODEL |
| body-scoped steps | BLOCK_STEP_EXECUTION |
| migration | EXECUTION_MODEL_MIGRATION |
| acceptance | UAT_JENKINS_EXECUTION_PARITY |
| feasibility gate | SPIKE-016 |

## 2. Existing authority interactions

### ADR-0046

**Preserve**

- durable shell script-file pattern;
- result/log/output file strategy;
- atomic result publication;
- heartbeat/cookie;
- detach/reattach;
- script text not interpolated into wrapper.

**Amend**

- timeout should be owned by block cancellation rather than canonical shell command;
- shell task state/result API may change.

### ADR-0047

Review its `FAILED_TIMEOUT` operation-status semantics.

Do not delete historical evidence. Add a current note clarifying whether
`FAILED_TIMEOUT` remains an external reporting state while runtime control flow uses
an interruption.

### ADR-0064

Remain aligned with local-first.

ADR-0065 must not introduce remote controller/protocol machinery. `FailureRecord`
should merely avoid blocking a future adapter.

The major refactor requirement is satisfied only after its gate is fully specified
and SPIKE-016 passes.

## 3. Existing docs requiring semantic updates

### `JENKINS_FAMILIARITY.md`

Update:

- `sh` exact parameter contract;
- `returnStatus`;
- remove canonical acceptance of `timeoutMs`/`env` after wrapper migration;
- distinguish Jenkins-compatible API from Pipeline Kotlin extension API;
- compatibility level tied to differential harness.

### `RUNTIME_MODEL.md`

The existing deterministic replay concept becomes normative implementation direction.

Add:

- operation-key algorithm;
- executable artifact identity;
- replayed failures;
- block scope path;
- interruption records;
- unsafe nondeterministic API policy.

### `DSL_SPEC.md`

Correct the example return type if it currently assumes `.stdout` from Jenkins `sh`.

Recommended Jenkins-compatible form:

```kotlin
val tag = sh(script = "git describe --tags", returnStdout = true).trim()
```

A rich `.stdout` result belongs to a separate extension step.

Declare `script` executable, not a command accumulator.

### `STEP_PLUGIN_SDK.md`

Extend descriptor:

```text
takesBody
bodyInvocationModel
contextContributions
interruptionPolicy
return schema
```

Change durable failure contract to structured records.

### `ROADMAP.md`

Insert/reference the EM programme.

INC-039 becomes EM-1/EM-3 traceable work.

### `UAT_SCENARIOS.md` / `UAT_ACCEPTANCE_MATRIX.md`

Reference UAT-JEP suite and make its critical subset a release gate.

### `TRACEABILITY.md`

Add rows:

| Capability | Spec | ADR | Milestone | UAT |
|---|---|---|---|---|
| typed durable terminal result | JENKINS_SH_CONTRACT | ADR-0065 | EM-1 | JEP-008..010 |
| central lifecycle | FAILURE_INTERRUPTION_MODEL | ADR-0065 | EM-2 | JEP-029..030 |
| Jenkins sh parity | JENKINS_SH_CONTRACT | ADR-0065 | EM-3 | JEP-001..010 |
| first-class body steps | BLOCK_STEP_EXECUTION | ADR-0065 | EM-4 | JEP-014..023 |
| durable timeout | BLOCK_STEP_EXECUTION | ADR-0065 | EM-5 | JEP-011..013 |
| scripted replay | DURABLE_KOTLIN_EXECUTION | ADR-0065 | EM-8 | JEP-024..028 |

### `MANIFEST.md`

Regenerate hashes only after merge-ready contents are final. Never hand-copy stale
hashes from this proposal package.

## 4. Code-to-contract mapping

| Current code | New contract |
|---|---|
| `ShExecution.runShellCommandTyped` | JENKINS_SH_CONTRACT §9 |
| `DurableShellExecutor` | terminal/snapshot split + FailureRecord |
| `CanonicalDurableRunCoordinator` | StepExecutionBoundary + top-level RunOutcome |
| `CanonicalShellNodeDispatcher` | typed shell command invocation |
| `CanonicalCoreStepDecoder` | schema decode only; no semantic recovery |
| `DslCompiledPipelineCompiler.rewriteWorkflowControl` | replace with BlockStepNode |
| `PipelineDsl.StageScope.sh` | declarative façade vs executable ScriptedScope façade |
| `PipelineDsl.ScriptScope` | replace by executable suspend scripted body |

## 5. Test disposition

Existing tests are not deleted because they conflict with the proposal.

For each affected test:

1. identify the product requirement it claims;
2. decide whether the requirement remains authoritative;
3. if yes, migrate the test to the new API;
4. if no, replace it with a test for the superseding contract;
5. document why in the implementation PR.

Never weaken an assertion to keep a transitional implementation green.

## 6. Legacy compatibility

Potential adapters:

```text
legacy String shell result
     ↓
LegacyShellAdapter
     ↓
new typed invocation
```

```text
old serialized shell node payload
     ↓
schema decoder v1
     ↓
ShellCommand(returnMode derived from old flags)
```

Adapters must be directional. New code must not use legacy contracts internally.
