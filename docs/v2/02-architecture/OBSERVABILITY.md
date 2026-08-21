# Observability

## IDs canónicos

- `runId`
- `stageId` / `stageRunId`
- `stepId` / `stepRunId`
- `attemptId`
- `operationId`
- `workerId`
- `leaseId`
- `eventId`
- `correlationId`
- `causationId`

## Tracing

Jerarquía recomendada:

```text
PipelineRun span
  Stage span
    Step span
      Attempt span
        DurableOperation span
```

Los spans son proyección/telemetría; el event log sigue siendo la verdad del estado de ejecución.

## Métricas mínimas

### Controller/Gateway
- run queue time;
- active runs;
- event ingestion rate;
- event lag;
- duplicate/rejected events;
- reconnects;
- active sessions;
- lease expirations/fencing rejects.

### Worker
- provisioning/startup duration;
- compile duration/cache hit;
- step duration;
- process CPU/memory;
- workspace bytes;
- artifact upload duration;
- event journal pending count;
- heartbeats missed.

### Kubernetes
- Pod schedule latency;
- image pull latency;
- Pod start failures;
- eviction/preemption rate;
- worker ready latency.

## Logs

Los logs voluminosos no deben convertirse en eventos completos. Usar `LogChunk`/stream storage con offsets y referencias desde eventos. Secret redaction se ejecuta antes de salir del worker y se repite defensivamente en gateway/controller.

## SLO iniciales
Ver `09-operations/SLO_SLA.md`.
