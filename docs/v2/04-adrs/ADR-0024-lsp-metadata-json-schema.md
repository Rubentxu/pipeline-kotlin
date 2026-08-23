# ADR-0024: LSP Metadata JSON Schema (KSP-emitted)

- **Status:** Accepted for V2 design
- **Date:** 2026-08-23
- **Decision owners:** Pipeline Kotlin maintainers
- **M2-R3 Implementation:** M2-R3 (A-lite, final M2 slice)

## Context

The Step Plugin SDK v2 needs to expose machine-readable metadata about each step type
for IDE/editor consumption (LSP integration). The metadata includes:

- Step identity (`stepId`, `name`)
- Parameter signatures (`parameters: [{name, type, required, index}]`)
- Execution location (`location`)
- Replay policy (`replayPolicy`)
- Jenkins surface mapping (`jenkinsSurface: "<step>|<plugin>|F<n>"`)
- Failure bridge kind (`failureKindBridge`)

The question is: what wire format should this metadata use, and where should it live?

## Decision

Emit per-step JSON resource files at `META-INF/pipeline/step-metadata/{stepId}.json`
using the KSP processor's `finish()` hook. The JSON is:

```json
{
  "schema": "pipeline.dev/lsp/v1",
  "stepId": "core.sh",
  "name": "sh",
  "parameters": [
    { "name": "context", "type": "StepContext", "required": true, "index": 0 },
    { "name": "argv", "type": "kotlin.collections.List<kotlin.String>", "required": true, "index": 1 }
  ],
  "location": "WORKER",
  "replayPolicy": "RERUN",
  "failureKindBridge": "PROCESS",
  "jenkinsSurface": "sh|workflow-durable-task-step|F3"
}
```

The schema version sentinel (`"pipeline.dev/lsp/v1"`) allows future format negotiation.
The resources are loaded at runtime via `LspMetadataLoader.loadAll(classLoader)` which
uses `ClassLoader.getResources()` to enumerate all `META-INF/pipeline/step-metadata/`
resources.

## Rationale

1. **ClassLoader-friendly**: Standard Java resource loading mechanism — no custom class
   scanning, no reflection, no third-party libraries.
2. **Decoupled from Kotlin source**: The metadata is emitted as a build artifact,
   consumable by any tool that can read JAR resources.
3. **No protobuf dependency**: Using protobuf would add a third-party dependency
   (F-ARCH-001 constraint: "no new third-party deps"). JSON uses the same escape
   rules as `JsonEventLog.jsonString()` which is already in the codebase.
4. **Future-proof for migration**: When M4 ships protobuf support, the same
   `META-INF/pipeline/step-metadata/` path will carry `.pbf` files alongside `.json`
   files. `LspMetadataLoader.loadAll()` remains the consumer API.

## Alternatives Considered

1. **Kotlin source generation** (e.g., `object StepMetadata { val core_sh = LspMetadata(...) }`) —
   rejected because IDEs need a machine-readable format, not Kotlin source.
2. **Protobuf** — rejected because it adds a third-party dependency and requires
   schema compilation step. JSON is already available via `JsonEventLog.jsonString()`.
3. **YAML** — rejected because YAML parsing requires an additional library and the
   use case (LSP metadata) does not benefit from YAML's readability.
4. **Separate service endpoint** (`GET /metadata/{stepId}`) — rejected because it
   requires a running server and is not co-located with the step JAR.

## Consequences

- KSP processor emits one JSON file per `@Step` annotated function.
- `LspMetadataLoader.loadAll(classLoader)` loads all JSON resources from the classpath.
- The `schema: "pipeline.dev/lsp/v1"` sentinel enables future format negotiation.
- No `$schema`/`$id`/`$definitions` (full JSON Schema) — flat JSON only. Full JSON
  Schema is M3+ tooling territory.

## Migration Path (M4/E5-01)

When M4 ships protobuf support:
1. KSP emits both `.json` (current) + `.pbf` (new) at the same path prefix.
2. `LspMetadataLoader` gains a `loadAllProto()` sibling preferring protobuf.
3. JSON emission is deprecated for one M4 cycle before removal in M5.
4. Consumer contract (`loadAll()`) is preserved throughout.

## Revisit When

- The LSP metadata schema needs `$schema`/`$id`/`$definitions` validation.
- Protobuf becomes a requirement and the dependency can be managed.
- IDE consumption requires a different wire format (e.g., Binary XML, MessagePack).
