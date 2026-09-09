# LB-02 / G3-A4.0: Sh Grounding (Legacy vs Registry)

**Cycle:** LB-02 / G3 (registry Step path) — slice A4
**Cycle base:** `f4d0ea90` (G3-A3 atomic execution seam)
**Author:** LFC-2 pipeline-application author
**Status:** A4.0 grounding complete; A4.1+ ready to execute

## Purpose

Document the current `core.sh` execution surface exactly as it exists today, and
contrast it with the registry-routed target. No redesign — only observation. This is
the input to A4.1+ decisions about which fields belong on `StepContract`/`StepDescriptor`
versus which stay at the `CanonicalRuntimeContext` capability seam.

## Current authority chain (legacy)

```text
DSL sh(...)
   ↓
canonical IR (StepNode with pluginStepId="core.sh")
   ↓
CanonicalCoreStepDecoder.decode(node)
   ↓
CanonicalCoreStepCommand.Shell(shell: ShellCommand, isScriptBlock: Boolean)
   ↓
StructuralFamilyResolver.classify → StructuralStepFamily.LegacyCore (core.sh ∈ LEGACY_PLUGIN_IDS)
   ↓
LegacyExecutionBoundary.prepare → PreparedLegacyExecution(command = CanonicalCoreStepCommand.Shell)
   ↓
CanonicalInvocationExecutor.invoke(command, runtime)
   ↓
CanonicalNodeDispatcher.dispatch(command)
   ↓
CanonicalShellNodeDispatcher.dispatch(command.shell, context)
   ↓
ShExecution.runShellCommandTyped(command, opId, runId, stageIndex, stepIndex, shOptions, controlDirRoot, eventSink)
   ↓
ShExecution.invokeShell(...)  // durable engine (DurableShellExecutor OR ProcessDurableTaskRuntime fallback)
   ↓
ShellInvocationResult (UnitValue | Stdout | Status | Failed | Interrupted)
   ↓
.toStepOutcome() → StepOutcome
   ↓
StepExecutionBoundary (lifecycle events: StepStarted / StepFailed / StepFinished)
   ↓
CommonExecutionBoundary.execute(preparedLegacy, ctx) → CommonExecutionResult(outcome, encodedOutput = null)
   ↓
CanonicalDurableRunCoordinator.journal.append(RerunOperation(output = null, status, ...))
   ↓
OperationOutput NOT populated
```

## Target authority chain (registry)

```text
DSL sh(...)
   ↓
canonical IR (StepNode with pluginStepId="core.sh")
   ↓
StepMetadataResolver composite → StepMetadata (effects, replayPolicy, recoveryPolicy)
   ↓
StructuralFamilyResolver.classify → StructuralStepFamily.Registry (after A4.8: core.sh NOT in LEGACY_PLUGIN_IDS)
   ↓
RegistryExecutionPreparation.prepare(registry, key, encodedInput, availableCapabilities)
   ↓
Capability admission: declared requiredCapabilities ⊆ available() (fail-closed)
   ↓
CommonExecutionBoundary.execute(preparedRegistry, runtime)
   ↓
RegistryExecutionBoundary.coexecute(prepared, runtime)
   ↓
StepCapabilityAccess.get<ShellOperations>(SHELL_OPERATIONS_CAPABILITY)
   ↓
ShellOperations.invoke(command, runId, stepIndex)
   ↓
ShOperationsAdapter (constructed by CanonicalRuntimeCapabilityAccess from runtime.shOptions, controlDirRoot, eventSink)
   ↓
ShExecution.invokeShell(command, opId, runId, stageIndex, stepIndex, shOptions, controlDirRoot, eventSink)
   ↓
ShellInvocationResult (same closed ADT)
   ↓
CoreShellOutput(result = ..., capturedStdout, durationMs)
   ↓
outputCodec.encode(output) → EncodedStepValue
   ↓
CommonExecutionResult(outcome = StepOutcome, encodedOutput = EncodedStepValue)
   ↓
CanonicalDurableRunCoordinator.journal.append(RerunOperation(output = OperationOutput(JsonPrimitive(...)), status, ...))
   ↓
OperationOutput POPULATED (typed O captured)
```

## Concern-by-concern table

| Concern                 | Legacy Sh authority                                                                                                                                                                                                                                                                                  | Registry Sh target                                                                                                                                                                                                                                                                |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Input type              | `CanonicalCoreStepCommand.Shell(shell: ShellCommand, isScriptBlock: Boolean)`                                                                                                                                                                                                                       | `CoreShellInput(command: ShellCommand, stepIndex: Int)` — same underlying `ShellCommand`; `stepIndex` is runtime-derived (does NOT round-trip through durable payload)                                                                                                          |
| Input codec             | Hand-written in `CanonicalCoreStepDecoder.decode` (parses `{kind:"sh", command, returnStdout?, returnStatus?, isScriptBlock}` JSON, produces `ShellCommand` with `returnMode`)                                                                                                                          | `CoreShellStep.definition.contract.inputCodec` (existing) — encodes `{kind:"shell", script, encoding?, label?, returnMode}`; decode is the inverse. Identical durable wire shape `{kind:"shell", script, returnMode}` so fingerprints stay byte-identical through the flip. |
| Output type             | `StepOutcome` only (no typed `O`); the closed `ShellInvocationResult` is collapsed inside `ShExecution` via `.toStepOutcome()`                                                                                                                                                                          | `CoreShellOutput(result: ShellInvocationResult, capturedStdout: String, durationMs: Long)` — typed `O`, the `ShellInvocationResult` ADT is preserved through the seam (A3 carrier), plus captured stdout + durationMs.                                                              |
| Output codec            | None at the seam — projection to `OperationOutput.result` is `null` (legacy)                                                                                                                                                                                                                        | `CoreShellStep.definition.contract.outputCodec` (existing G1 stub; G3 must finish `decode`) — JSON object with `kind` discriminant (`UNIT`/`STDOUT`/`STATUS`/`FAILED`/`INTERRUPTED`) + variant-specific fields + `capturedStdout`, `durationMs`.                              |
| Failure classification  | `FailureKind.SCRIPT` for non-zero exit; `FailureKind.TIMEOUT` for `Interrupted` (TIMEOUT kind) or `failure.message` for PARENT_CANCELLED; `FailureKind.INFRASTRUCTURE` for engine exceptions. Mapped through `classifyShellTerminal` → `ShellInvocationResult.Failed` → `.toStepOutcome()`.         | **Same closed algebra must hold.** `ShellOperations.invoke()` returns `ShellInvocationResult`; `CoreShellStep.handler` wraps it into `CoreShellOutput`; the boundary classifies into `StepOutcome` identically. No collapse to `StepOutcome` inside the handler — boundary classifies. |
| Required capabilities   | None declared at the legacy seam (the dispatcher reaches `ShExecution` directly via constructor injection).                                                                                                                                                                                          | `setOf(SHELL_OPERATIONS_CAPABILITY)` declared on `CoreShellStep.definition.contract.requiredCapabilities`; fail-closed admission by `RegistryExecutionBoundary`.                                                                                                              |
| RecoveryPolicy          | `RecoveryPolicy.ExternalSubprocess` declared on `CanonicalCoreStepMetadata["core.sh"]` — coordinator reads it via `metadata.recoveryPolicy` in `recoverRunningShell`.                                                                                                                                | **`RecoveryPolicy.ExternalSubprocess` MUST travel with the Step.** Today `StepContract.descriptor` has only `replayPolicy`; we must either (a) extend the descriptor with `recoveryPolicy` or (b) extend `StepContract` with a parallel field.                          |
| stdout/stderr ownership | `ShExecution.invokeShell` writes to `controlDir/jenkins-log.txt` (durable path) OR `ProcessDurableTaskRuntime` accumulates stdout+stderr into one `EchoOutputCaptured` event (non-durable fallback). One event per step; legacy semantics merged stdout+stderr.                                       | Same `ShExecution.invokeShell` is reused — `ShellOperations.invoke()` is a thin wrapper. The handler never sees the streams. **stdout must still surface in the typed `O` as `capturedStdout` and via `EchoOutputCaptured` event.** A3's typed-output projection must preserve this.      |
| cancellation            | `DurableShellExecutor` honours SIGTERM/SIGKILL via tee-gated wrapper; `ProcessDurableTaskRuntime` checks `result.cancelled` → `DurableTaskTerminal.Cancelled` → `ShellInvocationResult.Interrupted`. Both paths funnel into the same `ShellInvocationResult.Interrupted` ADT.                       | Same — handler does NOT implement cancellation. `ShellOperations.invoke()` returns the closed result.                                                                                                                                                                          |
| workspace/env           | `shOptions.workspaceRoot` (from `PipelineRun.kt`) passed through; `EnvModel.apply(shOptions.env)` (JAVA_HOME/M2_HOME prepend). `shOptions.workingDirectory` overrides `workspaceRoot` per `effectiveOptions.workspaceRoot = shOptions.workingDirectory ?: shOptions.workspaceRoot`.             | Same — `ShellOperations.invoke()` is unaware; `ShOperationsAdapter` derives `OpId` from `(runId, stepIndex)` and threads `shOptions`/`controlDirRoot`/`eventSink` from the runtime context (constructed once in `CanonicalRuntimeCapabilityAccess`).                              |

## Identified contract gaps

1. **`RecoveryPolicy` not yet on `StepContract` / `StepDescriptor`** — the registry metadata resolver (`RegistryStepMetadataResolver.composite`) constructs `StepMetadata(..., recoveryPolicy = RecoveryPolicy.None)` (the default), so a registry-routed `core.sh` would NOT trigger `recoverRunningShell`. **This must be addressed for A4.10** — we extend `StepDescriptor` (or `StepContract`) with `recoveryPolicy`. Decision deferred to A4.1 design.

2. **`CoreShellStep.outputCodec.decode()` is unimplemented** — G1 deferred it to G3/G7. We need decode to be implemented for the replay path (A4.9 RERUN/REUSE law preservation), but A4 only requires `encode` (fresh execution). `decode` lands at A5/A7.

3. **`CoreShellStep.definition` currently declares `requiredCapabilities = emptySet()`** — A4 flips this to `setOf(SHELL_OPERATIONS_CAPABILITY)`. The handler then must read the capability; capability admission must be wired through `CanonicalRuntimeCapabilityAccess` to expose a `ShellOperations`.

4. **Handler is a deterministic stub** — A4 replaces with the real handler:
   ```kotlin
   StepHandler { input, ctx ->
       val shellOps: ShellOperations = ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
       val startMs = System.currentTimeMillis()
       val result: ShellInvocationResult = shellOps.invoke(input.command, ctx.runId, ctx.stepIndex)
       val capturedStdout = ... // derived from result if Stdout/Status/UnitValue
       CoreShellOutput(result = result, capturedStdout = ..., durationMs = System.currentTimeMillis() - startMs)
   }
   ```
   The handler is small: it delegates to the capability and packages the typed `O`. **No process launching.**

5. **`core.sh` removal from `LEGACY_PLUGIN_IDS`** is the structural flip (A4.8). Until then the structural family resolver routes it as `LegacyCore` regardless of the registry.

6. **`CoreStepRegistryFactory.registry()` must seed `CoreShellStep.registerInto(this)`** — A4.7 / A4.8 step.

## Stop-condition watch (for A4.10)

The recovery gate depends on `RecoveryPolicy.ExternalSubprocess` reaching the coordinator
via the metadata resolver. The current resolver builds `StepMetadata` from the registry
contract's `descriptor` (effects, replayPolicy). If the registry-routed Step cannot carry
`RecoveryPolicy.ExternalSubprocess`, the recovery path is unreachable and A4.10 fails the
gate — and that would be a hard stop per the user's instruction.

The clean fix is to put `recoveryPolicy` on `StepDescriptor` so the registry resolver
propagates it identically to how the legacy core table does. This is the A4.1 decision
to make explicit before extending the registry metadata resolver.

## Files reviewed

| File | Role |
|---|---|
| `CoreShellStep.kt` | Existing G1 stub definition; will be the A4 entry point |
| `CoreShellInput.kt` / `CoreShellOutput.kt` | Typed I/O types (already typed) |
| `ShellOperations.kt` | Capability seam interface |
| `ShellOperationsCapabilityKey.kt` | `SHELL_OPERATIONS_CAPABILITY` token |
| `ShOperationsAdapter.kt` | Adapter from seam to `ShExecution.invokeShell` (uses runtime shOptions/controlDirRoot/eventSink) |
| `ShExecution.kt` | The certified shell engine (durable + non-durable fallback) |
| `CanonicalShellNodeDispatcher.kt` | Legacy dispatcher for `core.sh` |
| `CanonicalCoreStepDecoder.kt` | Legacy `Shell` decoder |
| `CanonicalCoreStepMetadata.kt` | Legacy metadata table; `core.sh → RecoveryPolicy.ExternalSubprocess` |
| `StructuralStepFamily.kt` | Selection rule; `core.sh ∈ LEGACY_PLUGIN_IDS → LegacyCore` |
| `RegistryStepMetadataResolver.kt` | Composite resolver; current shape `StepMetadata(effects, replayPolicy)` (recoveryPolicy = None) |
| `CanonicalRuntimeCapabilityAccess.kt` | Capability bridge (today: only `EVENT_SINK_CAPABILITY`) |
| `CanonicalDurableRunCoordinator.kt` (lines 750-848) | Recovery branch `recoverRunningShell`, gated on `RecoveryPolicy.ExternalSubprocess` |
| `RecoveryPolicy.kt` | Sealed `None | ExternalSubprocess` |
| `StepReconcilerL1.kt` | The reusable reconciler (parameterized only by `(clock, controlDirRoot)`); classified `Complete/Reattach/TimedOut/Lost` |
| `DurableShellExecutor.kt` | The reusable shell executor (control-dir wrapper, tee-gated stdout, timeout plumbing) |
| `StepContract.kt` (in `StepRegistry.kt`) | The contract surface; currently no `recoveryPolicy` field |
| `StepDescriptor` | Currently no `recoveryPolicy` field |

## Next steps (A4.1+)

1. **A4.1** — Integrate the inert supporting files (verify they compile + work). Decide
   where to put `recoveryPolicy`: extend `StepDescriptor` (simpler) or `StepContract`
   (parallel field). Update `CanonicalRuntimeCapabilityAccess` to expose
   `SHELL_OPERATIONS_CAPABILITY` through the new adapter.
2. **A4.2** — Update `CoreShellStep` to declare `recoveryPolicy = ExternalSubprocess`
   on its contract, declare `SHELL_OPERATIONS_CAPABILITY` as required, and replace the
   stub handler with the real one.
3. **A4.3** — The new handler delegates to `ShExecution.invokeShell` via the adapter
   (no process-engine reimplementation).
4. **A4.4–A4.5** — Characterize parity: same `ShellInvocationResult` ADT, same
   `FailureKind`, same captured-stdout projection.
5. **A4.6** — Capability admission tests (`SHELL_OPERATIONS` present → execute;
   missing → fail-closed).
6. **A4.7** — Registry seam proof: invoke `core.sh` via registry path while legacy
   remains authority, prove `prepare=1 / common execution=1 / ShellOperations=1 /
   process launch=1 / encodedOutput != null`.
7. **A4.8** — Flip `StructuralFamilyResolver` so `core.sh` → `StructuralStepFamily.Registry`.
8. **A4.9** — Prove fresh / replay / divergence laws hold through the registry seam.
9. **A4.10** — Prove `RecoveryPolicy.ExternalSubprocess` reaches `recoverRunningShell`
   through the registry metadata resolver, with no `if core.sh` branch.
