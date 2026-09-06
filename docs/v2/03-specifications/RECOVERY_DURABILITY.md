# Recovery & Durability Specification

## 1. Objetivos

Sobrevivir a:
- worker process restart;
- worker connection loss;
- Kubernetes Pod eviction;
- gateway restart;
- Jenkins/controller restart;
- duplicated messages;
- late events de worker antiguo.

## 2. State machines

### Run
```text
CREATED -> QUEUED -> LEASED -> COMPILING -> RUNNING
                                  │          ├-> WAITING_APPROVAL
                                  │          ├-> RECOVERING
                                  │          └-> CANCELLING
                                  └----------> FAILED
RUNNING/RECOVERING -> SUCCEEDED | FAILED | CANCELLED
```

### Step Attempt
```text
PENDING -> SCHEDULED -> RUNNING -> SUCCEEDED
                              ├-> FAILED
                              ├-> CANCELLED
                              └-> LOST
```

## 3. Local journal

MVP recomendado: SQLite WAL o append log con atomic record framing. Contiene:
- run metadata;
- compiled source/artifact ref;
- event outbox;
- durable operation results;
- task handles;
- log offsets.

No contiene secretos persistentes salvo provider explícito que garantice cifrado/TTL y sea inevitable.

## 4. Recovery algorithm

1. cargar accepted event history;
2. validar pipeline/runtime/plugin digests;
3. reconstruir projection/cursor;
4. reconciliar durable tasks;
5. reejecutar DSL;
6. cada durable operation consulta history;
7. reusa resultado compatible o ejecuta según policy;
8. continúa emitiendo nuevos events.

## 5. Divergence

Si durante replay aparece una operación distinta a la esperada:
- fail closed con `ReplayDivergence`;
- incluir expected/actual operation fingerprints;
- ofrecer fork/migration, nunca adivinar.

## 6. Pod loss

Un process/container desaparecido se marca LOST. Se crea nuevo Attempt según policy. El nuevo Attempt no hereda falsamente el task handle antiguo.

## 7. Exactly-once

No se promete exactly-once de side effects externos. Se ofrecen:
- idempotency keys;
- provider-specific dedupe;
- effect/replay policy;
- transactional outbox donde aplique;
- human approval para replay peligroso.

## 8. Addendum EM (2026-09-06, cycle em-0 — ADR-0067/0068)

- **Terminal canónico `INTERRUPTED`**: la cancelación cooperativa
  (`CancellationException` en el boundary durable) se journaliza como
  `OperationStatus.INTERRUPTED` con `InterruptionKind`
  (`USER_ABORT`/`PARENT_CANCELLED`/`SUPERSEDED`/`SHUTDOWN`); el marcador
  `PipelineInterruptedException` NO extiende `CancellationException`
  (ADR-0068). Anclas: SPIKE-016 N4/N5.
- **`FAILED_TIMEOUT` sigue siendo el estado de reporting** del watchdog de
  deadline (ADR-0047/ADR-0028) con la causa original preservada; control flow
  usa `InterruptionKind.TIMEOUT` (SPIKE-016 N6).
- **`schemaVersion` en registros persistidos**: todo registro durable lleva
  `schemaVersion` (default 1); los readers declaran un máximo y fallan
  cerrados con `IncompatibleJournalSchema` ante versiones superiores
  (ADR-0067). Ancla: SPIKE-016 N3.
- **Gate nombrada `RECOVERY_DURABILITY`**: cubre los anclas UAT-REC-* más
  SPIKE-016 N6 (crash del worker durante la ventana FAILED_TIMEOUT) y la
  matriz de cortes SPIKE-016 E3/E3a. Gate de release para las fases
  EM-1/EM-2/EM-5/EM-8.
