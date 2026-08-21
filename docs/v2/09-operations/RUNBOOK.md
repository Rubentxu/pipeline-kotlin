# Operations Runbook

## Worker no conecta
1. inspeccionar WorkerRequested/Provisioning/Connected events;
2. revisar protocol compatibility;
3. revisar identity/cert/token;
4. Kubernetes: Pod phase/events/image pull/service account/network;
5. no reasignar manualmente sin respetar lease/fencing.

## Worker perdido
1. confirmar heartbeat/liveness policy;
2. localizar active lease/token;
3. marcar pérdida mediante coordinator, no DB manual;
4. revisar current Step effect/retry policy;
5. programar new Attempt si procede.

## Event lag
1. gateway queue depth;
2. event store latency;
3. projector lag separado de ingestion;
4. logs deben degradarse antes que perder domain events;
5. habilitar snapshot/scale projector si procede.

## Replay divergence
1. detener automatic recovery;
2. comparar source/runtime/plugin digests;
3. inspeccionar expected/actual operation fingerprint;
4. decidir continuar con original artifact o fork/migration;
5. nunca forzar cursor a mano sin evento/audit.

## Secret leak suspected
1. revoke credential/lease;
2. stop affected runs si es necesario;
3. identificar log/event/storage surfaces;
4. rotate secret;
5. preserve forensic metadata without redistributing secret;
6. defect P0/P1 según exposición.

## Jenkins controller restart
FlowExecution carga last sequence y solicita replay tail. Si projection falla, preservar run remoto y reconstruir; no cancelar workload automáticamente salvo policy.

## Graph corruption/loss
Borrar/recrear projection desde event store. Si event log está íntegro, graph loss no debe implicar run data loss.
