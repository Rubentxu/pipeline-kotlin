# PK-58A — Lectura viva/recuperable y presupuesto de recursos

**Última actualización:** 2026-10-13T13:00Z
**Estado de entrada:** NO INICIADO (PK-57A publicado como CANDIDATE_PUBLISHED)
**Rama prevista:** `pk-release-0-58-consolidation` (nueva, basada en `main` @ `a22734cc`)
**Release objetivo:** `v0.58.0-rc1`
**WorkItem SDDK:** (a registrar al autorizar)

## Estado de entrada (inventario)

### Puertos de lectura que YA EXISTEN (no recrear)

| Puerto | Módulo | Estado |
|---|---|---|
| `OutputReadPort` | `:pipeline-output` | Existe. `read`, `readRange`, `readRangeDigested`; refusals ADT |
| `OutputTailPort` | `:pipeline-output` | Existe. `Open(committedEnd)`, `Sealed(finalEnd)` sealed |
| `OutputFrameIndex` | `:pipeline-output` | Existe |
| `EventTail` | `:pipeline-events/identity/EventHistoryPorts` | Existe |
| `EventRecordReadPort` | `:pipeline-events/identity` | Existe + adapter SQLite |
| `EventShapeReadPort` | `:pipeline-events/shape` | Existe + adapter store |
| `EventStore` (InMemory + SQLite) | `:pipeline-events` | Existe con tests de concurrencia y round-trip |
| `SegmentOutputStore` | `:pipeline-output/store` | Existe con tests de crash y cross-process |
| `CommitWakeupPort` | — | **NO EXISTE** (gap identificado por SPEC-01) |

### Implementaciones de observación que YA EXISTEN

- `:pipeline-application/observation/`: `LiveOutputDrain`, `LiveOutputPresentation`,
  `ObservationOutputReader`, `ObservationOutputFollower`, `ObservationQuery`,
  `ObservationView`, `ObservationWakeup`, `RunObservationOutput`, etc.
- OBS-PC evolutivo: `IMPLEMENTED_PARTIAL` / `NOT_PRODUCT_CERTIFIED`
  (con 107 + 201..207 entradas implementadas; ver `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`)

### Tests que YA EXISTEN (no rehacer)

- `ObsBLiveOutputIngressTest`, `ObsCChannelAndTailCharacterisationTest`,
  `ObsE5TailTest`
- `ObsGReaderRecoveryInterferenceUatTest`,
  `ObsPcReadRecoveryOwnershipUatTest`,
  `ObsPcReadRecoverySeamFitnessTest`
- `SegmentFrameIndexCrashTest`, `SegmentFrameIndexCrossProcessOrdinalTest`
- `DurableReadTruthFitnessTest` (gate de arquitectura)
- `DurableReadTruthTest` (eventos)

### Brecha con el mandato PK-58A

PK-58A pide:
1. **`CommitWakeupPort`** (SPEC-01 §"CommitWakeups read-side opcional"). NO EXISTE.
2. **UAT-PK-001..008** y **AAT-01..04** etiquetados como tales. NO EXISTEN con esos
   nombres (los tests OBS-PC cubren el dominio pero con naming distinto).
3. **Medición de RSS/heap, FD, latencia p95, bytes escaneados** en streams
   pequeños y grandes. NO EXISTE measurement harness.
4. **Sin thread/proceso por frame/página**. Por verificar bajo carga.
5. **Contrato de capacidad y compatibilidad ABI publicado**. Falta publicación
   explícita (cambia la línea base 0.57 → 0.58).
6. **`v0.58.0-rc1` publicado**. NO EXISTE.

## Tareas (propuesta)

> Auditoría pre-impl. — no requiere Gradle.

- [ ] **T1** Inventario completo de read/tail/frame ports contra el spec SPEC-01
  PK-L1..PK-L7. Documentar en `docs/v2/07-uat/PK-58A-ports-audit.md` qué
  requisitos están cubiertos, cuáles parciales, cuáles ausentes. Sin tocar
  código de producción.
- [ ] **T2** Decidir si `CommitWakeupPort` se introduce como interface nuevo
  (recomendado por SPEC-01) o se reusa `ObservationWakeup` que ya existe.
  ADR-style decisión registrada; si se introduce, evitar acoplar dominios.

> Implementación — Gradle requerido.

- [ ] **T3** Si T2 decide interface nuevo: introducir `CommitWakeupPort` en
  `:pipeline-output` con el contrato mínimo (hint coalescible, recheck +
  polling). Tests del seam: `CommitWakeupPortTest`.
- [ ] **T4** Etiquetar los tests OBS-PC existentes con los IDs UAT-PK-001..008
  y AAT-01..04 que correspondan (o crear los tests que falten si hay gaps
  no cubiertos por OBS-PC). Sin relajar cobertura.
- [ ] **T5** Measurement harness: ejecutar el conjunto seleccionado
  (pequeño: ~64KB stream, ~30 fps; grande: ~500MB stream, baja fps) y
  capturar RSS/heap/FD/latencia p95/bytes escaneados. Sin tocar lógica de
  producción; sólo el harness.
- [ ] **T6** Verificar "no thread/proceso por frame/página" — invariante
  asserted en un fitness test que cuenta threads antes y durante una
  lectura sostenida.
- [ ] **T7** Si T3..T6 cierran verde, regenerar artefactos a HEAD, regenerar
  SHA256SUMS, push rama, PR, merge a main, tag `v0.58.0-rc1`, `gh release
  create --prerelease` con zip/sdk/sbom/poms/module + SHA256SUMS + ABI note.
- [ ] **T8** `PK_CONTRACT_HANDOFF` a Fabric con `product_version=0.58.0-rc1`
  y `abi_diff` (cambios publicados en la línea 0.57 → 0.58).

## Criterios de salida

- Todos los gates OBS-PC y los UAT-PK-001..008 / AAT-01..04 PASS sobre los
  mismos bytes.
- ABI publicado en `docs/v2/03-specifications/OUTPUT_READ_ABI_v0.58.md` y
  `EVENT_READ_ABI_v0.58.md` (o similar).
- `v0.58.0-rc1` publicado como **CANDIDATE_PUBLISHED** (NO `PRODUCT_GATE_PASS`,
  NO `PAIR_CERTIFIED` — esos quedan para PK-58B con el veredicto
  independiente del certifier).

## Condiciones explícitas para NO avanzar

- Si un test OBS-PC pasa a rojo, NO relajar; clasificar y reparar.
- Si el measurement harness revela regresión > budget declarado, NO avanzar
  a publicación con la regresión abierta.
- Si T2 decide reusar `ObservationWakeup` y el seam no cumple el contrato
  del SPEC-01, NO forzar el reuso; introducir el interface nuevo.
- Si el releaseGate end-to-end no completa verde, NO publicar `v0.58.0-rc1`.

## Decisiones pendientes

1. T2: ¿`CommitWakeupPort` interface nuevo o reuso de `ObservationWakeup`?
2. ¿Cuántos de los UAT-PK-001..008 ya están cubiertos por OBS-PC-101..107,
   201..207? Mapeo 1:1 o creación de tests que faltan.
3. ¿El measurement harness es por-cambio (gate) o baseline-inmutable
   (LPR-0)? El PERFORMANCE_BUDGETS.md lo marca como PROPOSED.
