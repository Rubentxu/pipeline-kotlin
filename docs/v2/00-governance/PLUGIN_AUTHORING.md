# Plugin Authoring Guide — LFC-2E2-PREP

**Cycle:** LFC-2E2-PREP (preparation for the first official plugin)
**Date:** 2026-09-17
**Status:** ACTIVE — frozen plugin authoring surface

---

## Purpose

This document is the **operative authoring guide** for plugins under the
LFC-2E2 universal-core freeze. It complements the ADRs (ADR-0070..0074) and
the admission record (`CORE_STEP_ADMISSION.md`).

The frozen authoring surface is intentionally small:

| Concern | Frozen answer |
|---|---|
| How does a plugin expose Step families? | `StepDefinitionContributor` SPI (ServiceLoader) |
| How does a plugin declare its identity? | `PluginManifest(coordinate, version, families)` |
| How does a plugin declare its capabilities? | `PluginFamily.requiredCapabilities: Set<StepCapability>` |
| How does a plugin get admitted to the runtime? | `resolvePluginAdmissionPolicy(manifest, supplied)` → `Ready` / `MissingCapabilities` / `Malformed` |
| How does the runtime reject missing capabilities? | Fail-closed BEFORE the handler runs (LB-02 / G3-A4.2) |
| How does a plugin name its coordinate? | `<groupId>.<artifactId>` (dot-separated, lowercase) |
| How does a plugin name its versions? | Semver (`major.minor.patch[-preRelease][+buildMetadata]`) |
| How does a plugin identify its release? | `PluginReleaseRef(coordinate, version)` → `ResourceKind.PLUGIN_RELEASE` |
| How does a plugin identify its families? | `ResourceRefs.stepDefinition(pluginStepId)` → `ResourceKind.STEP_DEFINITION` |

## What is forbidden for a plugin

A plugin **MUST NOT**:

- Add a `CanonicalXxxNodeDispatcher.kt` file.
- Reference `LEGACY_PLUGIN_IDS` or extend the legacy decoder.
- Branch on `pluginStepId.value` in the coordinator / dispatcher / compiler.
- Depend on any internal runtime, application, or dispatcher package.
- Reach `CanonicalRuntimeContext` (or any service-locator context) from a handler.
- Acquire capabilities beyond what `StepContract.requiredCapabilities` declares.
- Infer replay from output presence (use `ReplayPolicy` instead).
- Persist typed `Input` or `PreparedExecution` as `Any`/Map.
- Claim `CERTIFIED` while remaining legacy-executable.

Violating any of these is a defect caught by `Lfc2UniversalCoreFreezeFitnessTest`.

## Plugin authoring golden path

```text
1.  Pick a coordinate: `pipeline.<groupId>.<artifactId>` (e.g. `pipeline.utilities.json`).
2.  Pick a semver version: `major.minor.patch[-preRelease][+buildMetadata]`.
3.  Define typed Input and Output value types (sealed/data class; never Any?).
4.  Define StepCodec<Input> and StepCodec<Output>.
5.  For each family:
      - choose a unique PluginStepId (e.g. `utilities.readJSON`)
      - declare requiredCapabilities (Set<StepCapability>) — must be supplied by the runtime
      - choose the executionShape (ATOMIC / BODY_BEARING / SCOPED / RETRYING / PARALLEL)
6.  Construct a PluginManifest(coordinate, version, families).
7.  Validate via resolvePluginAdmissionPolicy(manifest, suppliedCapabilities).
      - If Ready → proceed.
      - If MissingCapabilities → declare which capabilities the host runtime must supply,
        OR fail the plugin install (do not silently downgrade capabilities).
      - If Malformed → fix the manifest (duplicate StepKeys, empty families, etc.).
8.  Define StepDescriptor (per family).
9.  Define StepContract (per family): key, descriptor, inputCodec, outputCodec, requiredCapabilities.
10. Implement the typed StepHandler using ONLY the declared capabilities.
11. Define StepDefinition.
12. Expose the families through StepDefinitionContributor.
13. Register the contributor via ServiceLoader (META-INF/services/...).
14. (Optional) Provide a plugin-owned typed Kotlin DSL extension.
15. Run the StepContractSuite, run Lfc2E2PrepFitnessTest, run
    Lfc2UniversalCoreFreezeFitnessTest, run Lfc2E0GlobalClosureFitnessTest
    to prove zero production change.
16. Reach CERTIFIED only when ALL of the above are GREEN and a real
    `.pipeline.kts` fixture exercises the plugin end-to-end.
```

## Minimal worked example

The `examples/example-uppercase-plugin` (CERTIFIED) is the reference implementation.
Read it before authoring a new plugin.

A new plugin (e.g. `pipeline.utilities.json`) follows the same shape:

```kotlin
package pipeline.utilities.json

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.plugin.PluginFamily
import dev.rubentxu.pipeline.v2.domain.plugin.PluginManifest
import dev.rubentxu.pipeline.v2.domain.plugin.PluginCoordinate
import dev.rubentxu.pipeline.v2.domain.plugin.PluginVersion
import dev.rubentxu.pipeline.v2.domain.plugin.resolvePluginAdmissionPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor

// Step families contributed by this release:
val JSON_CAP = StepCapability("utilities.json.operations")

val JSON_MANIFEST = PluginManifest(
    coordinate = PluginCoordinate("pipeline.utilities.json"),
    version = PluginVersion(1, 0, 0),
    contributors = listOf("Pipeline Kotlin Team"),
    families = listOf(
        PluginFamily(
            stepKey = PluginStepId("utilities.readJSON"),
            requiredCapabilities = setOf(JSON_CAP),
        ),
        PluginFamily(
            stepKey = PluginStepId("utilities.writeJSON"),
            requiredCapabilities = setOf(JSON_CAP),
        ),
    ),
)

// Static identity for diagnostics:
val READ_JSON_RELEASE_REF = JSON_MANIFEST.releaseRef()
// READ_JSON_RELEASE_REF.toResourceRef() == ResourceKind.PLUGIN_RELEASE
// ResourceRefs.stepDefinition("utilities.readJSON") == ResourceKind.STEP_DEFINITION
```

## Admission outcomes (typed)

The host runtime calls `resolvePluginAdmissionPolicy(manifest, suppliedCapabilities)`:

| Result | Meaning | Action |
|---|---|---|
| `PluginAdmissionPolicy.Ready` | Every declared capability is supplied | Admit the plugin; proceed with `StepRegistry.register(...)` |
| `PluginAdmissionPolicy.MissingCapabilities(missing, declared)` | Runtime cannot supply some capabilities | Reject the plugin install (do not silently downgrade) |
| `PluginAdmissionPolicy.Malformed(reason)` | Manifest is structurally invalid | Reject the plugin install; fix the manifest |

Each case carries its own typed payload; the caller cannot accidentally
collapse them into a `Boolean?`.

## What the fitness tests enforce

- **`Lfc2E2PrepFitnessTest`** (10 tests, this slice): every E2-PREP invariant
  C1..C10 has a corresponding assertion. Adding a new plugin type without an
  admission manifest + fitness coverage is fail-closed at the test.
- **`Lfc2UniversalCoreFreezeFitnessTest`** (6 tests, prior slice): no
  plugin-induced change to the universal core surface is permitted.
- **`Lfc2E0GlobalClosureFitnessTest`** (12 tests, prior slice): every
  plugin Step must resolve through the registry seam (zero legacy residual).

## See also

- `docs/v2/00-governance/CORE_STEP_ADMISSION.md` — frozen core admission record.
- `docs/v2/07-uat/LFC2E2_PREP_RECEIPT.md` — LFC-2E2-PREP closure receipt.
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` — human-readable certification matrix.
- `docs/v2/status/step-certification.yaml` — machine-readable canonical source.
- ADR-0070, ADR-0071, ADR-0072, ADR-0073, ADR-0074 — Step Constitution ADRs.
- `examples/example-uppercase-plugin` — CERTIFIED reference implementation.
