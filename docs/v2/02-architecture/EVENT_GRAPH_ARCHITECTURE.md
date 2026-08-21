# Event + Graph Architecture

## Invariante

```text
EVENT LOG = source of truth
GRAPH = materialized projection
```

Inspiración: el patrón event-sourced graph de ActiveGraph, adaptado a un motor CI/CD determinista. El graph modela hechos/relaciones; no sustituye la state machine ni introduce coordinación agentic en el camino crítico.

## Proyecciones principales

```text
                    Event Log
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      Plan Graph   Execution    Provenance
                    Graph         Graph
          │            │            │
          └────────────┼────────────┘
                       ▼
               Queries / UI / AI
```

## Execution Graph

Nodos:
- PipelineRun
- StageRun
- StepRun
- Attempt
- Worker
- WorkerLease
- DurableOperation
- Failure
- Approval

Relaciones:
- CONTAINS
- EXECUTED_ON
- ATTEMPT_OF
- CAUSED_BY
- RETRY_OF
- BLOCKED_BY
- REQUIRES
- PRODUCED

## Provenance Graph

Nodos:
- Repository
- Commit
- PipelineSource
- PluginArtifact
- WorkerImage
- BuildArtifact
- SBOM
- Signature
- Deployment
- Environment

Relaciones:
- DERIVED_FROM
- BUILT_BY
- USED_PLUGIN
- RAN_ON_IMAGE
- HAS_SBOM
- SIGNED_BY
- DEPLOYED_TO
- TESTED_BY

## Relation policies

Se adopta conceptualmente la idea de “behavior on relation”, pero con semántica determinista:

```kotlin
interface RelationPolicy<R : Relation> {
    fun evaluate(relation: R, view: GraphView): PolicyResult
}
```

Ejemplos:
- `Artifact DEPLOYED_TO Production` requiere signature/SBOM/policy pass.
- `Step REQUIRES Credential` exige lease/scopes válidos.
- `Step DEPENDS_ON Step` mantiene bloqueo/desbloqueo derivado.

## Causalidad

Todo EventEnvelope relevante lleva `correlationId` y `causationId`, permitiendo reconstruir:

```text
WorkerLost
  └─causes→ AttemptLost
      └─causes→ RetryScheduled
          └─causes→ WorkerRequested
```

## Graph store

No forma parte del hot path transaccional. `GraphProjectionStore` es un port. La primera implementación puede ser in-memory/SQLite para run local; una proyección global puede usar un graph DB sin hacer de él la fuente de verdad.
