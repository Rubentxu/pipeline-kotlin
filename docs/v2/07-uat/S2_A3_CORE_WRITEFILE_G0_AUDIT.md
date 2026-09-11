# S2-A3 / G0 — core.file.writeFile baseline audit

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A3 (`core.file.writeFile`)
> Gate: **G0 — baseline / pre-existing state**
> Date: 2026-09-11T14:48Z · Base SHA: `f6fbde11` (post S2-A2 close)

## Legacy source-of-truth inventory (all present at base)

1. **Catalogue entry**: `CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` contains
   `"core.file.writeFile"`.
2. **Metadata row**: `CanonicalCoreStepMetadata["core.file.writeFile"] =
   StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)`.
3. **Decoder**: `CanonicalCoreStepCommand.WriteFile(file, text, encoding)` data
   class + `WRITE_FILE_PLUGIN_ID` decode branch (`kind == "writeFile"`).
4. **Dispatcher**: `CanonicalWriteFileNodeDispatcher` — delegates to the certified
   SDK substrate `dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor`
   (atomic write, path-traversal guard, `.v2` reserved-dir guard), ensures the
   stage workspace exists via `WorkspaceResolver`, then emits `FileWritten`
   (path, sha256, size, atomicallyMoved) and returns `StepOutcome.Success`.
5. **DSL**: `PipelineDsl.kt:1302` `writeFile(file, text, encoding = "UTF-8")`;
   `DslCompiledPipelineCompiler` re-maps `StepSpec.WriteFile` to
   `core.file.writeFile` with typed payload.

## Canonical legacy envelope (fingerprint authority until G5)

```json
{"kind":"writeFile","file":"out.txt","text":"hello","encoding":"UTF-8"}
```

(verify against `DslCompiledPipelineCompiler` payload emission at G1; the
registry codec MUST emit byte-identical envelopes.)

## Observable legacy behavior (characterization)

- Stage workspace `<controlRoot>/workspace/<safeName>-<idx>` auto-created.
- Atomic move (`atomicallyMoved = true`), sha256 + size in `FileWritten`.
- Failure surfaces: path escaping workspace, reserved `.v2` target → typed
  failure via `IllegalArgumentException` from the executor guard (fail-closed).
- `ReplayPolicy.MEMOIZED`, effect `WRITES_WORKSPACE`.

## Pre-existing test baseline

- `CompatibilityCorpusTest.fixture17WriteFile` — PASS at base (fresh CLI,
  writeFile + sh cat composition).
- `UatLocal009TopStepsTest.CR-U9-012` (cross-step writeFile + archiveArtifacts)
  — PRE-EXISTING FAILURE (archiveArtifacts gap; LB02_G0 #5). NOT a writeFile
  defect: the write itself succeeds; the archive pickup is the known gap.
- SDK substrate tests: `FileWriteExecutorTest` (pipeline-step-sdk:files) green.

## G0 conclusion

Slice approved. The legacy dispatcher is a thin adapter over the certified
`FileWriteExecutor`; the registry candidate (G1) MUST reuse the same substrate
through a narrow `WORKSPACE_OPERATIONS` capability (mirror of
`SHELL_OPERATIONS_CAPABILITY`), keeping single-writer and atomicity semantics
identical. The `FileWritten` event remains emitted by the substrate/adapter
layer, not by the handler.
