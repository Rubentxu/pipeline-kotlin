# ResourceRef — cross-cutting identity model

Status: PROPOSED  
Uses the neutral domain term `ResourceRef` for cross-system resource identity.

## Motivation

Events, journal projections, future Cedar entities, controller state, Jenkins FlowNodes and OpenTelemetry
need to talk about the same pipeline entities without each inventing a different string identifier.

The solution should resemble the useful property of AWS ARNs — stable, hierarchical references — without
copying AWS syntax or pretending an unregistered URN/URI namespace is standardized.

## Decision shape

Use **`ResourceRef`** as the domain term.

```kotlin
data class ResourceRef(
    val namespace: ResourceNamespace,
    val kind: ResourceKind,
    val path: NonEmptyList<ResourceSegment>,
)
```

The exact canonical text/URI serialization is **deferred to EVT-1 spike**. The domain type is structured;
string parsing belongs to codecs/adapters.

## Minimal kinds now

Only entities that need identity outside the immediate function receive ResourceRefs:

- `PIPELINE_DEFINITION`
- `RUN`
- `STAGE`
- `STEP`
- `OPERATION`

Likely future kinds when justified:

- `PARALLEL_BRANCH`
- `CREDENTIAL`
- `ARTIFACT`
- `ENVIRONMENT`
- `AGENT` / `AGENT_POOL`
- `POLICY_PACK`
- `EVENT_CONTRACT`

Explicitly **not resources**: `ExecutionContext`, overlays, Retry/Parallel decisions, prepared inputs,
codec values and other transient domain values.

## Existing typed IDs remain authoritative locally

`ResourceRef` does not replace `RunId`, `StageId`, `StepId` or `OpId`.

```text
RunId / StageId / StepId / OpId
        |
        v
ResourceRef projection
        |
        +-> events
        +-> policy entities
        +-> controller/Jenkins
```

No journal schema migration is required merely to introduce ResourceRef.

## Event identity

Events themselves keep `EventId`. They refer to addressable resources:

```text
EventId                occurrence identity
source: ResourceRef    emitting/run context
subject: ResourceRef   affected Stage/Step/Operation/etc.
```

For causation/deduplication use:

```kotlin
data class EventRef(
    val source: ResourceRef,
    val id: EventId,
)
```

This aligns with CloudEvents, where `source + id` identifies a distinct event occurrence and `subject`
identifies the object inside the source context.

## Required laws

- deterministic: same logical entity -> same ResourceRef;
- scheduler/time independent unless time is part of the entity's actual identity;
- no secrets in identifiers;
- no mutable attributes (branch name, environment labels, policy result) unless they are true identity segments;
- typed construction; no ad-hoc concatenation throughout production code;
- round-trip codec tests;
- versioned serialization when a canonical wire format is frozen;
- no execution fingerprint change merely because a ResourceRef projection is added.

## Cedar mapping

Cedar principal/action/resource are roles in an authorization request. ResourceRef identifies entities;
it does not mean every entity is always Cedar's `resource` role.

Examples:

```text
principal = PipelineRun(ResourceRef)
action    = UseCredential
resource  = Credential(ResourceRef)
```

or later:

```text
principal = Controller/Actor
action    = CancelRun
resource  = PipelineRun(ResourceRef)
```

## CloudEvents mapping

The CloudEvents adapter converts ResourceRef to a valid URI-reference for `source` and usually a stable
string for `subject`. The first implementation must test local-only identities and future controller
namespaces. Do not freeze an attractive but non-portable URI scheme without the spike.
