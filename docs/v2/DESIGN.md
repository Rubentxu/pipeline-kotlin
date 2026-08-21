# DESIGN — Pipeline Kotlin V2

Este documento es el punto de entrada técnico corto. El detalle normativo vive en `02-architecture`, `03-specifications` y `04-adrs`.

## Problem statement

Mantener la experiencia familiar de Jenkins Pipeline mientras se elimina del nuevo runtime la necesidad de ejecutar/evaluar el lenguaje de pipeline en controller, serializar continuations CPS o usar Jenkins Remoting como contrato de worker.

## Solution shape

```text
.pipeline.kts
    │ Kotlin Scripting Adapter (version pinned)
    ▼
Durable Kotlin Runtime ──► local journal
    │                          │
    │ Step commands            │ events
    ▼                          ▼
Worker capabilities ─────► Worker Protocol
                               │
                               ▼
                         Event Store
                               │
                    ┌──────────┼──────────┐
                    ▼          ▼          ▼
                 Jenkins    Execution  Provenance
                 FlowGraph     Graph      Graph
```

## Design center

- Kotlin 2.4.10 baseline, context parameters.
- `.pipeline.kts` via Custom Scripting, experimental API contained.
- KSP-generated Step ecosystem.
- runtime replay instead of CPS.
- workers own compile/evaluate/execute.
- Protobuf/event contracts.
- Kubernetes ephemeral workers.
- providers for credentials/artifacts/storage.
- graph projections over append-only history.
- Jenkins adapter via FlowDefinition/FlowExecution.

## Critical invariants

1. No user pipeline compile/evaluate on Jenkins controller happy path.
2. No secret values in event log/provenance graph.
3. No accepted event from stale fencing token.
4. No implicit replay of confirmed external mutation.
5. No graph state that cannot be derived from accepted history.
6. No compiler/scripting experimental API leaking into domain/application.
7. No stable Step without effects/replay/capability metadata.

## Development rule

If a feature forces us to violate one invariant, stop and write/revisit an ADR before implementation.
