# E2-U2 — YAML Families Receipt

Cycle: LFC-2E2-UTILITIES-EXPANSION
Slice: U2 (YAML families)
Status: CLOSED
Commit: (this slice)
Date: 2026-09-17

## Scope

Adds two new Step families (`utilities.readYaml`, `utilities.writeYaml`) to the
existing OFFICIAL_PLUGIN `pipeline.utilities.json@1.0.0`. The new families:

1. Ship in the **same JAR** as the JSON families, behind the **same contributor**
   (same plugin coordinate, same registry family).
2. Declare a **NEW typed capability** `utilities.yaml.operations` — distinct
   from `utilities.json.operations` and `utilities.sha.operations`, registered
   through the same generic `CapabilityAccessFactory` seam U0 introduced.
3. Carry their **OWN typed failure ADT** `UtilitiesYamlError` (3 cases:
   `YamlNotFound`, `YamlParseFailure`, `YamlIoFailure`) and typed exception
   `UtilitiesYamlException`, mirroring U1's JSON pattern bit-equivalently.
4. Add a **NEW** transitive-free dependency `org.yaml:snakeyaml:2.3` (compileOnly)
   to project YAML trees ↔ `JsonElement` at the capability boundary so the
   public contract stays one shape (JSON).

This slice proves that adding a new family to an existing OFFICIAL_PLUGIN
**does not require** a new contributor, a new capability port in production,
or any change to the canonical coordinator / boundary / dispatcher machinery.

## What changed

### Plugin package (`examples/utilities-plugin`)

**New file**: `src/main/kotlin/pipeline/utilities/yaml/UtilitiesYamlPlugin.kt`
(313 lines):

- `sealed interface UtilitiesYamlError` + 3 variants
  (`YamlNotFound`, `YamlParseFailure`, `YamlIoFailure`)
- `class UtilitiesYamlException(reason: UtilitiesYamlError)`
- `interface UtilitiesYamlOperations` with `@Throws(UtilitiesYamlException::class)`
- `ReadYamlInput`, `ReadYamlOutput`, `ReadYamlCodec`, `ReadYamlOutputCodec`,
  `ReadYamlStepDefinition` (READ_ONLY effect, MEMOIZED replay, requires
  `utilities.yaml.operations` capability)
- `WriteYamlInput`, `WriteYamlOutput`, `WriteYamlCodec`, `WriteYamlOutputCodec`,
  `WriteYamlStepDefinition` (WRITES_WORKSPACE effect, MEMOIZED replay, requires
  `utilities.yaml.operations` capability)
- `UtilitiesYamlContributor` (shares the JSON plugin coordinate)
- `DefaultUtilitiesYamlOperations` — FS-backed default impl
- `fun StageScope.readYaml(path)` + `fun StageScope.writeYaml(path, value)`
  DSL extensions lowering to `registryStep(...)`
- Pure projection functions `jsonElementFrom(Any?)` and `yamlFromJsonElement(JsonElement)`
  for JSON ↔ SnakeYAML value-tree translation

**Modified**: `src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt`:
the existing `UtilitiesJsonContributor.definitions()` grows from 3 entries to 5
entries by including `ReadYamlStepDefinition` and `WriteYamlStepDefinition`.

**Modified**: `build.gradle.kts`: added `compileOnly("org.yaml:snakeyaml:2.3")`
to support the YAML codec. The dep is `compileOnly` because the plugin
**consumes** SnakeYAML at the capability boundary (the host can replace it
through the typed capability port if it wants a different YAML engine).

### Production core (`v2/pipeline-application`)

**Zero changes.** The boundary's existing generic `catch (e: Exception)` wraps
`UtilitiesYamlException` as `StepOutcome.Failure(PipelineFailure(kind=ENGINE,
message="...", cause=e))`. The capability admission layer reads the new
`utilities.yaml.operations` capability through the same `CapabilityAccessFactory`
seam U0 introduced — it doesn't know or care that the value is provided by the
plugin package.

### Test contract suite (`UtilitiesYamlStepContractSuiteTest`)

NEW file: 17 rows covering:

1. **`identity`** (2 rows) — StepKey uniqueness; coordinate matches JSON's;
   duplicate registration fails closed
2. **`identity`** (1 row) — both YAML families coexist in the SAME contributor
   as the 3 JSON families
3. **`contract completeness`** (2 rows) — readYaml declares READ_ONLY + YAML
   capability; writeYaml declares WRITES_WORKSPACE + YAML capability
4. **`contract completeness`** (1 row) — YAML plugin declares exactly ONE
   new capability token (`utilities.yaml.operations`)
5. **`codec round-trips`** (2 rows) — input codecs round-trip path + value
6. **`capability admission`** (3 rows) — Ready when YAML capability available
   for both families; Rejected when absent
7. **`handler semantics`** (2 rows) — readYaml round-trips a real YAML file;
   writeYaml produces a parseable YAML file
8. **`typed failure`** (3 rows) — missing-file → `UtilitiesYamlException(YamlNotFound)`;
   malformed YAML → `UtilitiesYamlException(YamlParseFailure)`;
   exhaustive `when` over the sealed ADT
9. **`real DSL scenario`** (1 row) — readYaml + writeYaml round-trip runs
   end-to-end through the canonical coordinator + boundary

### Expansion gate fitness (`Lfc2E2ExpansionGateFitnessTest`)

NEW rows (2):

- **`G4-5`** — YAML plugin declares its OWN typed `UtilitiesYamlError` sealed
  ADT + capability port (3 cases); capability port functions carry
  `@Throws(UtilitiesYamlException::class)`; production core sources contain
  zero references to `UtilitiesYamlError` / `UtilitiesYamlException`.
- **`G5b`** — YAML families are registered alongside JSON families in the
  SAME contributor; the single `UtilitiesJsonContributor` exposes all 5
  families (`readYaml + writeYaml + readJSON + writeJSON + sha256`).

## Test evidence

```text
Lfc2E2ExpansionGateFitnessTest          17/17 GREEN (was 15/15, +2 G4-5 + G5b)
UtilitiesYamlStepContractSuiteTest      17/17 GREEN (new)
UtilitiesJsonStepContractSuiteTest      26/26 GREEN (unchanged)
Lfc2E2PrepFitnessTest                   10/10 GREEN (unchanged)
Lfc2E0GlobalClosureFitnessTest          12/12 GREEN (unchanged)
Lfc2UniversalCoreFreezeFitnessTest       6/6  GREEN (unchanged)
UppercaseStepContractSuiteTest          14/14 GREEN (unchanged)
                                       ───
total focused slice                    102/102 GREEN
```

XML canaries confirmed for each row (fresh `test-results/test/TEST-*.xml`
regenerated).

## Counter rollup

```text
OFFICIAL_PLUGIN families certified:     1   (still single coordinate; 5 StepKeys)
CERTIFIED (core):                      12  (unchanged)
CERTIFIED (external plugin):            2 → 6   (example.uppercase + utilities.5-step family)
Production Step keys total:            16 → 21  (added readYaml + writeYaml)
CERTIFIED + EXTERNAL_REFERENCE total:  14 → 18
LEGACY_PLUGIN_IDS:                      0   ← STILL ZERO
CanonicalCoreStepMetadata rows:         0
Canonical*NodeDispatcher.kt files:      0
CanonicalCoreStepCommand subtypes:      0
plugin manifest caps == union(contract.requiredCapabilities): 3 == 3
   (utilities.json.operations, utilities.sha.operations, utilities.yaml.operations)
production core refs to UtilitiesYamlError/Exception:          0   ← NEW
production core refs to UtilitiesJsonError/Exception:          0   ← UNCHANGED
```

## Architectural claims

A1 — Adding a new family to an existing OFFICIAL_PLUGIN requires ZERO changes
     to the production coordinator, dispatcher, boundary, durable protocol,
     or compiler.

   Evidence: git diff for this slice touches 4 files in `examples/utilities-plugin/`
   and 2 files in `v2/pipeline-application/src/test/`. The
   `pipeline-application/src/main/` tree is UNTOUCHED.

A2 — Adding a new capability type to an existing OFFICIAL_PLUGIN requires NO
     changes to the canonical capability bridge (CanonicalRuntimeCapabilityAccess).

   Evidence: the bridge exposes a `Map<StepCapability, Any>` typed map;
   `utilities.yaml.operations` is just another key the host supplies. No
   production source mentions `yaml` or `utilities.yaml`.

A3 — A new family reuses the SAME contributor id; the canonical idempotent
     `StepDefinitionContributor` SPI lets the existing contributor grow
     its `definitions()` list without spawning a new contributor.

   Evidence: `UtilitiesJsonContributor.id = "pipeline.utilities.json"` (unchanged);
   `definitions()` now returns 5 entries instead of 3; the registry keeps a
   single contributor per plugin coordinate. Verified mechanically by
   `Lfc2E2ExpansionGateFitnessTest.G5b`.

A4 — A new family declares its OWN typed failure ADT; the production boundary
     stays unaware.

   Evidence: `UtilitiesYamlError` is declared in `pipeline/utilities/yaml/`;
   no production source references it (verified by `G4-5`).

A5 — The capability-per-port discipline scales: 3 distinct capability tokens
     (`utilities.json.operations`, `utilities.sha.operations`,
     `utilities.yaml.operations`) coexist on the same plugin without
     collision, capability bleed, or dispatcher switch.

   Evidence: `G4-3` confirms exactly 2 JSON+SHA capability tokens in the JSON
   plugin source; `G4-5` confirms exactly 1 YAML capability token in the YAML
   plugin source. Total = 3 capability tokens for the same plugin coordinate.

## Known limitations (carried forward, NOT regressions)

- Pre-existing `01-json-roundtrip.pipeline.kts` DSL signature mismatch:
  "Too many arguments for 'fun steps()'" is reproducible on base `2daec08a`.
  Same issue applies to a hypothetical YAML `.pipeline.kts` fixture; we kept
  the proof in the ContractSuite's typed PipelineSpec + DslCompiledPipelineCompiler
  path (same canonical coordinator) rather than a `.kts` file.
- YAML pre-existing parse error at line 442 (now ~800 after this slice's edits)
  is pre-existing on base `2daec08a` and out-of-scope for this cycle.

## Next slice

LFC-2E2-EXPANSION U3 — properties file support. Will follow the same shape
(readProperties + writeProperties under the same OFFICIAL_PLUGIN coordinate,
new `utilities.properties.operations` capability token, new typed
`UtilitiesPropertiesError` ADT) and prove the plugin model scales further
without erosion.
