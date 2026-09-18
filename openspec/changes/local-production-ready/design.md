# Design: local-production-ready

## 1. Workstreams

```text
           ┌────────── LPR-A authority/CI ──────────┐
           │                                        │
           ▼                                        ▼
LPR-E execution/body ──► LPR-D honest DSL ──► product corpus
           │                                        │
           └─────────────┐              ┌───────────┘
                         ▼              ▼
                     LPR-O observation/perf
                              │
                              ▼
                     LPR-C certification
                              │
                              ▼
                     LPR-R release/SDKMAN
                              │
                              ▼
                     LPR-F dogfooding
```

## 2. Execution seam

Use current canonical execution as baseline. Do not create a second runtime. Extract BodyExecutionEngine and later InvocationEngine behind existing behavior.

### Body model

Bodies are durable references with generic cardinality (`None/Single/Named`). Body execution policy is a closed ADT. The engine interprets policy; plugin key selects definition/descriptor, not central branch behavior.

### Migration rule

Every extraction uses characterization + parity. Delete legacy/special code only after the new path is production-reachable and fitness-gated.

## 3. DSL seam

Declarative compilation must be referentially transparent with respect to runtime effects. Runtime-returning APIs live in an explicit continuation/runtime scope. Unsupported language features fail closed during compile/admission.

## 4. Observation seam

The observation plane is read-side only.

### Semantic events

DomainEvent ingress -> bounded writer -> batch commit -> EventHistory/EventTail.

### Console

Process pump -> streaming redaction -> append-only transcript. Optional wakeup gives low latency; byte offset gives recovery.

### Projection

`ObservationReader` merges references to event/console/diagnostics for presentation without claiming one durable global ordering. Views and JSON rendering execute outside producers.

## 5. Performance design

### Current baseline risks to remove

- connection open/close + autocommit per event;
- per-chunk `runBlocking` bridge;
- process output duplication into semantic event payloads;
- read-all history/tail patterns where bounded read is sufficient.

### Target properties

- O(buffer) memory;
- renderer speed independent from process drain;
- micro-batched event persistence;
- sequence monotonic across process reopen;
- bounded tail/inspect reads;
- no silent event loss.

## 6. CLI product surface

Parser implementation may evolve, but contract is independent:

```text
run       execute
validate  compile/admit without effects
doctor    environment/product readiness
version   reproducibility metadata
events    semantic history/tail
logs      transcript read/tail (may be subcommand or inspect projection)
inspect   bounded diagnostic projection
credentials existing local credential management
```

Views: normal/events/full/console/quiet. Formats: text/jsonl/json.

## 7. Distribution

Use Gradle application distribution already present. Release ZIP becomes immutable authority. All installation channels consume it.

## 8. Compatibility

Do not freeze every current surface. Freeze only Gate-1 supported profile after honesty/certification. Maintain versioned corpus from then onward.

## 9. Rollout

No feature flag that chooses a second execution engine. Migration flags, if temporarily needed, must exist only in tests/diagnostics or select observation projection, never different semantic algorithms in production.
