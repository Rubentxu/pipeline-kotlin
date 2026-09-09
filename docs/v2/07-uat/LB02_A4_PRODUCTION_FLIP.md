# LB-02 / A4 — `core.sh` production flip to the registry path

## Scope and traceability

- **Milestone:** LB-02 / A4
- **Backlog:** production flip from the legacy canonical dispatcher to the open
  Step registry for `core.sh`
- **Exit criterion:** a production `core.sh` invocation is structurally classified
  as `Registry`, admitted through the declared `SHELL_OPERATIONS_CAPABILITY`,
  uses `CoreShellStep.descriptor` as its metadata authority, and reaches the
  shared shell substrate without a legacy-Sh decode or dispatch.
- **Gate:** `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test`, the targeted L1 closure,
  and the real `04-sh.pipeline.kts` CLI smoke.

This document records the authorized A4 production flip. It follows the
pre-flight authority audit in `LB02_A4_PRE_FLIGHT_AUDIT.md`.

## Production change

The structural classifier remains generic: `StructuralFamilyResolver.classify`
selects `LegacyCore` only for membership in `LEGACY_PLUGIN_IDS`; it has no
`StepKey == "core.sh"` branch. Removing `core.sh` from that set changes only
that membership fact. With `CoreShellStep` registered by the single composition
root, the same classifier therefore resolves `core.sh` to `Registry`.

The production composition and admission changes are:

1. `CoreStepRegistryFactory.registry()` registers `CoreShellStep` alongside
   `CoreEchoStep`.
2. `CanonicalRuntimeCapabilityAccess` provides the declared
   `SHELL_OPERATIONS_CAPABILITY`, backed by `ShOperationsAdapter` constructed
   from the explicit canonical runtime context.
3. `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS` excludes `core.sh`.
4. `RegistryStepMetadataResolver` consequently obtains `core.sh` metadata from
   `CoreShellStep.descriptor`, including `RecoveryPolicy.ExternalSubprocess`.

No coordinator StepKey branching or per-call-site registration is introduced.

## Payload bridge discovered by the real CLI smoke

The first post-flip execution of `04-sh.pipeline.kts` failed before
`StepStarted`. The failure was correctly fail-closed during registry
preparation:

```text
schema mismatch for step 'core.sh': shell payload kind must be 'shell'
```

The cause was a pre-existing wire-shape seam between the DSL compiler and the
new registry codec:

| Producer / consumer | payload shape |
| --- | --- |
| `DslCompiledPipelineCompiler.shellPayload` | `{"kind":"sh","command":"...","isScriptBlock":false,"returnStdout":false}` |
| Legacy `CanonicalCoreStepDecoder` | consumes that `sh` / `command` / boolean-return shape |
| `CoreShellStep.inputCodec.encode` | emits its typed contract form: `{"kind":"shell","script":"...","returnMode":"NONE"}` |

The registry codec is therefore deliberately **decode-tolerant but
fail-closed**:

- accepts only `kind == "sh" || kind == "shell"`;
- reads either `command` or `script`;
- reads `returnMode`, or maps Jenkins-compatible `returnStdout` /
  `returnStatus` to the closed `ShellReturnMode` ADT;
- rejects missing script data, foreign kinds, and the invalid combination of
  `returnStdout && returnStatus` before effects.

This is an adapter-boundary compatibility bridge, not a new dynamic public
contract. The handler still receives the single typed `CoreShellInput`, and no
unsupported input reaches a process launch.

## Legacy quarantine remains intact

This flip deliberately does **not** delete or repair the legacy path:

- `CanonicalCoreStepCommand.Shell` remains present.
- The Sh branch in `CanonicalCoreStepDecoder` remains present.
- `CanonicalShellNodeDispatcher` remains present.
- `CanonicalCoreStepMetadata.table["core.sh"]` remains physically present for
  rollback and later burn-down.
- Other legacy keys remain in `LEGACY_PLUGIN_IDS`.

Recovery does not need `CanonicalCoreStepCommand.Shell`: production metadata
resolution now projects `CoreShellStep.descriptor.recoveryPolicy`, which remains
`ExternalSubprocess`. No recovery dependency graph into the legacy command was
found, so the user-mandated stop condition is not triggered.

## Acceptance coverage

`A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` pins the structural and capability
facts: registry classification, legacy-set exclusion, descriptor metadata
resolution, fail-closed absence from an empty registry, production capability
admission, routing composition, and a real shell subprocess emitting exactly
one `EchoOutputCaptured` event.

`CoreShellStepTest` additionally pins both accepted input wire shapes and
rejects foreign kinds. A real CLI run of `04-sh.pipeline.kts` is required as the
consumer-level proof because it exercises the DSL compiler's actual payload.

## Deferred work

`core.sh` remains `IMPLEMENTED_UNCERTIFIED`. A5 must migrate the corpus and
establish `LEGACY_UNREACHABLE` before deleting the legacy command, decoder,
dispatcher, and metadata row. Only after certification may `AGENTS.md` be
updated with `core.sh` as the reference effectful/recoverable Step.

## Evidence

Fresh post-implementation XML canaries, run with the targeted A4 L1 closure:

```text
A4_1DescriptorRecoveryCharacterizationTest     7 / 0 / 0  SHA 5a82adef93b63ecb
A4_2ShellOperationsCapabilityTest             14 / 0 / 0  SHA 95df045acbf2eaec
A4_3TypedShellOutputIntegrationTest           20 / 0 / 0  SHA c3c1648b0831bda9
A4_8LegacyRegistrySemanticParityTest          12 / 0 / 0  SHA fccb4c46c1948c9c
A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test        10 / 0 / 0  SHA 6be67c40d02a6fcf
CoreShellStepTest                             12 / 0 / 0  SHA ea3ce2a8fcd36d55
RegistryExecutionBoundaryTest                  6 / 0 / 0
GenericRegistryExecutionCarrierTest            6 / 0 / 0
FamilyRouterTest                               4 / 0 / 0
ExecutionBoundaryFactoryTest                   4 / 0 / 0
G7_CoreShellOutputCodecRoundTripTest          34 / 0 / 0  SHA 9f930a940537a735
EchoStepContractSuiteTest                     17 / 0 / 0  SHA 9278e945ac99ddac
RegistryStepMetadataResolverTest               5 / 0 / 0  SHA 115b7831629fe0e0
CoordinatorFixtureTest                         3 / 0 / 0
TOTAL                                        154 / 0 / 0
```

The real distribution CLI smoke also passed:

```text
pipeline-application run v2/compatibility/04-sh.pipeline.kts
CompilationStarted → CompilationFinished → RunStarted → StageStarted →
StepStarted → EchoOutputCaptured("hello from sh\\n") → StepFinished →
StageFinished → RunFinished(success)
```

`UatCompat001CorpusSmokeRunTest` remains the frozen pre-existing baseline at
`2 tests / 2 failures / 0 errors`. Its failures are the known canonical-ID gap
for already-registry-migrated `core.echo` and `core.withCredentials`, not an A4
`core.sh` failure. The A4 change did not widen that baseline.

The architecture guardrail also passed incrementally:
`timeout 600 ./gradlew -p v2 :pipeline-architecture-tests:test` →
`BUILD SUCCESSFUL` (33 tasks up-to-date). It covers the fitness constraints that
prohibit central concrete-Step dispatch and forbidden dependency direction.

### Module-suite follow-up

A later owning-module run exposed one A4-owned stale characterization:
`CanonicalCoreStepCommandRegistryTest` still expected `core.sh` in
`LEGACY_PLUGIN_IDS`. It was corrected in `d87a9188` while retaining the physical
`CanonicalCoreStepCommand.Shell` subtype for rollback. The focused class then
passed **15 / 0 / 0**. After that correction, the installed distribution reran
`04-sh.pipeline.kts` and again produced the exact accepted event sequence:
`CompilationStarted → CompilationFinished → RunStarted → StageStarted →
StepStarted → EchoOutputCaptured("hello from sh\\n") → StepFinished →
StageFinished → RunFinished(success)`.

The two contemporaneous CLI failures
(`CanonicalInMemoryCliTest` and `CliCompileErrorExitsOneTest`) are the already
recorded `core.echo` canonical-ID gap, identical to `UatCompat001`, and do not
exercise the A4 `core.sh` route.

### Full gate receipt

One final incremental `timeout 600 ./gradlew -p v2 check` was launched after the
implementation commit. It reached broad pre-existing failures in
`pipeline-domain`, `pipeline-scripting-kotlin24`, and existing application UAT
classes, then exhausted the fixed 600-second budget before producing a final
Gradle summary. It is **not** a green receipt and is not used as A4 success
evidence. The fresh focused L1 XMLs, real distribution smoke, and unchanged
`UatCompat001` baseline above are the scoped evidence for this slice. The broad
failure set requires its own base-vs-head investigation and is outside the A4
change closure.
