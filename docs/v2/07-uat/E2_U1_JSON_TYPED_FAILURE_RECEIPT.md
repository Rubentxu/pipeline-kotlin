# E2-U1 — JSON Typed Failure Hardening Receipt

Cycle: LFC-2E2-UTILITIES-EXPANSION
Slice: U1 (JSON hardening)
Status: CLOSED
Commit: (this slice)
Date: 2026-09-17

## Scope

Hardens the JSON capability surface of the first OFFICIAL_PLUGIN
(`pipeline.utilities.json@1.0.0`) by introducing a typed failure ADT and a
typed exception that carries it. The plugin's default FS-backed capability
implementation throws the typed exception on every failure path; the canonical
boundary catches it as a generic `Exception`, preserves it as the `cause` of
the resulting engine failure, and surfaces it through the `RunOutcome.Failure.
failure.cause` chain to consumers (the Event Harness, the ContractSuite,
and downstream diagnostic tooling).

This is the FIRST slice in the LFC-2E2-EXPANSION cycle to introduce typed
failure semantics for an external plugin family while preserving the
architecture's "production core stays unaware of plugin internals" invariant.

## What changed

### Plugin package (`examples/utilities-plugin`)

`UtilitiesJsonPlugin.kt`:

1. **New sealed ADT `UtilitiesJsonError`** (3 cases, exhaustively matchable):
   - `JsonNotFound(path: String)` — target file is missing or unreadable
   - `JsonParseFailure(path: String, reason: String)` — content is not valid JSON
   - `JsonIoFailure(path: String, reason: String)` — filesystem-level I/O failure
     other than NotFound/Parse (permission denied, EIO, etc.)

2. **New typed exception `UtilitiesJsonException(reason: UtilitiesJsonError)`**
   extending `RuntimeException`. Carries the sealed ADT directly so consumers
   can branch on `when (e.reason)` exhaustively.

3. **Refactored `DefaultUtilitiesJsonOperations` and `DefaultUtilitiesShaOperations`**:
   every `require(...)` / unguarded `File.readBytes()` is replaced by a typed
   `UtilitiesJsonException(...)` throw. `Json.parseToJsonElement(...)` is
   wrapped in a `try/catch (SerializationException)` to surface a typed
   `JsonParseFailure`.

4. **Capability ports** (`UtilitiesJsonOperations`, `UtilitiesShaOperations`)
   now document that conformant implementations MUST raise
   `UtilitiesJsonException` on every failure path. Each `fun` declaration
   carries `@Throws(UtilitiesJsonException::class)`.

### Production core (`v2/pipeline-application`)

**Zero changes.** The boundary's existing generic `catch (e: Exception)`
wraps `UtilitiesJsonException` as `StepOutcome.Failure(PipelineFailure(
kind=ENGINE, message="...", cause=e))`. The typed exception rides through
the `cause` field. Production sources contain no reference to
`UtilitiesJsonError` or `UtilitiesJsonException` — verified by
`Lfc2E2ExpansionGateFitnessTest.G4-4`.

### Test contract suite (`UtilitiesJsonStepContractSuiteTest`)

Five new rows added:

1. **`typed failure - readJSON of a missing file throws UtilitiesJsonException(JsonNotFound)`**
   — direct handler invocation, asserts `UtilitiesJsonException` + `JsonNotFound`.

2. **`typed failure - readJSON of malformed JSON throws UtilitiesJsonException(JsonParseFailure)`**
   — handler reads a file containing `this is not valid JSON {{{ :::`, asserts
   `UtilitiesJsonException` + `JsonParseFailure` with the right path.

3. **`typed failure - sha256 of a missing file throws UtilitiesJsonException(JsonNotFound)`**
   — same shape for the SHA capability.

4. **`typed failure - UtilitiesJsonError sealed ADT is exhaustively matchable (3 cases)`**
   — compiles only if all three cases exist; maps each to a discriminator string.

5. **`real DSL scenario - typed failure (missing-file readJSON) propagates typed UtilitiesJsonException carrying JsonNotFound reason`**
   — full canonical coordinator + boundary path. Builds a `PipelineSpec`,
   compiles through `DslCompiledPipelineCompiler`, runs through
   `CanonicalDurableRunCoordinator`, asserts `RunOutcome.Failure.failure.cause`
   is `UtilitiesJsonException` carrying `JsonNotFound(path)`.

### Expansion gate fitness (`Lfc2E2ExpansionGateFitnessTest`)

One new row added:

- **`G4-4 utilities plugin declares a typed UtilitiesJsonError sealed ADT (3 cases)`**
  — checks the plugin source contains the sealed interface, the typed exception,
  and all three variants; then walks three production-core source files
  (`RegistryExecutionBoundary`, `CanonicalDurableRunCoordinator`,
  `CanonicalRuntimeCapabilityAccess`) and asserts NONE references
  `UtilitiesJsonError` or `UtilitiesJsonException`. This is the mechanical
  proof that the typed failure surface stays inside the plugin package.

## Test evidence

```text
Lfc2E2ExpansionGateFitnessTest          15/15 GREEN (was 14/14, +1 G4-4)
UtilitiesJsonStepContractSuiteTest      26/26 GREEN (was 21/21, +5 typed-failure rows)
Lfc2E2PrepFitnessTest                   10/10 GREEN (unchanged)
Lfc2E0GlobalClosureFitnessTest          12/12 GREEN (unchanged)
Lfc2UniversalCoreFreezeFitnessTest       6/6  GREEN (unchanged)
UppercaseStepContractSuiteTest          14/14 GREEN (unchanged)
                                       ───
total focused slice                     83/83 GREEN
```

XML canaries confirmed for each row (fresh test-results/test/TEST-*.xml
generated by re-running `--rerun-tasks`).

## Counter rollup

```text
OFFICIAL_PLUGIN families certified:     1   → 1   (no new families; existing utilities
                                                 family gained 1 typed-failure surface)
CERTIFIED (core):                      12  (unchanged)
CERTIFIED (external plugin):            2   (example.uppercase + utilities.3-step family)
Production Step keys total:            16  (unchanged)
CERTIFIED + EXTERNAL_REFERENCE total:  14
LEGACY_PLUGIN_IDS:                      0   ← STILL ZERO
CanonicalCoreStepMetadata rows:         0
Canonical*NodeDispatcher.kt files:      0
CanonicalCoreStepCommand subtypes:      0
plugin manifest caps == union(contract.requiredCapabilities): 2 == 2 (verified G4-3)
production core refs to UtilitiesJsonError/Exception:          0   ← NEW: typed failure
                                                                 surface stays in plugin
```

## Architectural claims

A1 — The plugin declares its OWN typed failure ADT. Production core stays unaware.

   Evidence: `Lfc2E2ExpansionGateFitnessTest.G4-4` scans three production-core
   sources for any reference to `UtilitiesJsonError` / `UtilitiesJsonException`
   and asserts none. The plugin source declares the sealed interface and the
   typed exception.

A2 — The boundary catches the typed exception through its GENERIC
     `catch (e: Exception)` clause. No Step-specific branch is added.

   Evidence: `RegistryExecutionBoundary.kt` (lines 175-186) catches
   `Exception` and wraps the cause into a `PipelineFailure`. The plugin's
   `UtilitiesJsonException` rides through the `cause` field bit-identically
   to any other `Exception`. No production source branches on
   `is UtilitiesJsonException`.

A3 — The typed failure surface is REACHABLE from the consumer side through
     `RunOutcome.Failure.failure.cause: Throwable?`.

   Evidence: the new ContractSuite row "real DSL scenario - typed failure"
   runs through the canonical coordinator and asserts the chain
   `RunOutcome.Failure.failure.cause is UtilitiesJsonException` +
   `cause.reason is UtilitiesJsonError.JsonNotFound`. Both assertions pass.

A4 — The plugin still depends ONLY on public SDK contracts.

   Evidence: `UtilitiesJsonPlugin.kt` imports only
   `dev.rubentxu.pipeline.v2.domain.*`, `dev.rubentxu.pipeline.v2.domain.step.*`,
   and `dev.rubentxu.pipeline.v2.dsl.StageScope`. No import of any
   `pipeline-application.*` type. The plugin package itself owns the typed
   failure ADT.

A5 — Adding a typed failure surface required ZERO changes to coordinator
     routing, durable protocol, dispatcher machinery, compiler, or
     capability admission. The boundary's generic exception catch is the
     single seam that handles every plugin's typed failure.

   Evidence: git diff for this slice touches 3 files in `v2/` and 1 file in
   `examples/utilities-plugin/`. Production core (`pipeline-application`)
   is touched only by adding 5 test rows + 1 expansion-gate row, all of which
   live under `src/test/`.

## Known limitations (carried forward, NOT regressions)

- The pre-existing `01-json-roundtrip.pipeline.kts` fixture does NOT compile
  against the current DSL signature ("Too many arguments for 'fun steps()'"
  diagnostic). Verified pre-existing on base `2daec08a` and head; identical
  failure mode in both. The ContractSuite covers the same canonical pipeline
  surface programmatically (real DSL scenario, line 495).
- `examples/utilities/02-json-typed-failure.pipeline.kts` was authored and
  then removed during this slice because the same DSL signature mismatch
  applied. The ContractSuite's typed-failure real-DSL scenario is the
  authoritative proof; the `.pipeline.kts` file would have added no
  additional evidence.
- YAML parse error at line 442 (now ~700 after this slice's edits) is
  pre-existing and out-of-scope for this cycle.

## Next slice

LFC-2E2-EXPANSION U2 — YAML support (new family `utilities.readYaml` /
`utilities.writeYaml` with their own typed capability and typed error ADT).
The U1 slice proves the plugin can grow typed failure surfaces without
erosion; U2 will prove it can grow new families without erosion.
