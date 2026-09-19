---
type: adr
id: ADR-0092
title: "Plugin Identity / Provider Registration Additive — C1..C10 implementation freeze"
status: proposed
date: 2026-09-19
deciders: "Rubentxu (product owner); LFC-2E2-prep cycle"
supersedes: null
superseded_by: null
related:
  - ADR-0070
  - ADR-0074
  - docs/v2/02-architecture/PLUGIN_IDENTITY_MODEL.md
  - docs/v2/02-architecture/PLUGIN_POLICY_READINESS_GATE.md
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
  - WU-LPR-110
---

# ADR-0092 — Plugin Identity / Provider Registration Additive (C1..C10 freeze)

## Context

S2 burns down `LEGACY_PLUGIN_IDS` (12 → 0) by migrating every Step to the
registry seam (`StepDefinition<I, O>` + codecs + descriptor + capabilities).
What remains unanswered for the policy/audit story:

- **who** provides the Step (publisher / plugin)
- **what immutable artifact** runs in this execution (release / digest)
- **what functional family** the Step belongs to
- **what trust metadata** the runtime has for the provider

The **gate** (`PLUGIN_POLICY_READINESS_GATE.md`, C1..C10, ACTIVE precondition
since 2026-09-11) freezes the **data shape** so a future Cedar-style policy
engine can reason over it **without reconstructing runtime data later**.

The gate's own "Order of work" prescribes that **LFC-2E2-prep** (adopt
`StepRegistration(definition, provider)` shape, author
`Lfc2PolicyReadinessFitness`) lands **before** LFC-2E2 (first plugin
families). F5 (post-`WU-F3`/`WU-F4`) is the first LFC-2E2-style slice
(OFFICIAL_PLUGIN SCM/Git). It cannot start until the gate is implemented.

`PLUGIN_IDENTITY_MODEL.md` and `STEP_PLUGIN_SDK.md` already freeze the
**conceptual** shape (`ResourceRef(kind, namespace, identity)`,
`PluginReleaseRef`, `PluginFamily`, `Delivery`, `TrustMetadata`,
`StepRegistration(definition, provider)`, event projection).

This ADR ratifies that conceptual shape into a **concrete additive
data shape** in code, preserving C10 backwards-compat for the 16 CORE
Steps and the existing external `example.uppercase` plugin.

## Discovery that shaped the implementation

Before coding, the developer verified the **current** runtime shape
versus the conceptual model:

1. `ResourceRef` already exists at
   `v2/pipeline-domain/.../identity/ResourceRef.kt` with shape
   `data class ResourceRef(val kind: ResourceKind, val segments: List<String>)`.
   Namespace is `segments.first()`; no `namespace + identity` field pair.
   Construction is typed via `ResourceRefs.*` builders; ad-hoc
   `ResourceRef(...)` from outside the identity package is rejected by
   architecture fitness.

2. `ResourceKind` already enumerates `PIPELINE_DEFINITION, RUN, STAGE,
   STEP, OPERATION`. The conceptual document proposes adding
   `PLUGIN, PLUGIN_RELEASE, STEP_DEFINITION, PLUGIN_FAMILY`. Adding
   these to the enum is fine, but **using** them as `subject` in
   `PipelineEventEnvelope` requires either bumping the envelope major
   version or expanding the wire serializer.

3. `PipelineEventEnvelope` carries `subject: ResourceRef` and a
   hand-written serializer (`PipelineEventEnvelopeSerializer`).
   `version = 1`. The envelope is the projection of the store-assigned
   event; **adding new `ResourceKind` values to the existing subject
   field is wire-compatible** (the serializer emits kind as
   `ResourceKind.name`, the decoder resolves via `ResourceKind.valueOf`).

4. `StepStarted` already carries `stepName` and `stepType` but no
   provider identity. This is where C8 has to land.

5. `EnvelopeProjector` is the **single authority** for projecting
   `DomainEvent → PipelineEventEnvelope`. Adding provider identity to
   the envelope must happen there or be carried as additional
   structured fields, never by mutating producers.

6. `StepRegistry` (interface + `InMemoryStepRegistry`) currently has a
   single `register(StepDefinition)` method with deterministic
   duplicate-key rejection. Adding `register(StepRegistration)` is
   additive; sharing the same underlying map keeps the rejection
   rule uniform.

## Decisions

### D1. ResourceKind expansion is in scope

`ResourceKind` grows with `PLUGIN, PLUGIN_RELEASE, STEP_DEFINITION,
PLUGIN_FAMILY` (matches `PLUGIN_IDENTITY_MODEL.md`). The expansion is
backwards-compatible because the wire serializer emits `kind` as a
name string; V1 consumers decoding only known kinds fail closed on
unknown kinds (`InvalidResourceRefException`) — which is the desired
behaviour (new event families should not be silently mis-classified
by old consumers).

### D2. Identity construction stays via `ResourceRefs` builders

`ResourceRef(kind, segments)` is **not** part of the public
construction API outside the identity package. Plugin code that wants
to build a `ResourceRef(PLUGIN, ...)` calls `ResourceRefs.plugin(...)`
or a new builder — never the constructor. Architecture fitness
already enforces this; the new builders are added to the same
`ResourceRefs` object so the rule stays uniform.

### D3. Provider metadata split into two dimensions (rule from human approver)

`StepProviderMetadata` carries **`plugin: ResourceRef` (PLUGIN) and
`release: PluginReleaseRef` as separate fields**, with an invariant
that `release.plugin == plugin` (validated at construction; the helper
`StepProviderMetadata.create(...)` enforces it; the primary
constructor is `internal`). The conceptual document's two-dimension
shape is preserved; no collapse.

### D4. TrustMetadata is a typed ADT (rule from human approver)

```kotlin
sealed interface TrustMetadata {
    /** Plugin manifest exists; pipelinek has no verified trust data for it. */
    data object Unverified : TrustMetadata
}
```

No `Verified`, `Signed`, or `Approved` constructors are introduced.
These would require real verification logic (Cedar policy engine,
signature verifier, digest allow-list) which is explicitly out of
scope for this cycle. Adding them later is an ADR-level decision that
brings its own evidence path; `Unverified` is the **only** value
supported today, and any code attempting to construct a `Verified`
subclass would have to do so by adding a new constructor — visible
in code review.

### D5. StepRegistry extension is additive (C10)

```kotlin
interface StepRegistry {
    fun register(definition: StepDefinition<*, *>)
    fun register(registration: StepRegistration<*, *>)   // NEW
    fun definition(key: PluginStepId): StepDefinition<*, *>?
    fun providerOf(key: PluginStepId): StepProviderMetadata?  // NEW
    fun contains(key: PluginStepId): Boolean
    fun keys(): Set<PluginStepId>
}
```

Both `register` overloads share the same underlying `Map<PluginStepId,
Entry>` where `Entry` is `data class Entry(val definition:
StepDefinition<*, *>, val provider: StepProviderMetadata?)`. Calling
`register(definition)` after `register(registration)` with the same key
(or vice-versa) throws `IllegalArgumentException` with a diagnostic
naming both the key and the prior contributor. **No** second execution
path: the invoker (`RegistryStepInvoker`) reads `definition` from the
entry; provider metadata is only read by the boundary / projector.
`providerOf` is O(1) backed by the same map.

### D6. C5 manifest cross-check is a separate validator

```kotlin
class PluginManifest(
    val plugin: ResourceRef,                 // kind = PLUGIN
    val release: PluginReleaseRef,
    val publisher: String,
    val families: Set<PluginFamily>,
    val delivery: Delivery,
    val trust: TrustMetadata,
    val stepManifests: List<StepManifest>,   // one per Step the plugin provides
)

class StepManifest(
    val stepKey: PluginStepId,
    val declaredCapabilities: Set<StepCapability>,
)

object PluginManifestValidator {
    /** Throws if any step's declared capabilities != contract.requiredCapabilities. */
    fun validate(pluginManifest: PluginManifest, definitions: List<StepDefinition<*, *>>)
}
```

The validator is invoked by **plugin authors** at construction time
of a `StepRegistration` (helper `StepRegistration.fromManifest(...)`),
NOT by `register(...)` directly. This keeps `register(...)` semantically
neutral: the registry's only invariant is duplicate-key rejection.
A plugin that wants C5 enforcement uses `fromManifest`; one that
opts out of the manifest cross-check uses the primary constructor
with explicit `StepProviderMetadata` and accepts the documented
"manifestless" status.

A new plugin calling `register(StepRegistration)` without a manifest
**still works**, but loses C5 enforcement. The plugin author is
responsible for using `fromManifest` if they want C5. (Future ADR may
make the manifest mandatory for `Delivery.OFFICIAL_PLUGIN` and
`Delivery.EXTERNAL_REFERENCE`; this ADR does not.)

### D7. C8 event projection adds ProviderProvenance as additive optional field

```kotlin
data class PipelineEventEnvelope(
    val version: Int,
    val eventRef: EventRef,
    val kind: String,
    val occurredAt: Instant,
    val sequence: Long,
    val subject: ResourceRef,
    val causation: EventRef? = null,
    val correlation: EventRef? = null,
    val provenance: ProviderProvenance? = null,   // NEW (additive)
) { ... }
```

`ProviderProvenance` is the **audit-class projection** of
`StepProviderMetadata` carried in the envelope, NOT a structural
field of `ResourceRef`:

```kotlin
data class ProviderProvenance(
    val pluginPublisher: String,
    val pluginNamespace: String,            // ResourceRef(PLUGIN).segments.first()
    val pluginIdentity: String,             // ResourceRef(PLUGIN).segments.drop(1).joinToString("/")
    val releaseVersion: String,             // PluginReleaseRef.version.toString()
    val releaseDigest: String,              // PluginReleaseRef.digest.value
    val families: Set<String>,              // sorted for determinism
)
```

The envelope `Wire` data class and serializer are updated to carry
`provenance` as an optional field. V1 wire form without `provenance`
decodes to `provenance = null` (default value of nullable field).
**No bump to `version`** because the change is strictly additive and
the decoder accepts the missing field.

`EnvelopeProjector` reads provider metadata from a new seam:

```kotlin
class EnvelopeProjector(
    private val providerLookup: (stepKey: PluginStepId) -> StepProviderMetadata?,
)
```

The default seam implementation looks up from `StepRegistry.providerOf`.
Producers of `DomainEvent` are unchanged; they keep emitting plain
events and the projector adds provenance. **No** SCM/Git-specific
logic; the projector reads provider metadata for **every** step
key uniformly.

### D8. C6 still forbids Cedar

No Gradle dependency is added to any `*.gradle.kts` for a Cedar
runtime. No `PolicyEngine` interface exists in production code.
The `ProviderProvenance` data shape exists for **audit** purposes
(answering "what code ran?") and the future policy engine will
consume it; the engine is not introduced.

### D9. Phase 3 (CORE backfill) is a separate cycle

The 16 CORE Steps continue to register via
`register(StepDefinition)` (the legacy overload) with no provider
metadata. C10 verifies that this still works. A separate WU
(`WU-LPR-111` or analogous) will adopt `register(StepRegistration)`
with provider metadata for the CORE catalogue **after** the first
OFFICIAL_PLUGIN has certified the new shape end-to-end.

### D10. C9 fitness suite tests behaviour, not names

`Lfc2PolicyReadinessFitnessTest` is the **mechanical gate**. Each
condition (C1..C8, C10) has at least one **positive** test (the
behaviour is real) and at least one **negative** test (a
mismatch/lapse/shortcut is rejected). The suite does not contain a
test that asserts only "the type named `StepProviderMetadata` exists"
or "the method `providerOf` exists"; that kind of test is incidental
to the behavioural tests.

C9 itself is verified by the existence of the suite's behavioural
tests: a passing run of those tests IS the proof of C9.

## Scope firewall

This ADR is the freeze of C1..C10 as concrete additive data shapes
and behaviour. It does **not**:

- introduce Cedar, signature verification, or any policy engine;
- rewrite `CanonicalDurableRunCoordinator`;
- rename any `core.*` StepKey;
- add new `StepSpec` subtypes;
- promote CORE Steps to OFFICIAL_PLUGIN;
- introduce a plugin marketplace, hot-reload, dependency resolution,
  signing, remote repository, plugin lifecycle manager, default-import
  discovery, or advanced KSP automation.

Any of those is its own ADR.

## Consequences

- The first OFFICIAL_PLUGIN (F5 SCM/Git) can now be added with a
  typed `StepProviderMetadata`, a `PluginManifest`, the C5 validator,
  and `ProviderProvenance` projected into its `StepStarted` envelopes.
- The `example.uppercase` external plugin continues to register via
  the legacy `register(StepDefinition)` overload (C10) and its
  `UppercaseStepDefinition` certifies without code changes.
- The 16 CORE Steps continue to register via the legacy overload and
  their contract suites remain green. Future backfill is a separate
  cycle.
- `PipelineEventEnvelope.version` stays at 1. The wire form V1
  continues to be readable; the new optional `provenance` field is
  decoded to `null` when absent. Encoders that do not know about
  `provenance` are unaffected (decoder accepts missing field;
  encoder-side `encodeDefaults = true` already emits null when
  unknown).
- `ResourceKind` grows. Old code that does `valueOf("PIPELINE_DEFINITION")`
  still works; old envelopes with only the pre-expansion kinds decode
  unchanged.

## Out of scope for this cycle

- WU-LPR-111 (CORE provider-metadata backfill).
- Cedar runtime binding.
- `Delivery.OFFICIAL_PLUGIN` being mandatory (the manifest is still
  opt-in via `fromManifest`).
- Signature verification / digest allow-list.
- `Verified` / `Signed` / `Approved` constructors of `TrustMetadata`.
