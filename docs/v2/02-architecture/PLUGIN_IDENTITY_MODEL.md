# Plugin Identity Model — Cedar-ready data shape (no Cedar in runtime)

**Status:** DESIGN (LFC-2E1 / S2 cycle, 2026-09-11)
**Authority:** input to LFC-2E2 expansion gate
**Goal:** freeze the *shape* of identity, release, family, and provider metadata now, so
that a future Cedar-style policy engine can reason over it **without reconstructing
runtime data later**. Cedar itself is NOT introduced into the runtime by this document.

This document is intentionally silent about syntax for ARNs / resource URIs. It freezes
the *structured* data model that the policy engine will consume. A textual representation
will be derived from this shape if and when the policy engine is wired up.

## Why now

S2 burns down `LEGACY_PLUGIN_IDS` (12 → 0). Every Step migrating to the registry seam
will gain:

- typed `StepDefinition<I, O>`
- typed input/output codecs
- declared `requiredCapabilities`
- descriptor (effects / replayPolicy)

These four fields answer **what** the Step does. They do NOT answer:

- **who** provides the Step (publisher / plugin)
- **what immutable artifact** runs in this execution (plugin release / digest)
- **what functional family** the Step belongs to (e.g. `containers`, `scm`, `testing`)
- **what trust metadata** the runtime has for the provider (signed, approved, etc.)

Without those dimensions, a future policy engine can only reason over Step-key-level rules,
which collapses back into "deny if step == docker.push" — a name-based architecture we
explicitly rejected for capability admission (ADR-0070/0074).

## Conceptual layering (frozen)

```text
Pipeline / Run / Stage / Step
              │
              └── StepDefinition
                     │
                     ├── key
                     ├── contract (descriptor + codecs + requiredCapabilities)
                     ├── handler
                     │
                     └── provider            ← StepProviderMetadata (this document)
                            │
                            ├── pluginRef          (logical identity)
                            ├── pluginReleaseRef   (immutable artifact identity)
                            ├── publisher
                            ├── families[]         (multi-family classification)
                            ├── delivery           (CORE / OFFICIAL_PLUGIN / EXTERNAL_REFERENCE / ...)
                            └── trustMetadata      (signed / approved / digest)
```

The provider metadata is **outside** `StepContract` on purpose: the contract is the
semantic agreement (input shape / output shape / capabilities needed); the provider is
the provenance/distribution dimension. Conflating them would couple policy reasoning to
codec layout and lock the data model.

## Resource kinds (frozen set, extension allowed)

A future `ResourceKind` enumeration covers at minimum:

```text
PLUGIN                 — logical identity of a provider (publisher-scoped)
PLUGIN_RELEASE         — immutable artifact of one PLUGIN (version + digest)
STEP_DEFINITION        — one Step family provided by one PLUGIN_RELEASE
PLUGIN_FAMILY          — a functional classification (containers, scm, testing, ...)
```

The Cedar-style entity set proposed later:

```text
User
Project
Pipeline
Run
Environment
Credential
Artifact
Capability

PluginFamily
Plugin
PluginRelease
StepDefinition
```

with relations:

```text
StepDefinition     providedBy    PluginRelease
PluginRelease      releaseOf     Plugin
Plugin             memberOf      PluginFamily[]      (multi-family)
StepDefinition     requires      Capability
StepDefinition     uses          Credential?         (optional)
Run                executes      StepDefinition
Run                runsIn        Environment
Run                ownedBy       User / Project
```

## `ResourceRef` (logical, structured)

```kotlin
data class ResourceRef(
    val kind: ResourceKind,           // PLUGIN | PLUGIN_RELEASE | STEP_DEFINITION | PLUGIN_FAMILY
    val namespace: String,            // publisher / scope (e.g. "rubentxu", "official")
    val identity: String,             // logical identity within the namespace
)
```

Conceptual forms:

```text
Plugin             rubentxu/containers
PluginRelease      rubentxu/containers@1.4.2  (sha256:abc...)
StepDefinition     rubentxu/containers/build
PluginFamily       rubentxu/containers (when kind = PLUGIN_FAMILY)
```

Version is **not** part of the plugin logical identity. It is part of the *release*. A
plugin can have many releases; a release belongs to exactly one plugin; a release has
exactly one digest (immutable artifact).

```kotlin
data class PluginReleaseRef(
    val plugin: ResourceRef,                          // kind = PLUGIN
    val version: SemVer,                              // version per release
    val digest: Digest,                               // sha256 of the artifact
)
```

`StepDefinition` references a specific `PluginReleaseRef` (not just a plugin), so audit
events can later answer "what code actually ran in this run".

## Family classification (multi-family allowed)

```kotlin
data class StepProviderMetadata(
    val plugin: ResourceRef,                          // kind = PLUGIN
    val release: PluginReleaseRef,                    // immutable artifact for THIS run
    val publisher: String,                            // stable identifier (e.g. "io.rubentxu")
    val families: Set<PluginFamily>,                  // multi-family allowed
    val delivery: Delivery,                           // CORE | OFFICIAL_PLUGIN | EXTERNAL_REFERENCE | DEFERRED_REMOTE | REJECTED_JENKINS_INTERNAL
    val trust: TrustMetadata,                         // signed? approved? provenance?
)
```

A plugin can declare multiple families. Examples:

```text
pipeline-git              -> { SCM, NETWORK }
pipeline-containers       -> { CONTAINERS, ARTIFACTS }
pipeline-junit            -> { TESTING, REPORTING }
pipeline-artifactory      -> { ARTIFACTS, NETWORK, SUPPLY_CHAIN }
```

## Family vs Capability (kept distinct)

```text
PLUGIN FAMILY   = functional classification of what the plugin is for
CAPABILITY      = authority / effect the plugin (or one of its Steps) can exercise
```

Example:

```text
plugin: pipeline-git

  families:      { SCM, NETWORK }
  capabilities:  { NETWORK, FILESYSTEM_READ, FILESYSTEM_WRITE, CREDENTIAL_USE, PROCESS_EXECUTION }

plugin: pipeline-junit

  families:      { TESTING, REPORTING }
  capabilities:  { FILESYSTEM_READ, REPORT_PUBLISH }
```

Cedar (or any policy engine) reasons over both dimensions independently.

## Four-level admission (frozen)

```text
1. Plugin Admission
2. Plugin Enablement
3. Step Invocation
4. Capability Admission
```

### Level 1 — Plugin Admission

> Is this code allowed to be loaded into the platform at all?

Decisions use `PluginReleaseRef` (digest, signature, provenance, publisher approval list,
approved-digest allow-list). Today: registry admission in `InMemoryStepRegistry.register`
is a structural fail-closed on duplicate keys; trust gating is a future layer that
*consumes* the same metadata without rewriting it.

### Level 2 — Plugin Enablement

> Can *this* project (or principal) use *this* provider?

Decisions use `ResourceRef(PLUGIN, ...)` + project/principal identity. Today this is not
enforced in the runtime; the data model must be ready for it.

### Level 3 — Step Invocation

> Can the principal execute *this specific* Step?

Decisions use `ResourceRef(STEP_DEFINITION, ...)` + family constraints + environment.
Today the coordinator's `StructuralFamilyResolver` discriminates `LegacyCore` vs
`Registry`; the policy surface is a future orthogonal layer.

### Level 4 — Capability Admission

> Does the Step's declared `requiredCapabilities` set pass the policy?

This is the **most security-critical** level and exists today (capability admission in
`RegistryStepInvoker` / `RegistryExecutionPreparation`). It will become the seed of the
full Cedar-bound capability surface; the rest of the model is read-only metadata so
that the policy can be expressed declaratively.

## Delivery classification vs trust decision

`Delivery` is **metadata**, not a policy verdict.

```text
CORE                  ≠ "allow everything"
OFFICIAL_PLUGIN       ≠ "allow everything"
EXTERNAL_REFERENCE    ≠ "deny"
```

The policy engine takes the delivery value into account as one input. Example Cedar
intent (illustrative only — Cedar is not wired up):

```text
permit (principal: developer, action: invoke, resource: StepDefinition)
when { resource.providedBy.release.digest in approvedSet
    && resource.providedBy.delivery in { CORE, OFFICIAL_PLUGIN }
    && resource.providedBy.families contains TESTING
    && environment != PROD }
```

The same intent over a name-based rule (`step == junit`) would be fragile to renames,
typos, and forks; the structured shape survives.

## Plugin Manifest (frozen conceptual shape)

The manifest that plugins declare (and that KSP/loader converts to typed metadata) is:

```yaml
plugin:
  id:           io.rubentxu.pipeline.containers
  version:      1.2.0
  publisher:    io.rubentxu
  delivery:     OFFICIAL_PLUGIN

  families:
    - containers
    - artifacts

  release:
    digest:      sha256:abc...
    signature:   ...
    provenance:  ...

  steps:
    - id:          container.build
      capabilities:
        - process.execution
        - filesystem.read
        - filesystem.write

    - id:          container.push
      capabilities:
        - network
        - credential.use
        - artifact.publish
```

YAML is the **conceptual** representation; the in-memory model is the structured shape
above. The loader converts YAML → typed `StepProviderMetadata` once at registration; the
runtime then reasons over the typed model.

## Step registration seam (frozen)

Today:

```text
StepDefinition
   +
StepRegistry
```

Proposed (additive; no breaking change to `StepDefinition`):

```text
StepDefinition
   +
StepProviderMetadata         (new)
   +
StepRegistration(definition, provider)
   +
StepRegistry
```

The registry stores registrations, not raw definitions. The boundary sees a
`StepRegistration` and reads `provider` for event projection + future policy use, while
the handler still reads only `definition.contract` + `definition.handler`.

This split keeps:
- `StepContract` semantically minimal (input/output/capabilities/effects).
- `StepDefinition` as the executable shape.
- `StepProviderMetadata` as the provenance/distribution shape.
- `StepRegistration` as the composition point — never part of `StepContract`.

## Event projection (frozen)

`PipelineEventEnvelope.subject` may carry a `ResourceRef` for the executing Step. The
event does NOT carry every metadata field; it carries the immutable identifiers needed
to look them up later:

```text
subject = ResourceRef(STEP_DEFINITION, publisher, plugin/step-id)
```

Audit events MAY additionally carry:

```text
plugin_release_digest    (sha256 of the artifact that ran)
plugin_publisher
plugin_families          (sorted, deterministic)
```

This connects to M8 (Run → Step execution → PluginRelease → Artifact → Provenance) so
that five years from now we can answer "what code ran in that pipeline?".

## What this document does NOT change

- `StepContract<I, O>` — unchanged. Provider metadata lives outside the contract.
- `StepHandler` — unchanged. The handler reaches only declared capabilities.
- `CoreStepRegistryFactory` — unchanged at the data level; today it stores `StepDefinition`,
  not `StepRegistration`. The composition point is forward-compatible.
- S2-A (core.error burn-down) — this document is **not** in S2-A scope. The error Step's
  `StepProviderMetadata` is `IMPLEMENTED_UNCERTIFIED` until LFC-2E2 (utilities expansion)
  adopts the new shape; that is fine.

## What this document DOES establish

- `ResourceRef` shape (PLUGIN / PLUGIN_RELEASE / STEP_DEFINITION / PLUGIN_FAMILY).
- `PluginReleaseRef` separates version+digest from logical plugin identity.
- Multi-family classification is allowed and expected.
- Family and Capability are distinct dimensions.
- Delivery classification is metadata, not a policy verdict.
- The four-level admission model (Plugin / Enablement / Invocation / Capability).
- The Plugin Manifest shape (YAML conceptual, structured typed in-memory).
- `StepRegistration(definition, provider)` is the future composition point.
- Event subject carries `ResourceRef` for the executing Step; audit events may freeze
  `plugin_release_digest` + `publisher` + `families`.

These are the **data shape invariants** that any future Cedar binding must consume.
Adding Cedar to the runtime later is an architectural decision (ADR), not a data-shape
reconstruction.
