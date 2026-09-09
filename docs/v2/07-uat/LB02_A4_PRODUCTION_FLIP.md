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
