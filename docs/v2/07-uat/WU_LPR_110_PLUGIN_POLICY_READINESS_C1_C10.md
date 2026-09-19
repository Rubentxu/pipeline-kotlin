# WU-LPR-110 — Plugin Policy Readiness Gate (C1..C10) implementation proposal

**Date:** 2026-09-19
**Author:** agent (F5 blocker discovery)
**Status:** PROPOSED — pre-F5 architectural precondition
**Authority:** `docs/v2/02-architecture/PLUGIN_POLICY_READINESS_GATE.md`
**Scope:** implement C1..C8 structural + C9 fitness test; defer C10
backfill to a follow-on cycle (additive registration shape, no
retroactive break).

---

## Why this WU exists

F5 of the post-`WU-LPR-090` / `WU-F3` / `WU-F4` plan is "first
ecosystem slice: `checkout → build → test → reports → artifacts` via
OFFICIAL_PLUGIN SCM/Git". The plan instruction explicitly demands:

> "Antes de la primera nueva familia oficial, comprueba el gate de
> preparación del plugin SDK C1..C10. Si falla una capacidad
> genérica, registra el hueco y corrige el seam mínimo."

The gate exists at `docs/v2/02-architecture/PLUGIN_POLICY_READINESS_GATE.md`
(193 lines, ACTIVE precondition, LFC-2E1 / S2 cycle, 2026-09-11).
**The C1..C10 conditions are documented but not implemented.**

Empirical verification on HEAD `522928f1` (post-F4):

| Condition | Required | Present? |
|---|---|---|
| C1 — `ResourceRef(kind = PLUGIN, namespace, identity)` | data class in `v2/pipeline-domain` | **NO** (not found) |
| C2 — `PluginReleaseRef` with `version: SemVer`, `digest: Digest` | data class | **NO** (not found) |
| C3 — `StepRegistration(definition, provider)` and `registry.providerOf(STEP_KEY)` | API | **NO** (registry only has `register(StepDefinition)`) |
| C4 — `StepProviderMetadata.families: Set<PluginFamily>` | data class | **NO** |
| C5 — Manifest capabilities cross-check vs `StepContract.requiredCapabilities` | adapter | **NO** |
| C6 — No Cedar runtime dependency | constraint | **PASS** (no Cedar dep in any `*.gradle.kts`) |
| C7 — `Delivery` enum (CORE / OFFICIAL_PLUGIN / ...) | enum | **NO** (the word appears in docs only) |
| C8 — `PipelineEventEnvelope` carries provider identity | adapter | **NO** |
| C9 — `Lfc2PolicyReadinessFitnessTest` (mechanical) | fitness test | **NO** (file not found) |
| C10 — Backwards-compatible for S2-burned-down Steps | additive shape | **PARTIAL** (existing `register(StepDefinition)` would need overload) |

**Net:** 1 / 10 conditions met (C6 trivially). 9 / 10 require new code.

This matches the gate's own "Order of work" section:

```text
LFC-2E2-prep   Adopt StepRegistration(definition, provider) shape.
               Author PolicyReadinessFitness (C1..C8).
               Backfill provider metadata for already-CERTIFIED core Steps.
LFC-2E2        New plugin families (utilities, junit, http, git, docker, artifactory)
               born with provider metadata; no retroactive changes to runtime.
```

The gate was authored as a precondition to LFC-2E2; F5 wants to be the
first LFC-2E2-style plugin. Therefore F5 is blocked on the LFC-2E2-prep
work — exactly the same shape of blocker the gate document anticipated.

---

## Proposed implementation (3 phases, additive only)

### Phase 1 — Domain contracts (no production runtime change)

In `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/`:

- `step/ResourceRef.kt` — `data class ResourceRef(kind: ResourceKind, namespace: String, identity: String)`. Frozen structural shape (no `toArnString()`, per gate C1 "forbidden").
- `step/PluginFamily.kt` — `enum class PluginFamily { SCM, NETWORK, CONTAINERS, ARTIFACTS, TESTING, UTILITIES, SUPPLY_CHAIN, REPORTING, ... }`. Open for plugins to declare more.
- `step/PluginReleaseRef.kt` — `data class PluginReleaseRef(plugin: ResourceRef, version: SemVer, digest: Digest)`. `SemVer` and `Digest` are value classes (no behaviour).
- `step/StepProviderMetadata.kt` — `data class StepProviderMetadata(plugin: PluginReleaseRef, publisher: String, families: Set<PluginFamily>, delivery: Delivery, trustMetadata: TrustMetadata?, manifestCapabilities: Set<String>)`.
- `step/Delivery.kt` — `enum class Delivery { CORE, OFFICIAL_PLUGIN, EXTERNAL_REFERENCE, DEFERRED_REMOTE, REJECTED_JENKINS_INTERNAL }`.
- `step/StepRegistration.kt` — `data class StepRegistration<I, O>(val definition: StepDefinition<I, O>, val provider: StepProviderMetadata)`. New shape; existing `StepDefinition` remains untouched.
- `step/StepRegistry.kt` — add `register(reg: StepRegistration<*, *>)` overload alongside the existing `register(definition: StepDefinition<*, *>)`. Existing call sites keep working (C10 backwards-compat).
- `step/StepRegistry.kt` — add `providerOf(STEP_KEY): StepProviderMetadata?` as an O(1) lookup backed by a `Map<PluginStepId, StepProviderMetadata>` companion to the existing `Map<PluginStepId, StepDefinition<*, *>>`.

All seven files are new, additive. No existing file is modified
beyond the two-line additions to `StepRegistry.kt`.

### Phase 2 — Fitness test (C9 mechanical gate)

New file `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2PolicyReadinessFitnessTest.kt`:

```kotlin
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Lfc2PolicyReadinessFitnessTest {
    @Test fun C1_ResourceRef_nonempty_for_every_registered_registration()
    @Test fun C2_no_two_releases_share_plugin_and_version_with_different_digest()
    @Test fun C3_registry_providerOf_is_O1_and_consistent_with_register()
    @Test fun C4_families_is_Set_with_at_least_one_element()
    @Test fun C5_manifest_capabilities_match_contract_required_capabilities()
    @Test fun C6_no_cedar_dependency_in_production_classpath()
    @Test fun C7_OFFICIAL_PLUGIN_and_EXTERNAL_REFERENCE_have_identical_admission()
    @Test fun C8_at_least_one_event_carries_provider_identity_in_envelope()
    @Test fun C9_this_fitness_suite_exists_and_is_green()
    @Test fun C10_S2_burned_core_Steps_register_without_provider_and_still_pass_contract_suite()
}
```

The test does **not** require any of the 16 existing CORE Steps to
adopt provider metadata — that's the backfill phase below. C10's test
asserts that registration WITHOUT provider metadata is allowed and
behaves as legacy (`IMPLEMENTED_UNCERTIFIED` sentinel).

### Phase 3 — Backfill provider metadata (separate cycle)

Out of scope for this WU. The 16 existing CORE Steps gain
`StepRegistration` with provider metadata in a follow-on cycle
(probably `WU-LPR-111_CORE_PROVIDER_METADATA_BACKFILL`). No CORE Step
fails any test until then.

---

## What this WU does NOT do

- Does NOT add a Cedar runtime binding. (Deferred per gate C6.)
- Does NOT add a textual ARN representation. (Deferred per gate C1.)
- Does NOT change the admission gate or the canonical bridge. (C7
  forbids `when (delivery)` branches in coordinator/dispatcher/boundary.)
- Does NOT introduce a new plugin family. (That is LFC-2E2; we are
  doing LFC-2E2-PREP.)

---

## Why F5 is blocked until this WU lands

F5's brief is "first slice of the real `checkout → build → test →
reports → artifacts` pipeline, with OFFICIAL_PLUGIN SCM/Git". For
that to be a clean additive plugin (no coordinator edit, the test
criterion of the plan), the new SCM/Git plugin must:

- Declare its `ResourceRef(PLUGIN, "rubentxu", "scm-git")` (C1).
- Declare its `PluginReleaseRef(version, digest)` (C2).
- Declare its families `{SCM, NETWORK}` (C4).
- Declare its `Delivery.OFFICIAL_PLUGIN` (C7).
- Cross-check its `StepContract.requiredCapabilities` against the
  manifest (C5).
- Survive `Lfc2PolicyReadinessFitnessTest` (C9).

Without C1..C9, any new OFFICIAL_PLUGIN is silently privileged (no
delivery metadata) or silently ungoverned (no manifest cross-check).
The gate explicitly forbids both.

---

## Recommendation

**Implement this WU before any F5 slice.** Estimated effort:

- Phase 1 (domain contracts): small (~6 new files, ~2 small additions
  to `StepRegistry.kt`). Pure data classes.
- Phase 2 (fitness test): medium (~10 test methods, each a few
  lines; mechanical once Phase 1 lands).
- Phase 3 (CORE backfill): out of scope, separate cycle.

Phase 1 + Phase 2 together: **the minimum seam correction the plan
demanded** ("corrige el seam mínimo").

A formal ADR (e.g. `ADR-0092-plugin-policy-readiness-shape.md`) should
accompany Phase 1, ratifying the structural shapes for Cedar deferred.

---

## Open questions for the human approver

1. **ADR number**: do we use `ADR-0092` (next in `docs/v2/04-adrs/`) or
   reserve a specific number?
2. **Phase 3 timing**: do CORE Steps need provider metadata
   backfilled before or after the first OFFICIAL_PLUGIN ships?
3. **`TrustMetadata` shape**: the gate mentions it but doesn't specify
   the data shape. Should it be a free-form `Map<String, String>`
   (flexible) or a sealed ADT (`Signed { ... }, Unsigned { ... }`)
   (stricter)?

These are the only items requiring human input before Phase 1 starts.
