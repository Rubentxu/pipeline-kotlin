# Worker Protocol Specification

## 1. Objetivo

Conectar control plane y workers sin Java object RPC/serialization y con semántica explícita de resiliencia distribuida.

## 2. Contrato

Formato: Protobuf.  
Semántica: bidirectional session + commands/events + ACK/replay.  
MVP transport: binary WebSocket sobre HTTPS cuando Jenkins termina la conexión.  
Target scale: external Worker Gateway con gRPC bidireccional/mTLS.

## 3. Handshake

WorkerHello incluye:
- workerId/instanceId;
- runtimeVersion;
- protocol min/max;
- Kotlin/compiler capabilities;
- OS/arch;
- execution capabilities;
- plugin cache digests;
- security/isolation capabilities;
- auth attestation.

Gateway responde NegotiatedSession:
- protocol version;
- heartbeat interval;
- max message size;
- accepted capabilities;
- sessionId;
- time skew info.

## 4. Commands

- PrepareRun
- CompilePipeline
- StartRun
- CancelRun
- ExecuteControlReply
- GrantCredentialProjection
- DrainWorker
- Reconcile(lastAckedSequence)

## 5. Events
Usa `EventEnvelope` de `EVENT_MODEL.md`.

## 6. ACK/replay

Worker:
1. append local event;
2. send;
3. conserva hasta ACK durable;
4. tras reconnect anuncia last local sequence;
5. gateway devuelve last accepted sequence;
6. worker reenvía tail.

Controller reducer es idempotente por `eventId` y state transition.

## 7. Leases y fencing

```text
Worker A -> lease token 42
network partition
lease expires
Worker B -> lease token 43
A reconnects with 42 -> REJECT
```

Todo evento que pueda mutar execution state lleva `leaseId/fencingToken`.

## 8. Heartbeat

Heartbeat no es domain history completo salvo transiciones relevantes. Gateway mantiene liveness; `WorkerLost` se emite cuando policy determina pérdida.

## 9. Backpressure

- bounded outbound journal queue;
- prioridad control > state events > log chunks;
- logs pueden spill a storage;
- domain events no se descartan silenciosamente;
- gateway puede advertir slow-consumer.

## 10. Security

Target gRPC:
- mTLS;
- worker identity/claims;
- short-lived cert/token;
- replay protection de session;
- max sizes;
- schema validation;
- no polymorphic Java serialization.

## 11. Version negotiation

Protocol major incompatible; minor compatible hacia atrás. Worker y gateway intercambian rango soportado y fallan antes de asignar lease si no existe intersección.

## 12. Por qué no gRPC directo a cada worker desde Jenkins inicialmente

Mantener miles de conexiones/Netty channels dentro del controller contradice el objetivo de descargarlo. El gateway externo permite escalar transport/session state independientemente.
