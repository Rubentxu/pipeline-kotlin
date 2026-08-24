# ADR-0023: Jenkins Surface Levels (F0-F3)

- **Status:** Accepted for V2 design
- **Date:** 2026-08-23
- **Decision owners:** Pipeline Kotlin maintainers
- **M2-R3 Implementation:** M2-R3 (A-lite, final M2 slice)

## Context

The Jenkins familiarity contract requires a measurable way to classify how closely a V2
pipeline step maps to its Jenkins counterpart. A flat "compatible/not compatible" boolean
is insufficient because:

1. **Naming-only matches** (F0) are common in early migration — same name, different semantics.
2. **Surface-level matches** (F1) require parameter shape equivalence.
3. **Behavioral compatibility** (F2) requires observable semantics to match for documented cases.
4. **Automatic migration** (F3) requires a working migrator that can convert Jenkins usage.

## Decision

Define four compatibility levels as an enum with an integer `level` accessor:

```kotlin
enum class CompatibilityLevel(val level: Int) {
    NAMING(0),       // F0 — same name + general concept
    SURFACE(1),      // F1 — name + main parameters equivalent
    BEHAVIORAL(2),   // F2 — observable semantics compatible for documented cases
    MIGRATION(3),    // F3 — migrator can convert Jenkins usage automatically
}
```

The `level` accessor enables ordering: F3 > F2 > F1 > F0. Comparisons like
`level >= F2` express "behaviorally compatible or better".

## Rationale

- **Compile-time safety**: Using an enum gives compile-time verification that only valid
  levels are used, versus a flat `String` which allows typos.
- **Ordering**: The `level: Int` accessor allows ordinal comparisons without custom
  comparison logic.
- **Extensibility**: Adding new levels (F4, F5) is additive and non-breaking, though
  would require a schema version bump in the LSP metadata wire format.
- **Minimal footprint**: The `jenkinsSurface` field stores a triple string
  (`"<step>|<plugin>|F<n>"`) — no structural change to `StepDescriptor`.

## Alternatives Considered

1. **Flat `String` values** (`NAMING`, `SURFACE`, `BEHAVIORAL`, `MIGRATION`) — rejected
   because it provides no ordering and allows typos at runtime.
2. **Boolean flags** (`isNaming`, `isBehavioral`, etc.) — rejected because it allows
   contradictory combinations and grows with each new level.
3. **External catalog** (JSON file mapping step names to levels) — rejected because it
   introduces an out-of-band dependency and is not co-located with the step definition.

## Consequences

- Every `@Step` annotated function that claims Jenkins familiarity MUST specify a
  `CompatibilityLevel` via `@JenkinsSurface(compatibility = ...)`.
- The KSP processor extracts `@JenkinsSurface` metadata and emits it as part of the
  `jenkinsSurface` field in `GeneratedStepDescriptors`.
- Corpus fixtures and UAT tests assert specific F-levels for the known steps
  (`echo`, `sh`, `error`, `sleep` are F3/MIGRATION).

## Revisit When

- A Jenkins step is added that cannot be classified into F0-F3.
- The F-level taxonomy is found to be insufficient for describing future migration paths.
- A new F-level (F4+) is needed and the schema version can be bumped.
