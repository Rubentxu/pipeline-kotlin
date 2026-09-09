# LB-02 / A5.1 — Sh corpus inventory and migration boundary

## Scope

A5 migrates **ordinary execution surfaces** for `core.sh` to the same registry
composition used by production:

```text
CoreStepRegistryFactory.registry()
+ RegistryStepMetadataResolver.composite(registry)
+ CommonExecutionBoundary / family routing
+ CanonicalRuntimeCapabilityAccess
```

A5 does not remove any legacy code. The legacy command, decoder, dispatcher, and
metadata row stay physically present until `LEGACY_UNREACHABLE` is mechanically
proven and the later B1-B5 burn-down begins.

## Classification

### A. Behavioural / durable corpus: migrate to registry composition

| Surface | Why it matters | A5 action |
| --- | --- | --- |
| `durable/DurableShellCommandTest` | Shell success, failure, streams, durable output | Migrate its coordinator composition to `CoordinatorFixture.default`; preserve all assertions. |
| `durable/CanonicalDurableRunCoordinatorTest` | Fresh, replay, divergence, operation journal laws | Replace ordinary `core.sh` fixtures with production-like registry composition. |
| `durable/CanonicalCoordinatorScopeStackTest` | Block/scope propagation around Sh | Migrate ordinary Sh fixtures, retaining scope laws. |
| `durable/DurableProtocolInvocationCharacterizationTest` | Effective execution signal / replay protocol | Use its existing common-seam recorder to assert `PreparedRegistryExecution`. |
| `UatStep001ShExecutionTest` / `UatStep001ShFailureStepFinishedCountTest` | Real process success, failure, event cardinality | Retain public behavior and assert registry execution family. |
| `UatLocal003ReturnStdoutTest`, `UatLocal005EnvSpecialCharsTest` | Jenkins `sh` return/stdout and environment semantics | Preserve exact output/env behavior, add family proof only at common seam. |
| `UatDurable002DivergenceFailsClosedTest`, `UatDurableDefaultReuseCliTest` | Durable divergence and reuse | Retain durable laws, migrate composition. |
| `ErrorHandlingTest`, `UatLocal012ErrorHandlingTest` | FailureKind and `catchError` / `warnError` interaction | Preserve outcome semantics while running normal Sh through registry. |

### B. Recovery / process corpus: decisive registry migration

| Surface | Why it matters | A5 action |
| --- | --- | --- |
| `UatLocal001KillDuringShTest` | Kill while shell process is running | Prove registry prepare/descriptor metadata feeds existing process recovery. |
| `UatLocal002ResumeAfterKillTest` | Restart/resume | Prove `ExternalSubprocess` recovery follows registry `StepDescriptor`. |
| `UatLocal004TimeoutTest` | Cancellation/timeout process-tree semantics | Preserve cancellation behavior and registry family. |
| `UatLocal006LostHeartbeatTest` | LOST classification | Preserve reconciler semantics with registry-derived metadata. |
| `UatLocal007SandboxProfileTest` | Reattach/profile recovery | Preserve recovery behavior without legacy command dependency. |
| `UatDurable003ScriptBlockReplayTest`, `UatDurable004RetrySurvivesRestartTest`, `UatDurable006KillDuringInProgressTest`, `UatDurable007DivergenceMismatchTest`, `UatDurable009KillResumeBranchTest` | Script block replay, retry, kill/resume | Verify the recovery graph: `StructuralRegistry → StepDescriptor.recoveryPolicy → StepMetadata → StepReconcilerL1`. |
| `pipeline-step-sdk/runtime/.../StepReconcilerL1Test`, `DurableShellTerminalAdapterTest` | Pure recovery substrate | Keep unchanged unless a concrete legacy command is found. They are substrate tests, not execution-family tests. |

### C. Registry / architecture proof corpus: retain and extend

| Surface | Role |
| --- | --- |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | A4 authority proof. Extend into A5 final all-surface proof rather than adding per-Step branches. |
| `CoreShellStepTest`, `A4_1DescriptorRecoveryCharacterizationTest`, `A4_2ShellOperationsCapabilityTest`, `A4_3TypedShellOutputIntegrationTest`, `A4_8LegacyRegistrySemanticParityTest`, `G7_CoreShellOutputCodecRoundTripTest` | Typed contract, metadata, capability, output, parity, codec. Retain. |
| `RegistryStepMetadataResolverTest`, `FamilyRouterTest`, `ExecutionBoundaryFactoryTest`, `GenericRegistryExecutionCarrierTest`, `RegistryExecutionBoundaryTest` | Generic open-registry/family-routing seams. Extend only with generic prepared-family assertions. |
| `DslCompiledPipelineCompilerTest`, `PipelineRuleParityTest`, `UatLocal005CorpusUntouchedTest` | Compiler/PipelineRule/corpus producer boundary. Verify generated `core.sh` reaches registry when executed. |
| `:pipeline-architecture-tests:test` | Fitness guardrail. Keep as A5 final architecture gate. |

### D. Explicit legacy compatibility tests: quarantine temporarily, then delete in burn-down

These tests intentionally construct or dispatch legacy Sh. They are not
ordinary-surface evidence and MUST NOT be used to justify retaining legacy
execution after A5:

- `CanonicalShellNodeDispatcherTest`
- `CanonicalCoreStepDecoderTest`
- legacy Sh cases in `DualExecutionSeamCharacterizationTest`

They remain only as rollback characterization until `LEGACY_UNREACHABLE` is
proved. B1-B5 removes their subject matter along with the legacy artifacts.

### E. Legacy-model obsolete tests: retire with the model, do not migrate blindly

- `CanonicalCoreStepCommandRegistryTest` Shell subtype assertion
- `CoreLegacyStepMetadataResolverTest` `core.sh` row assertion
- legacy-only Sh fixtures in `CanonicalCoreStepDecoderTest`

These describe the quarantined model, not the desired registry runtime. They
must be deleted or narrowed during B1-B4 when their physical model is deleted.
They are explicitly **not** candidate ordinary execution tests.

## Non-execution references excluded from migration

Domain IR/validator/serializer tests mentioning `PluginStepId("core.sh")` are
not execution-path dependencies: `PipelineIdsTest`, `BlockStepNodeRoundTripTest`,
`CompiledExecutionPlannerTest`, `CompiledPipelineValidatorTest`,
`StepDescriptorRegistryTest`, `OpIdBodyPathTest`, and LSP metadata tests. They
remain unless their data contract changes in the burn-down.

## A5 acceptance order

1. Migrate A ordinary surfaces without semantic re-baselining.
2. Migrate B recovery surfaces. Stop if normal recovery irreducibly reaches
   `CanonicalCoreStepCommand.Shell` and report the exact graph.
3. Extend C with a mechanically counted all-surface proof.
4. Quarantine D and E, then prove all ordinary surfaces report:

   ```text
   registry execution > 0
   legacy Sh dispatch = 0
   ```

Only then may `core.sh = LEGACY_UNREACHABLE` be recorded. Legacy removal and
certification are out of this A5.1 inventory slice.
