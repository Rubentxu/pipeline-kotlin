# Test Strategy

## Pirámide adaptada al runtime

### Unit
- state machines;
- IDs/value objects;
- reducers;
- effect/replay policy;
- manifest validation;
- scheduler scoring;
- credentials policy.

### Contract
- WorkerTransport;
- EventStore;
- GraphProjectionStore;
- CredentialProvider;
- ArtifactStore;
- WorkerProvisioner;
- Plugin descriptors;
- protocol wire compatibility.

### Integration
- scripting host compile/evaluate;
- worker+journal;
- gateway+worker;
- Kubernetes API/test cluster;
- Jenkins plugin test harness;
- object/graph stores.

### End-to-end
`.pipeline.kts` real desde SCM hasta artifact/projection/Jenkins UI.

### Chaos
- kill worker;
- kill Pod;
- disconnect network;
- duplicate events;
- delayed events;
- gateway restart;
- controller restart;
- stale fencing token.

## Property tests

Especialmente útiles para reducers/state machines:
- replay same events => same state;
- duplicate event => same state;
- invalid transition rejected;
- sequence/fencing invariants;
- manifest round-trip;
- serializer round-trip.

## Golden tests

- Protobuf wire fixtures;
- DSL compile diagnostics normalized;
- Jenkins Familiarity snippets;
- Event trace fixtures;
- graph projection snapshots.

## Security tests

- secret redaction;
- no secret fields in event schemas;
- path traversal/workspace isolation;
- network policy profiles;
- plugin digest/signature failures;
- credential scope denial.

## Rule

Coverage % no es el único gate. Los invariants críticos deben tener tests explícitos con nombre y trazabilidad a ADR/UAT.
