# WU-RP-040 R5.1 — Design

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Phase:** Design
**Base SHA:** `6f04cf87` (specification commit on `wu/rp-053r-red-fixtures`)

This document binds the three fixes in the specification to the concrete code
changes. Each change is local, additive, and bounded.

## D-1 — CliParser complexity reduction

**File:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt`
**Type:** local extraction, no public API change.

Introduce an internal mutable `ParseState` value class (or 7 mutable locals
captured by a small helper) and an `applyOption` private function:

```kotlin
private class ParseState(
    var dbPath: String? = null,
    var durableRunPolicy: DurableRunPolicy = DurableRunPolicy.ReusePriorRun,
    var controlRoot: String? = null,
    var workspace: String? = null,
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    val pluginJars: MutableList<String> = mutableListOf(),
)

private fun applyOption(
    option: String,
    args: Array<String>,
    index: Int,
    state: ParseState,
): ApplyResult {  // = either OptionApplied(nextIndex), Rejected(CliError), Conflict(CliError)
    val value = args.getOrNull(index + 1)
        ?: return ApplyResult.Rejected(CliError.MissingOptionValue(option))
    when (option) {
        "--db" -> { state.dbPath = value; return ApplyResult.Applied(index + 2) }
        ...
    }
}
```

The `parse` function keeps:

- the empty check,
- the command `when`,
- the loop driver,
- the script-path finalization.

`applyOption` carries the per-option logic. Target complexity after the
refactor: `parse` ≤ 22, `applyOption` ≤ 12.

Alternative considered and rejected: keeping `parse` as-is and applying
`@Suppress("CyclomaticComplexMethod")` like `DslCompiledPipelineCompiler.encodePayload`.
Rejected because the parse function is the canonical example of a pure
decision that AGENTS.md §6 expects to be testable; refactoring it improves
readability instead of just hiding the metric.

## D-2 — Rename PipelineDslSteps.kt → StepSpec.kt

**Files:**
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDslSteps.kt`
  → `.../dsl/StepSpec.kt` (rename only).
- `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/FArchLfc1LegacyDslRemovedTest.kt`
  → update line 25 path string to `StepSpec.kt`.

**Type:** mechanical rename, no semantic change.

`StepSpec` is the sole top-level declaration in the file after
`193b6133 refactor(scripting): partition StepSpec source declarations`. The
test asserts that no legacy DSL artifact lives at the old path; now that the
new path is canonical, the test's purpose migrates from "removed" to
"canonical". The test body is unchanged apart from the path string.

Alternative considered and rejected: renaming the type to `PipelineDslSteps`.
Rejected because the type name `StepSpec` matches the public surface used
in every DSL consumer and the domain's `BranchSpec`.

## D-3 — JsonAccessors @Suppress

**File:** `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/PipelineJson.kt`
**Type:** annotation + comment, no behavior change.

Add `@Suppress("TooManyFunctions")` to `object JsonAccessors` (line 100) with
a justifying comment that:

- explains the object holds six paired `requiredX` / `XOrNull` extension
  functions (one pair per primitive type) plus `requiredObject` and
  `requiredArray`,
- references the precedent `DslCompiledPipelineCompiler.encodePayload`
  carrying `@Suppress("CyclomaticComplexMethod")` for the same reason
  (closed dispatch over typed IR),
- notes the alternative — split into `JsonScalarAccessors` and
  `JsonAggregateAccessors` — was rejected because it forces every codec
  consumer to update imports without reducing the surface detekt measures.

The detekt rule `TooManyFunctions` operates per-file; splitting the object
into two files would just produce two objects each under the threshold but
no consumer-level benefit.

Alternative considered and rejected: raising the global detekt threshold
from 11 to 12. Rejected because that would weaken the gate for every other
file in the codebase, not just `JsonAccessors`.

## Architectural consistency

Each fix is local and consistent with the existing hex/functional rules:

- D-1 keeps `CliParser.parse` pure (no I/O, no global state, returns
  `CliParseResult` ADT).
- D-2 is mechanical rename with no semantic impact.
- D-3 documents a deliberate suppression that mirrors an existing pattern
  in the codebase.

The slice does not introduce new modules, new dependencies, new public
APIs, new persistence formats, new event types, or new CLI flags.

## Build & verification order

1. Apply D-3 (1 line) and commit. Run `:pipeline-step-sdk:api:detekt` to
   confirm closure of C-3.
2. Apply D-2 (rename + 1-line test update) and commit. Run
   `:pipeline-scripting-api:detekt` to confirm closure of C-2 and
   `:pipeline-architecture-tests:test` for the path update.
3. Apply D-1 (refactor) and commit. Run `:pipeline-application:test` (focused
   on `MainCliParsingTest`) and `:pipeline-application:detekt`.
4. Run full round gate: `cd v2 && timeout 1200 ./gradlew check --no-daemon
   --rerun-tasks --console=plain`.

Reference implementation consulted: none applicable. The three detekt
findings are static-analysis thresholds defined in
`v2/config/detekt/detekt.yml`; their suppression pattern follows
in-repo precedent.
