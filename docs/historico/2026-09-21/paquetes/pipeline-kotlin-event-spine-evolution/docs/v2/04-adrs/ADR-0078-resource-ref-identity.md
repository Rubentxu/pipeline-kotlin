# ADR-0078: ResourceRef for cross-system pipeline identity

Status: PROPOSED  
Date: 2026-09-10

## Context

Events, journal projections, policy entities, future controller state, Jenkins UI and telemetry require
stable references to the same run/stage/step/operation. Ad-hoc string IDs would create semantic drift.
The earlier acronym-based working name is rejected; the domain term is `ResourceRef`.

## Decision

1. Introduce a structured domain value named `ResourceRef`.
2. Start only with PipelineDefinition, Run, Stage, Step and Operation kinds.
3. Existing typed IDs remain authoritative; ResourceRef is a projection/cross-boundary reference.
4. Events have `EventId`; `source`/`subject` carry ResourceRefs. Events are not automatically resources.
5. `EventRef = source ResourceRef + EventId` is used for causation/deduplication references.
6. Canonical textual/URI serialization is NOT frozen in this ADR; EVT-1 must spike local and remote namespace requirements first.
7. No secrets, mutable labels or scheduler/thread data may enter ResourceRef identity.
8. Adding ResourceRef must not change execution fingerprints or journal semantics.

## Consequences

A single typed identity vocabulary can later map to CloudEvents, Cedar entities, Jenkins nodes and OTel
without forcing every internal transient value to become a resource.
