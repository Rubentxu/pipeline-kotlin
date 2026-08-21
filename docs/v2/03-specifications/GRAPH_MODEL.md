# Graph Model Specification

## 1. Regla

El graph model es una proyección reconstruible del Event Log. No es la única base de datos transaccional del runtime.

## 2. Graphs

### Plan Graph
PipelineDefinition, StageDefinition, StepDefinition, requirements y relations.

### Execution Graph
Run, StageRun, StepRun, Attempt, Operation, Worker, Lease, Failure, Approval.

### Provenance Graph
Repository, Commit, SourceDigest, Plugin, WorkerImage, Artifact, SBOM, Signature, Deployment, Environment.

## 3. Typed relations

```text
CONTAINS
DEPENDS_ON
REQUIRES
EXECUTED_ON
ATTEMPT_OF
RETRY_OF
CAUSED_BY
PRODUCED
DERIVED_FROM
HAS_SBOM
SIGNED_BY
DEPLOYED_TO
TESTED_BY
```

## 4. Projection contract

```kotlin
interface GraphProjector {
    suspend fun apply(event: EventEnvelope)
    suspend fun rebuild(events: Flow<EventEnvelope>)
}

interface GraphQueryPort {
    suspend fun neighborhood(id: GraphId, depth: Int): GraphView
    suspend fun causalChain(eventId: EventId): List<GraphElement>
    suspend fun provenance(artifact: ArtifactId): ProvenanceView
}
```

## 5. Behaviors/policies

La idea ActiveGraph de behaviors sobre relaciones se adapta como pure/deterministic policy evaluation. No se permite que una graph relation lance side effects arbitrarios en el hot path.

## 6. Fork/diff

Fork hereda prefix del event stream y proyecta un nuevo graph. Diff compara:
- event divergence;
- node/relation divergence;
- artifact outputs;
- durations/resources;
- test results;
- policy outcomes.

## 7. Storage

Adapters potenciales:
- InMemory;
- SQLite/local graph representation;
- LadybugDB para embedded/offline experiments;
- PostgreSQL+AGE/FalkorDB/u otro para proyección central.

La selección queda detrás de port y benchmark real.
