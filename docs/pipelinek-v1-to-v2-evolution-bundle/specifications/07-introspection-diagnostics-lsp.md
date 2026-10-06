# Specification — Unified introspection, diagnostics and LSP v2

## 1. Objective

Replace V1 hard-coded/heuristic tooling with projections of real V2 metadata.

## 2. Introspection service

```kotlin
interface IntrospectionService {
    fun root(): RuntimeResource
    fun steps(query: StepQuery = StepQuery()): List<StepResource>
    fun step(id: PluginStepId): StepResource?
    fun plugins(): List<PluginResource>
    fun libraries(): List<LibraryResource>
    fun capabilities(): List<CapabilityResource>
}
```

Exact method names are not frozen; responsibility is.

The service is read-only and deterministic for a frozen composition snapshot.

## 3. Sources

It projects from existing authorities:

- `StepRegistry`;
- `StepDescriptor`;
- `StepProviderMetadata`;
- `LspMetadata`;
- plugin/library admitted artifact catalogue;
- profile providers;
- run read services when run-specific resources are requested.

## 4. Actionable diagnostics

Create typed diagnostic enrichment over compiler/CLI/admission failures.

Example categories:

- `UNKNOWN_STEP`;
- `AMBIGUOUS_STEP`;
- `MISSING_CAPABILITY`;
- `INPUT_SCHEMA_MISMATCH`;
- `PLUGIN_NOT_INSTALLED`;
- `PLUGIN_REJECTED`;
- `LIBRARY_NOT_RESOLVED`;
- `LIBRARY_PLUGIN_REQUIREMENT_UNSATISFIED`;
- `INCOMPATIBLE_API_RANGE`.

Suggestions are derived from the real catalogue. No static list of step names.

## 5. Similarity suggestions

Unknown symbol suggestions MAY use a deterministic string-distance algorithm over registered names/aliases. The algorithm is advisory only and cannot change resolution semantics.

## 6. LSP v2

The V2 LSP SHALL consume the same introspection snapshot plus scripting diagnostics/source mapping.

Minimum capabilities:

- completion;
- hover;
- signature help;
- diagnostics;
- document symbols;
- code actions for deterministic fixes/suggestions.

A plugin added through the supported plugin mechanism appears in completion/signature help without LSP source-code changes.

## 7. LSP metadata evolution

Current `pipeline.dev/lsp/v1` resources remain compatible input. If richer schema is required, introduce `v2` additively and keep the loader able to negotiate supported versions during migration.

Do not create another descriptor authority solely for LSP.

## 8. Diagnostic action links

Machine-readable diagnostics may include affordance actions such as:

```json
{
  "rel": "describe",
  "command": ["pipelinek", "api", "step", "http.request", "--json"]
}
```

## 9. Exit criteria

- plugin install/remove affects CLI and LSP from the same catalogue;
- unknown Step suggestions are catalogue-driven;
- compiler source locations remain correct;
- no LSP hard-coded Step list exists in production;
- code actions never mutate runtime state by themselves.

## 10. Compiler phase/origin and warm diagnostics

GR-009/016: enrich existing raw diagnostics with phase/origin and stable codes where available; preserve original severity, message and source coordinates. Tooling consumes the same effective profile and admitted dependency snapshot as the runtime host. The Kotlin experimental compiler backend stays private.

Compile diagnostics accompany the artifact; evaluation/runtime diagnostics are produced afresh. Warm hits preserve real deprecation/unused-return compile diagnostics with mappings to the current document. Type-error source changes miss and fail compilation normally; fresh evaluation still reports construction errors. Failed compilations/evaluations are not cached. Reader-configuration INFO remains structured evidence; human verbosity may control its presentation without blanket filtering. Synthetic location is not itself a reason to discard a diagnostic.

Never derive completion signatures or cache availability from an invented Step catalogue/default profile. Reconcile any new diagnostic metadata/event representation with accepted SDK schema/versioning. Parser flags/commands shown to agents require actual installed syntax fixtures.
