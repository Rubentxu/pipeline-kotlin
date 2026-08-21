# Event Model Specification

## 1. Invariante

Un evento es un hecho inmutable ya ocurrido. Nunca se “edita”; correcciones se expresan mediante nuevos eventos.

## 2. Envelope wire

```protobuf
message EventEnvelope {
  string protocol_version = 1;
  string event_schema_version = 2;
  string event_id = 3;
  string run_id = 4;
  uint64 sequence = 5;
  string correlation_id = 6;
  string causation_id = 7;
  string actor_id = 8;
  string frame_id = 9;
  string lease_id = 10;
  uint64 fencing_token = 11;
  google.protobuf.Timestamp occurred_at = 12;
  EventPayload payload = 20;
}
```

## 3. Familias

### Run
- RunCreated
- PipelineSourceResolved
- PipelineCompilationStarted/Completed/Failed
- RunStarted
- RunCompleted/Failed/Cancelled

### Stage/Step
- StageStarted/Completed/Failed
- StepScheduled/Started/Completed/Failed/Cancelled
- AttemptStarted/Completed/Lost

### Durable operation
- OperationScheduled
- OperationStarted
- OperationProgress
- OperationCompleted
- OperationFailed

### Worker
- WorkerRequested
- WorkerProvisioning
- WorkerConnected
- WorkerReady
- WorkerLeased
- WorkerHeartbeat
- WorkerLost
- WorkerDraining
- WorkerTerminated

### Control
- RetryScheduled
- TimeoutScheduled/Fired
- CancellationRequested/Acknowledged
- ApprovalRequested/Granted/Denied

### Supply-chain
- ArtifactPublished
- SbomPublished
- ProvenanceGenerated
- ArtifactSigned
- DeploymentRecorded

## 4. Payload rules

Prohibido:
- arbitrary `Any`;
- Java/Kotlin class serialization;
- Throwable object;
- secrets;
- unbounded log bodies.

Errores usan estructura:

```text
code
kind
message_safe
retryable
source_location
step_id
attempt_id
details (schema-controlled)
```

## 5. Ordering

`sequence` es monotónica por run event stream aceptado. El gateway/controller deduplica `eventId` y valida fencing.

No asumir orden global entre runs.

## 6. Delivery

At-least-once. ACK confirma secuencia durable. Worker mantiene eventos no ACK en journal y los reenvía tras reconnect.

## 7. Schema evolution

- no reutilizar field numbers Protobuf;
- campos nuevos opcionales/default-safe;
- removal sólo en major;
- semantic change requiere nuevo payload/version;
- compatibility tests con golden binaries.

## 8. Event vs metric/log

Event = cambio factual de dominio.  
Metric = observación agregable.  
Log = stream humano/detalle operativo.

No convertir cada línea stdout en domain event.
