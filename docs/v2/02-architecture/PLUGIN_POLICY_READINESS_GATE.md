# Plugin Identity / Policy Readiness Gate (LFC-2E2 precondition)

**Status:** ACTIVE precondition (LFC-2E1 / S2 cycle, 2026-09-11)
**Authority:** `docs/v2/02-architecture/PLUGIN_IDENTITY_MODEL.md` (data shape, frozen)
**Scope:** all new plugins introduced in LFC-2E2 and beyond
**Goal:** ensure every plugin born after the S2 burn-down carries the Cedar-ready
identity, release, family, and capability metadata **from day one**, so the policy
engine can be added later without runtime data reconstruction.

## Why a gate

Cedar enforcement is a roadmap item (`docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`,
line 345: "Cedar enforcement" — deferred, not discarded). The data the engine will
consume must exist in the runtime before the engine arrives, or the migration will
re-touch every Step. The gate exists to prevent that.

## Conditions (each MUST be true before LFC-2E2 starts a new plugin family)

### C1 — Plugin ResourceRef frozen

A plugin MUST declare, at registration time, a `ResourceRef(kind = PLUGIN, namespace,
identity)` that uniquely identifies the **logical** plugin (independent of version).

Example:

```kotlin
ResourceRef(
    kind = ResourceKind.PLUGIN,
    namespace = "rubentxu",
    identity = "containers",
)
```

Tests / receipts: structural fitness asserts every registered `StepRegistration` carries
a non-empty `plugin.namespace` and `plugin.identity`, and that no two registrations
under the same namespace share the same identity (unless they share the same plugin,
which is allowed because a plugin may export multiple Steps).

### C2 — PluginRelease identity frozen

Each registration MUST carry a `PluginReleaseRef` with:

- `plugin: ResourceRef` (the plugin's logical identity)
- `version: SemVer`
- `digest: Digest` (sha256 of the artifact)

The release identity is what audit events freeze; two releases with the same version but
different digests are distinct artifacts and MUST NOT be collapsed.

### C3 — Step → provider relationship

Every `StepRegistration` MUST carry a `provider: StepProviderMetadata` that names the
plugin, release, publisher, families, delivery, and trust metadata. The registry stores
registrations, not raw definitions; the boundary reads provider metadata for events.

The runtime MUST be able to answer:

```text
registry.providerOf(STEP_KEY) -> StepProviderMetadata?
```

without scanning. A `Map<PluginStepId, StepProviderMetadata>` companion to the registry
is the minimum.

### C4 — Multi-family classification allowed

A plugin MUST be allowed to declare **multiple** families. For example:

```text
pipeline-git              -> { SCM, NETWORK }
pipeline-containers       -> { CONTAINERS, ARTIFACTS }
pipeline-artifactory      -> { ARTIFACTS, NETWORK, SUPPLY_CHAIN }
```

`StepProviderMetadata.families: Set<PluginFamily>` (not `List`, not a single value).
Tests must accept >= 1 family; ordering is not significant.

### C5 — Capability declaration

The plugin manifest MUST enumerate per-Step capabilities. The runtime consumes
`StepContract.requiredCapabilities`; the manifest is the source of those values. The
loader MUST cross-check that runtime capabilities match manifest capabilities
(mismatch = fail-closed at registration time, never silently coerced).

### C6 — No Cedar runtime dependency

The data model is frozen; Cedar is **not** wired up. No new Gradle dependency on a
Cedar runtime; no `PolicyEngine` interface; no policy-admission call sites. The gate
is about the *shape* of data, not a runtime integration.

This is critical: introducing the data shape is a documentation + adapter concern; the
runtime behavior of `StructuralFamilyResolver`, `RegistryExecutionBoundary`, and
`RegistryStepInvoker` is unchanged.

### C7 — Delivery ≠ policy verdict

The classification `Delivery ∈ { CORE, OFFICIAL_PLUGIN, EXTERNAL_REFERENCE,
DEFERRED_REMOTE, REJECTED_JENKINS_INTERNAL }` is metadata. No runtime branch in the
coordinator, dispatcher, or boundary uses the delivery value to *grant* or *deny*
execution. A test asserting that `OFFICIAL_PLUGIN` and `EXTERNAL_REFERENCE` have
**identical** admission behavior (modulo duplicate-key detection) is part of the gate.

### C8 — Event projection ready

`PipelineEventEnvelope` for a Step execution MUST carry enough data to resolve:

```text
subject = ResourceRef(STEP_DEFINITION, publisher, plugin/step-id)
```

Audit-class events (e.g. `StepStarted`) MAY additionally freeze `plugin_release_digest`,
`plugin_publisher`, and `plugin_families` so the audit trail is immutable.

The boundary's existing `produced as? TypedStepOutput` mechanism is unchanged. The
provider metadata is read at the registry seam, not inside the handler.

### C9 — Fitness test for the readiness invariants

A dedicated fitness suite (`Lfc2PolicyReadinessFitnessTest` or equivalent) MUST assert
each of C1..C8 structurally — not by textual grep — before any new plugin family is
declared ready for LFC-2E2 expansion.

### C10 — Backwards compatibility for S2-burned-down Steps

Steps that have already been migrated through S2 (`core.echo`, `core.sh`, `core.error`,
...) MUST NOT be retroactively broken by the introduction of the new registration shape.
The migration to `StepRegistration(definition, provider)` MUST be additive:

- a Step without provider metadata MAY be registered (treated as legacy; provider =
  IMPLEMENTED_UNCERTIFIED);
- a Step with provider metadata is the new normal;
- tests that do not exercise provider metadata continue to pass unchanged.

## What the gate does NOT require

- A textual ARN / URI representation for `ResourceRef`. The structured shape is
  sufficient; textual form is a future concern.
- A policy DSL, schema, or evaluation engine. Cedar is deferred.
- A trust store / approved-digest registry. The data model carries `trustMetadata`
  but the runtime does not consume it yet.
- Migration of the 3 already-CERTIFIED Steps (`core.echo`, `core.sh`,
  `example.uppercase`). They remain on the legacy `StepDefinition`-only registration
  until LFC-2E2 unifies the shape.

## Order of work

```text
S2-A   (in progress)    core.error         → StepDefinition only, no provider metadata
S2-A2                  core.sleep         → StepDefinition only, no provider metadata
...
S2-G                   core.emit.event    → StepDefinition only, no provider metadata
─────── LEGACY_PLUGIN_IDS == 0 reached ───────────────────────────────────────────
LFC-2E2-prep            Adopt StepRegistration(definition, provider) shape.
                        Author PolicyReadinessFitness (C1..C8).
                        Backfill provider metadata for already-CERTIFIED core Steps.
LFC-2E2                 New plugin families (utilities, junit, http, git, docker, artifactory)
                        born with provider metadata; no retroactive changes to runtime.
LFC-2E3 (future)        Cedar runtime binding; consumes the frozen shape.
```

## Fitness tests (mechanically checkable)

The gate is verified by:

```text
Lfc2PolicyReadinessFitnessTest
  - C1: every registered plugin carries ResourceRef(PLUGIN) with non-empty namespace + identity
  - C2: every release carries version + digest; no two releases with same (plugin, version) share digest
  - C3: registry.providerOf(STEP_KEY) is O(1); returns StepProviderMetadata?
  - C4: families is Set (>= 1); ordering is not asserted
  - C5: manifest capabilities == contract.requiredCapabilities (cross-checked at registration)
  - C6: no Cedar dependency in production classpath (Gradle dependency check)
  - C7: identical admission for OFFICIAL_PLUGIN and EXTERNAL_REFERENCE (modulo duplicate-key)
  - C8: at least one event class carries provider identity in its envelope
  - C9: this fitness test itself exists and is GREEN
  - C10: S2-burned-down core Steps (echo/sh/error/sleep/...) register without provider metadata
        and continue to pass their existing contract suites
```

## Forbidden during the gate period

- Adding any `when (delivery)` / `when (family)` branch in the coordinator, dispatcher,
  or boundary. (Delivery / family are metadata for the future policy engine.)
- Treating `OFFICIAL_PLUGIN` as a privileged execution path. (Already forbidden by
  AGENTS.md; reinforced here.)
- Renaming `core.*` keys to "plugin/*" form before the catalog reaches 0 legacy.
- Adding a textual `ResourceRef.toArnString()` before the shape is frozen and at least
  one consumer exists. (Premature format freeze.)

## Decision record

This document is the gate. LFC-2E2 cycle proposals MUST cite it and demonstrate the
conditions are met before the first new plugin family enters production.
