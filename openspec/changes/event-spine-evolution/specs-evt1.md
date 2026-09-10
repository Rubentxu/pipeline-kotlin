# EVT-1 Specification — ResourceRef + PipelineEventEnvelope

Status: PROPOSED (cycle evt-1-resource-ref-envelope)
Design authority: `docs/v2/06-design/RESOURCE_REF_MODEL.md`
Baseline: `1f39d17d` — behavior-preserving slice.

## Capabilities

- `resource-ref`: typed cross-cutting identity (ResourceRef, ResourceKind, EventRef).
- `event-envelope`: PipelineEventEnvelope + deterministic projection from DomainEvents + codecs.
- `cloudevents-characterization`: mapping tests only; no SDK dependency, no transport.

## Requirements

### R1 — ResourceRef construction (resource-ref)

ResourceRef MUST be constructible only through typed deterministic builders for
kinds PIPELINE_DEFINITION, RUN, STAGE, STEP, OPERATION. Ad-hoc string
construction MUST fail to compile/be rejected by architecture fitness.

#### Scenario: deterministic identity
- GIVEN a logical Pipeline/Run/Stage/Step/Operation identified by (definitionId, runId, stageIndex, stepIndex, opKey)
- WHEN a ResourceRef is built twice
- THEN both refs are equal and serialize identically.

#### Scenario: distinct operations
- GIVEN two operations with different opKeys in the same step
- WHEN their OPERATION refs are built
- THEN the refs are NOT equal.

#### Scenario: special characters fail-closed
- GIVEN an identity segment containing a path separator, control char, or empty string
- WHEN a builder is invoked
- THEN construction fails with a typed error (no silent sanitization).

### R2 — EventRef (resource-ref)

EventRef MUST equal `(source: ResourceRef, id: EventId)` and be unique per occurrence.

#### Scenario: uniqueness
- GIVEN two events with the same source but different EventIds (and vice versa)
- WHEN EventRefs are built
- THEN the pairs are distinct; equal pairs only when both components are equal.

### R3 — Envelope projection (event-envelope)

An EnvelopeProjector MUST map any DomainEvent to a PipelineEventEnvelope carrying
eventId, kind, occurredAt, sequence (as projection), source ResourceRef, subject ResourceRef?,
causation/correlation EventRefs where derivable. Producers MUST NOT change.

#### Scenario: roundtrip identity
- GIVEN a DomainEvent
- WHEN projected → encoded → decoded
- THEN the decoded envelope preserves identity fields (eventId, source, subject, kind, sequence).

#### Scenario: replay stability
- GIVEN the same logical run reconstructed/replayed
- WHEN envelopes are re-projected
- THEN source/subject refs are identical to the original projection.

#### Scenario: parallel order independence
- GIVEN branches A and B whose completion order is inverted between two runs
- WHEN envelopes are projected
- THEN every ref is identical across both runs.

#### Scenario: subject resolution
- GIVEN StepStarted(stageIndex=0, stepIndex=2, ...)
- WHEN projected
- THEN subject is the STEP ref `(run, stage 0, step 2)`; source is the RUN ref.
- GIVEN RunStarted
- WHEN projected
- THEN subject is the RUN ref (or null per codec decision — one choice, tested).

### R4 — Versioning (event-envelope)

PipelineEventEnvelope MUST carry an explicit envelope format version.

#### Scenario: version present
- GIVEN any projected envelope
- WHEN inspected
- THEN version is a declared constant; decode of unknown major version fails typed.

### R5 — CloudEvents characterization (cloudevents-characterization)

A characterization mapper MUST map: eventId→id, source ResourceRef→source,
subject→subject, kind(+version)→type. No SDK dependency; no transport.

#### Scenario: mapping table
- GIVEN a projected envelope
- WHEN the characterization mapper runs
- THEN the four mappings hold as plain values; test asserts the table, not a wire format.

### R6 — Behavior preservation (global)

Journal schema, fingerprints, replay/reconciliation, outcomes and the P4-EX gate
MUST be unchanged.

#### Scenario: P4-EX intact
- GIVEN trunk with EVT-1 applied
- WHEN `examples/run.sh` runs
- THEN exit 0, 10/10, all contracts green.

#### Scenario: journal zero-diff
- GIVEN a fixed script executed before and after EVT-1
- WHEN journal op keys/fingerprints are compared
- THEN they are semantically identical.

#### Scenario: no ad-hoc refs
- GIVEN production sources
- WHEN architecture fitness scans for manual ResourceRef construction outside the projector
- THEN zero occurrences.
