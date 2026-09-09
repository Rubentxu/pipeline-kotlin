# LB-02 / A4 — Pre-flight audit for `core.sh` production flip

## WU1 — current `core.sh` authorities

| # | Authority | File | Current role | Classification |
|---|-----------|------|--------------|----------------|
| 1 | DSL representation | `DslCompiledPipelineCompiler.kt` (lines 392, 410, 493) | Emits `core.sh` as a `pluginStepId` in canonical IR | unrelated (output form stays the same; what matters is who decodes) |
| 2 | Classifier structural | `StructuralFamilyResolver.classify()` | `if stepKey.value in LEGACY_PLUGIN_IDS -> LegacyCore` | **must change** (remove `"core.sh"` from `LEGACY_PLUGIN_IDS`) |
| 3 | Metadata authority (legacy) | `CanonicalCoreStepMetadata.table["core.sh"]` | Provides `effects/replayPolicy/recoveryPolicy` for pre-decode durable resolution | **must remain temporarily** for burn-down; production path will not consult it once `core.sh` is removed from `LEGACY_PLUGIN_IDS` (the composite resolver falls back to `CanonicalCoreStepMetadata` only when the key IS in `LEGACY_PLUGIN_IDS`) |
| 4 | Metadata authority (registry) | `RegistryStepMetadataResolver.resolve()` (lines 33-46) | Same as #3, but resolves through the open registry | **must change** — by removing `"core.sh"` from `LEGACY_PLUGIN_IDS`, the composite falls into the registry branch and reads `CoreShellStep.StepDescriptor` |
| 5 | `CanonicalCoreStepCommand.Shell` | `CanonicalCoreStepDecoder.kt` (lines 75-89, 231-251) | Closed sealed-interface subtype carrying the typed sh payload | **must remain temporarily** for rollback/burn-down. The dispatcher still has `when (command)` over the sealed hierarchy (compiler forces exhaustiveness). Removing the subtype is a separate slice. |
| 6 | Legacy decoder | `CanonicalCoreStepDecoder.decode` (lines 225-368) | Decodes `StepNode.payload.encoded` into `CanonicalCoreStepCommand.*` | **must remain temporarily**. Production path stops reaching it for `core.sh` because `StructuralFamilyResolver` will return `Registry` for `core.sh`, routing to `RegistryExecutionPreparation` instead. **Reachable only through other legacy keys.** |
| 7 | Legacy metadata row | `CanonicalCoreStepMetadata.table["core.sh"]` | The single row in the legacy metadata table | **must remain temporarily** — physically present, reachable only when explicitly asked by key. With `"core.sh" ∉ LEGACY_PLUGIN_IDS`, `metadata("core.sh")` becomes a callable-but-orphaned row. Burn-down will remove the row. |
| 8 | Legacy command family | `CanonicalCoreStepCommand.Shell` | Sealed interface variant | **must remain temporarily** (see #5) |
| 9 | `CanonicalShellNodeDispatcher.dispatch` | `durable/CanonicalShellNodeDispatcher.kt` | Calls `ShExecution.runShellCommandTyped` | **must remain temporarily** — still reachable by other call sites (e.g. tests). Production flips away from it via the family classifier. |
| 10 | `CanonicalNodeDispatcher.dispatch` | `durable/CanonicalNodeDispatcher.kt` (lines 23, 39) | Closed `when (command)` over sealed hierarchy | **must remain temporarily** (exhaustiveness). The Sh branch is reachable only when `LegacyCore` family is selected, which no longer happens for `core.sh`. |
| 11 | `LegacyExecutionBoundary.prepare` | `durable/LegacyExecutionBoundary.kt` | Pre-decode strategy prep for legacy-core family | **must remain temporarily** for other legacy keys |
| 12 | `LegacyExecutionAdapter.adapt` | `durable/CommonExecutionBoundary.kt` | Wraps the legacy `CanonicalInvocationExecutor` | **must remain temporarily** for other legacy keys |
| 13 | `FamilyRouter.decide` | `durable/FamilyRouter.kt` | Per-step key-aware routing decision | unrelated to core.sh flip (it operates on `stepKey` for the binary legacy/seamed decision; production uses the structural classifier) |
| 14 | `ExecutionBoundaryFactory.build` | `durable/ExecutionBoundaryFactory.kt` | Composition seam for the boundary | unrelated (the canonical coordinator at line 631 calls `StructuralFamilyResolver.classify` directly, not this factory) |
| 15 | Composition root | `Main.kt` (line 690) | `stepRegistry = CoreStepRegistryFactory.registry()` | **must change** — `CoreStepRegistryFactory.registry()` must register `CoreShellStep` alongside `CoreEchoStep` |
| 16 | Core definitions authority | `CoreStepRegistryFactory.kt` (lines 27-30) | `registry()` only registers `CoreEchoStep` | **must change** — add `CoreShellStep.registerInto(this)` |
| 17 | `StructuralStepFamily` (sealed ADT) | `durable/StructuralStepFamily.kt` | `LegacyCore` / `Registry` structural families | unrelated (the ADT shape stays; only the classifier's `LEGACY_PLUGIN_IDS` lookup changes) |
| 18 | `CanonicalRuntimeCapabilityAccess` | `durable/CanonicalRuntimeCapabilityAccess.kt` | Bridges runtime context to `StepCapabilityAccess` | **must change** — must expose `SHELL_OPERATIONS_CAPABILITY` backed by an `ShOperationsAdapter` constructed from the runtime context. Today it only exposes `EVENT_SINK_CAPABILITY`. |
| 19 | `CanonicalDurableRunCoordinator` | `durable/CanonicalDurableRunCoordinator.kt` (line 631) | Calls `StructuralFamilyResolver.classify(step.pluginStepId, stepRegistry)` | unrelated (the call site stays; only the classifier's logic changes) |
| 20 | Tests pinning legacy Sh dispatch | `CanonicalShellNodeDispatcherTest.kt`, `DualExecutionSeamCharacterizationTest.kt`, `DurableProtocolInvocationCharacterizationTest.kt`, `CanonicalCoreStepCommandRegistryTest.kt` | Pin the legacy path today | **must remain temporarily** — these tests exercise the legacy `Shell` command / dispatcher and MUST keep working after the flip. They prove the legacy path is intact for rollback. |
| 21 | Recovery | `StepReconcilerL1` | Driven by descriptor `recoveryPolicy` (read at coordinator level) | unrelated to flip code; the registry-resolved `CoreShellStep.descriptor.recoveryPolicy = RecoveryPolicy.ExternalSubprocess` is what `RegistryStepMetadataResolver` projects. The reconciler reads the resolved `StepMetadata.recoveryPolicy` regardless of family. |

## What does NOT change in this slice

- `CanonicalCoreStepCommand.Shell` (data class) — present.
- `CanonicalCoreStepDecoder.decode` Sh branch — present.
- `CanonicalCoreStepMetadata.metadata("core.sh")` — present (orphaned for production but callable).
- `CanonicalShellNodeDispatcher.dispatch` — present (no production caller for `core.sh` anymore).
- `CanonicalNodeDispatcher.dispatch` `Shell` branch — present (other call sites can still call `dispatch(CanonicalCoreStepCommand.Shell(...))`).
- `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS` row for `"core.sh"` — physically removed in this slice, but the row in `CanonicalCoreStepMetadata.table` stays.

## What changes in this slice

1. `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS`: remove `"core.sh"` (single line removal).
2. `CoreStepRegistryFactory.registry()`: add `CoreShellStep.registerInto(this)`.
3. `CanonicalRuntimeCapabilityAccess`: extend the `provided: Map<StepCapability, Any>` to include `SHELL_OPERATIONS_CAPABILITY` → `ShOperationsAdapter` constructed from `CanonicalRuntimeContext`. The adapter binds `runIdString = context.runId`, `shOptions = context.shOptions`, `controlDirRoot = context.controlDirRoot`, `eventSink = context.eventSink`. Fail-closed: if the adapter cannot be constructed (e.g. `controlDirRoot == null` path returns null), the capability is unavailable.
4. `CoreShellStep.registerInto` is the public extension point (already exists). No new wiring code per call site.
5. **NO** `when (stepKey == "core.sh")` branches anywhere. The classifier still emits `LegacyCore`/`Registry` based on `LEGACY_PLUGIN_IDS` membership, with `"core.sh"` no longer in that set.
6. **NO** `coordinator(... CoreShellStep ...)` per call site; everything flows from `CoreStepRegistryFactory.registry()`.

## Structural-classifier invariant preserved

`StructuralFamilyResolver.classify("core.sh", registry=CoreStepRegistryFactory.registry())`:
- BEFORE: returns `LegacyCore` (because `"core.sh" in LEGACY_PLUGIN_IDS`).
- AFTER: returns `Registry` (because `"core.sh" ∉ LEGACY_PLUGIN_IDS` AND registry resolves it via `CoreShellStep.registerInto`).

The classifier is still a pure function over `(stepKey, registry)`; no per-step name branching introduced.

## Stop-condition check (pre-emptive)

The user-mandated stop condition: **"Si después del flip descubres que el recovery real necesita todavía `CanonicalCoreStepCommand.Sh` para funcionar, DETENTE."**

`StepReconcilerL1` reads `StepMetadata.recoveryPolicy` from the **resolved** metadata (coordinator line ~480 via `metadataResolver.resolve(step.pluginStepId)`). After the flip, that resolution goes through `RegistryStepMetadataResolver.composite(registry)` → `registry.definition("core.sh")` → `CoreShellStep.descriptor.recoveryPolicy = RecoveryPolicy.ExternalSubprocess`. The same enum value that the legacy row had. **The recovery machinery is unchanged.** No new recovery code is needed.

## Risk register

- `Main.kt` is the **only** production composition root. There are tests with their own composition (`CoordinatorFixture`, `UatCompat001`, etc.). Each must continue to compile.
- The capability bridge change affects all production paths, not just `core.sh`. We add `SHELL_OPERATIONS_CAPABILITY` to `available()` — but `get(SHELL_OPERATIONS_CAPABILITY)` is only invoked from `CoreShellStep.handler`, so other registry Steps are unaffected.
- The `LegacyExecutionBoundary.prepare` step input requires `node.pluginStepId` AND decodes the payload into a typed command. After the flip, `core.sh` no longer reaches `LegacyExecutionBoundary.prepare` — it reaches `RegistryExecutionPreparation.prepare` instead. The legacy branch remains compiled and tested.
- `CanonicalRuntimeCapabilityAccess` is shared by **all** family-Registry invocations, not just `core.sh`. Adding `SHELL_OPERATIONS_CAPABILITY` to it is structurally safe: `CoreEchoStep` doesn't declare it on its contract, so `RegistryExecutionPreparation.prepare` would reject `core.echo` from needing it (no overlap).
