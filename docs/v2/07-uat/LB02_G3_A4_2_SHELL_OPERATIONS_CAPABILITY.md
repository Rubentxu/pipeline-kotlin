# LB-02 / G3-A4.2 — ShellOperations Capability Seam

## Scope

A4.2 declares the typed `SHELL_OPERATIONS_CAPABILITY` for `core.sh` and proves the
capability-routed handler discipline: the handler asks for the typed seam, the seam
is implemented by an adapter that delegates to the existing `ShExecution.invokeShell`
substrate, and process-engine authority stays in `ShExecution`. Coordinator and
canonical dispatcher have zero `core.sh` knowledge. Recovery is preserved at the
descriptor (`RecoveryPolicy.ExternalSubprocess`) per A4.1 and is **not** exercised
in this slice.

## Change set (atomic)

| File | Purpose |
|---|---|
| `v2/pipeline-application/.../CoreShellStep.kt` | Real capability-routed handler: `ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)` → `ShellOperations.invoke` → typed `CoreShellOutput`. `requiredCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY)`. |
| `v2/pipeline-application/.../ShellOperations.kt` | Already-quarantined typed port (`invoke(command, runId, stepIndex) → ShellInvocationResult`). Becomes unquarantined. |
| `v2/pipeline-application/.../ShellOperationsCapabilityKey.kt` | Already-quarantined capability token. Becomes unquarantined. |
| `v2/pipeline-application/.../durable/ShOperationsAdapter.kt` | Already-quarantined adapter implementing `ShellOperations` by delegating to `ShExecution.invokeShell`. Becomes unquarantined. |
| `v2/pipeline-domain/.../step/StepRegistry.kt` | `StepHandler.execute` and `StepInvoker.invoke` become `suspend` (LB-02 / G3-A4.2). Pure-function handlers ignore the suspend modifier. |
| `v2/pipeline-application/.../CoreShellStepTest.kt` | Migrated: handler now invokes `ShellOperations` exactly once; capability declaration is asserted; non-Stdout variants project 1:1. |
| `v2/pipeline-application/.../CoreEchoSeamTest.kt` | `runBlocking` wrappers around `invoker.invoke(...)` calls (suspend chain). |
| `v2/pipeline-application/.../durable/RegistryExecutionBoundaryTest.kt` | A3-gap fix: project `outcome.outcome` from `CommonExecutionResult` carrier (tests assumed the pre-A3 return type). |
| `v2/pipeline-domain/.../step/StepRegistryTest.kt` | `runBlocking` wrappers (suspend chain). |
| `v2/pipeline-domain/.../StepDescriptorBodyMetadataTest.kt` | A4.1 follow-on: 14-arg positional test updated to include the new `recoveryPolicy` slot. |
| `v2/pipeline-application/.../A4_2ShellOperationsCapabilityTest.kt` | 14 tests covering A4.2.1/2/3/5/6/7. |
| `AGENTS.md` | New rules: "Pre-decode durable metadata belongs to StepDescriptor (A4.1)" + "Capability-routed handler discipline (A4.2)". |
| `docs/v2/07-uat/LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY.md` | This durable doc. |

## Authority chain (target)

```
CoreShellStep.handler
        ↓ asks
ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
        ↓ binds
ShOperationsAdapter       (constructed once at runtime bridge)
        ↓ calls
ShExecution.invokeShell   (existing durable substrate; single authority for
                            stdout capture, EchoOutputCaptured emission, exit
                            classification, cancellation, control-dir, env)
        ↓ returns
ShellInvocationResult     (typed closed ADT)
        ↓ projected 1:1 by handler
CoreShellOutput
        ↓ encoded by outputCodec
EncodedStepValue          (carried by CommonExecutionResult)
```

The handler NEVER reaches `CanonicalRuntimeContext`, the journal, the durable-shell
substrate, or the events package. `EchoOutputCaptured` authority lives in
`ShExecution`; the handler does not emit it.

## Why `StepHandler.execute`/`StepInvoker.invoke` became `suspend`

The legacy path uses suspend functions throughout: `coexecute` → `coexecute`'s
`handler.execute` was non-suspend → which meant handlers could not call
`ShExecution.invokeShell` (suspend) without `runBlocking`. Wrapping a blocking
bridge inside a non-suspend handler would have pinned the dispatcher thread for the
duration of every shell invocation.

Making `StepHandler.execute` (and the erased `StepInvoker.invoke`) `suspend` lets the
chain stay suspend end-to-end. Pure-function handlers (e.g. `core.echo`) ignore the
modifier; the suspend chain is invisible to them. No ADR forbids suspend on
`StepHandler`; the change is a typed-seam extension, not a domain semantics change.

## Laws captured (frozen by `A4_2ShellOperationsCapabilityTest`)

1. **Capability declaration** — `CoreShellStep.contract.requiredCapabilities == setOf(SHELL_OPERATIONS_CAPABILITY)`. (`A4-2-2`)
2. **Capability distinct from EVENT_SINK_CAPABILITY** — two distinct typed tokens, no collision. (`A4-2-2`)
3. **Success delegation** — handler invokes `ShellOperations.invoke` exactly once with the same `command`, `runId`, `stepIndex`. (`A4-2-6`)
4. **1:1 projection** — each `ShellInvocationResult` variant projects 1:1 into `CoreShellOutput`; `capturedStdout` is lifted from `Stdout.value` only. (`A4-2-6`)
5. **Prepare-time admission** — `RegistryExecutionPreparation.prepare` returns `Ready` when capability is available; `Rejected("missing required capabilities...")` when it is not. (`A4-2-6`)
6. **Wrong capability fails closed** — supplying a wrong capability still rejects; admission is typed, not "do you have any capability?". (`A4-2-6`)
7. **Post-admission re-check** — handler throws when capability is missing (no silent work). (`A4-2-6`)
8. **No handler-side event emission** — `CoreShellStep` does NOT import `EventSink` / `EchoOutputCaptured` / call `eventSink.append`. (`A4-2-5`)
9. **No process-engine rewrite** — `CoreShellStep` does NOT import `ProcessBuilder`, `Runtime.getRuntime`, `Runtime.exec`, `bash -c`, `DurableShellExecutor`. (`A4-2-5`)
10. **Narrow port** — `ShellOperations` does NOT leak `CanonicalRuntimeContext`, `Journal`, `StepRegistry`, `ProcessBuilder`. (`A4-2-1`)
11. **Adapter delegates** — `ShOperationsAdapter` calls `ShExecution.invokeShell` and contains no `ProcessBuilder`/`Runtime.getRuntime`. (`A4-2-3`)
12. **Recovery preserved** — descriptor keeps `RecoveryPolicy.ExternalSubprocess` + `ReplayPolicy.RERUN`. (`A4-2-7`)
13. **No recovery substrate in handler** — `CoreShellStep` does NOT import `StepReconcilerL1`, `Reconciler`, `recoverRunningShell`, `controlDirRoot`. (`A4-2-7`)

## Evidence (fresh XML canaries)

```
A4_2ShellOperationsCapabilityTest          14 tests / 0 failures / 0 errors
CoreShellStepTest                          11 tests / 0 failures / 0 errors
A4_1DescriptorRecoveryCharacterizationTest  7 tests / 0 failures / 0 errors
RegistryStepMetadataResolverTest            3 tests / 0 failures / 0 errors
CoreEchoSeamTest                            7 tests / 0 failures / 0 errors
EchoStepContractSuiteTest                  17 tests / 0 failures / 0 errors
RegistryDurableSpineTest                    5 tests / 0 failures / 0 errors
RegistryExecutionPreparationTest            5 tests / 0 failures / 0 errors
RegistryExecutionBoundaryTest               6 tests / 0 failures / 0 errors  (A3 carrier gap closed)
RegistryExecutionOutcomeTest                4 tests / 0 failures / 0 errors
StepRegistryTest (domain)                   8 tests / 0 failures / 0 errors
StepOutcomeEncodedOutputDistinctionTest     4 tests / 0 failures / 0 errors
TOTAL: 91 tests / 0 failures / 0 errors
```

## Closed A3 gap

`RegistryExecutionBoundaryTest` was not migrated to the A3 `CommonExecutionResult`
carrier (`outcome.outcome`). Its two assertions (`outcome == StepOutcome.Success`)
have been failing since A3. A4.2 catches and fixes this in-scope as part of the
L1 evidence; the fix is a one-line projection (`outcome.outcome`).

## Coordinator knowledge of `core.sh` = 0

Verified by inspection: `CanonicalDurableRunCoordinator.kt` continues to gate
recovery on `metadata.recoveryPolicy == ExternalSubprocess` (NOT on `core.sh`).
`CanonicalNodeDispatcher.kt` continues to dispatch via the closed command ADT.
A4.2 changes neither file; the recovery path becomes reachable through the
registry metadata resolver with zero per-Step branches.

## Gating facts for A4.10 (recovery)

Unchanged from A4.1:
- `StepReconcilerL1` is parameterized only by `(clock, controlDirRoot, opId)`.
- The recovery branch in `coordinator.recoverRunningShell` gates on
  `metadata.recoveryPolicy == ExternalSubprocess`, **NOT** on `core.sh`.
- A4.1 already propagates `ExternalSubprocess` through registry metadata for `core.sh`.
- A4.2 keeps the recovery substrate untouched; A4.10 proves the recovery path
  works through the registry-resolved metadata without per-Step branches.

## Stop condition check

After A4.2, the handler still does not depend on `CanonicalCoreStepCommand.Sh`
(only on the typed `ShellOperations` port). The recovery branch is unreachable
through `core.sh` only because `core.sh` is still in `LEGACY_PLUGIN_IDS` —
`A4.8` flips that. The stop condition
("recovery irreducibly depends on `CanonicalCoreStepCommand.Sh`") is **not** met.

## Next

A4.3 — make `CoreShellStep` execute really through the port and produce the typed
output needed for `CommonExecutionResult`. The runtime bridge must supply
`SHELL_OPERATIONS_CAPABILITY` bound to `ShOperationsAdapter` so the registry
boundary can route `core.sh` through the registry path with the legacy row still
authoritative (until A4.8).
