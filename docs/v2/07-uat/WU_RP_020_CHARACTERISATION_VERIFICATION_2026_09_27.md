# WU-RP-020 — Caracterización SqliteEventStore: verificación 2026-09-27

## Estado factual

El test `v2/pipeline-events/src/test/kotlin/dev/rubentxu/pipeline/v2/events/SqliteEventStoreConcurrencyCharacterisationTest.kt`
está **presente en `main`** (HEAD `a1ea5985`). Introducido en commit
`06b39148` ("test(rp-020): SqliteEventStore characterisation suite
(10 properties)") y refinado en commit `59a576e5` ("test(rp-020):
flush() before eventsFor() after restart (CI race fix)"), ambos
del 2026-09-22.

## Verificación bajo TRAIN-1 (2026-09-27T09:43Z)

**Rama:** `wu/rp-020-sqlite-event-store` (creada desde main@a1ea5985).
**Estado:** no hay código de producción modificado; sólo se ejecuta el
test-side existente y se valida.

### Run (L1)

```bash
cd v2 && ./gradlew :pipeline-events:test --tests 'SqliteEventStoreConcurrencyCharacterisationTest'
# BUILD SUCCESSFUL in 4s
```

### XML canary

```text
TEST-dev.rubentxu.pipeline.v2.events.SqliteEventStoreConcurrencyCharacterisationTest.xml
  tests=10 skipped=0 failures=0 errors=0
  timestamp=2026-09-27T09:43:32.544Z
  hostname=bazzite-rubentxu
  total time=0.974s
```

### Cobertura del charter ROADMAP §4 WU-RP-020

| Charter property | Test | Property # | Status |
|---|---|---|---|
| sequence asignada vs orden inserción | `smaller explicit sequence does not rewind the counter` | 4 | PASS |
| sequence asignada vs orden inserción | `multi-run sequence counters are independent` | 5 | PASS |
| flush con productores activos | `flush waits for events enqueued by concurrent producers` | 2 | PASS |
| close con productores activos | `close drains pending events via flush barrier` | 3 | PASS |
| close con productores activos | `close idempotently rejects further appends` | 7 | PASS |
| reinicio | `restart continues sequences from MAX(sequence) per run` | 6 | PASS |
| gap (counter monotonicity) | `smaller explicit sequence does not rewind the counter` | 4 | PASS |
| gap (explicit large seq) | `explicit large sequence advances the per-run counter` | 10 | PASS |
| error de writer | `close idempotently rejects further appends` | 7 | PASS (limitado) |
| replay | `replay orders by rowid ASC (commit order), not sequence ASC` | 9 | PASS |
| arrays anidados | `nested array payload round-trips losslessly across reopen` | 8 | PASS |
| (extra) flush barrier basic | `flush is a barrier for events enqueued before it` | 1 | PASS |

**Cobertura: 7/7 charter properties + 3 extras = 10 properties total.
10/10 tests PASS.**

### Limitación documentada (honesta, no regresión)

El "error de writer" se cubre vía el path `close() → append()` que
dispara `IllegalStateException` cuando el writer está cerrado. El path
interno `writerLoop → bindInsert throws → writerError` no se simula
directamente porque `Thread.interrupt` race es frágil (nota en
`SqliteEventStoreConcurrencyCharacterisationTest.kt:194-199`). Esta
limitación está documentada en el test source y NO es regresión — es
la honestidad de "qué se probó y qué no" que el charter pide.

## Salida (rule 3 CIERRE REAL)

- Tests deterministas escritos: ✓ (10/10 PASS, JUnit @Timeout(60))
- Criterios observables documentados: ✓ (cada test cita una property
  en su comentario de cabecera)
- Sin modificación del contrato de sequence: ✓ (test-side only)
- Caracterización reproducible con XML canary: ✓ (timestamp 09:43:32Z,
  hostname bazzite-rubentxu)
- Sin regresiones: ✓ (BUILD SUCCESSFUL en 4s; UP-TO-DATE incremental)

## Estado del ciclo

- Cycle `p-733fb505b5a6bd2d/train-1-rp2-characterization` OPEN/explore.
- WI `7814ef14-8099-40d8-85d6-819eb7b7838a` activa, en transición a Done.
- Próximo WU depende de los resultados: si la caracterización
  descubre gaps, abrir WU-RP-021 (catalogar rutas) o WU-RP-022
  (baseline performance). Si no, pasar a WU-RP-030 (fitness).

## Audit trail

- `sddk cycle start --name train-1-rp2-characterization` → cycle OPEN
- `sddk cycle lock acquire --owner agent:cli` → fencing_token=1
- `sddk plan work-item create ... rp-020 ...` → id 7814ef14
- `sddk plan work-item transition ... --to active` → active
- `git checkout -b wu/rp-020-sqlite-event-store` desde main@a1ea5985
- `gradle :pipeline-events:test --tests SqliteEventStoreConcurrencyCharacterisationTest`
  → BUILD SUCCESSFUL, 10/10 PASS
- Este receipt creado y commiteado en rama de verificación.
