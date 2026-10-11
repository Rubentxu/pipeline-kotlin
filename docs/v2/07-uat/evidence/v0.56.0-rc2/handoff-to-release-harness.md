# Handoff a `pipelinek-release-harness` — v0.56.0-rc2

**Fecha:** 2026-10-12
**Candidata:** v0.56.0-rc2 (train `0.56.0`, sequence 2) — **release correctiva** sobre `v0.56.0-rc1` que cierra el PK-1 completo.
**Tag:** `v0.56.0-rc2` (anotado, peel = HEAD al construir).
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace el harness, no el repositorio de producto).

## Por qué `v0.56.0-rc2` y no solo `v0.56.0-rc1`

`v0.56.0-rc1` (publicada antes) cubre los tipos fundación y los dos
subcomandos machine-verifiable, pero NO propaga la correlación al
durable state ni la verifica end-to-end. `v0.56.0-rc2` añade los
commits T9-T11 + T12-T13 sobre la misma release branch, cerrando el
PK-1 completo. **Sin reescribir la tag `v0.56.0-rc1`** (regla del
roadmap #6); la candidata canónica para `CERTIFIED` es
`v0.56.0-rc2`.

## Cambios sobre `v0.56.0-rc1`

1. **`RunIdDirectory`** persiste `correlationId` además de
   `runId` (formato de dos líneas).
2. **`--correlation-id <uuid>` en el CLI.** El parser acepta el
   flag, rechaza blanco, propaga al `CliFlags.correlationId`.
3. **`selectDurableRun` / `startFreshRun`** aceptan
   `CorrelationId`; la `DurableRunSelection` lleva el
   `correlationId` resultante.
4. **`emitCorrelationAndReceipt` helper en `Main.kt`** emite:
   - `RunCorrelated` event en el `EventSink` (BEFORE
     `RunStarted`).
   - `LaunchReceipt` JSON line en stdout (schema
     `pipelinek.launch/1`).
5. **In-memory + durable branches** ambos llaman al helper.
6. **Consumer extension** (`examples/example-external-consumer`):
   `Pk1ContractSurfaceTest` con 5 casos end-to-end.

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **5 SDK coordinates** a `0.56.0` (idénticas a `v0.56.0-rc1`).
- **`candidate_id`:** `sha256:0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.

## Batería de certificación (PK-1 completo)

- `Pk1ContractTypesTest` (8 casos in-monorepo) — verde.
- `Pk1ContractSurfaceTest` (5 casos, consumer extension) — **5/5
  verde** contra `-PsdkVersion=0.56.0` con `PIPELINEK_BIN`.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos.

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El
   `candidate_id` (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería completa** sobre la imagen reconstruida
   (los 145+ casos arriba).
3. **Verificar end-to-end el `--correlation-id`** ejecutando
   `pipeline run --correlation-id <uuid> --db <path> <script>` y
   parseando la línea `pipelinek.launch/1` de stdout. La línea
   debe llevar `"correlationId":"<uuid>"` exactamente.
4. **Verificar el `CONTRACT_SHA256.txt`** (sin cambios desde
   `v0.56.0-rc1`).
5. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED,
   las 9 capabilities EXPERIMENTAL pueden promocionarse a
   PUBLICADA en un release posterior.

## Estado del bloque PK-1 (final)

```
T1-T18: 18/18 tareas completadas (T5/T6/T10/T13 con implementación inline equivalente)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces
```

**PK-1 fully complete; ready for `CERTIFIED` + `PAIR_CERTIFIED` with Fabric.**

## Pendiente

- ✅ **Tag + Prerelease en GitHub:** `v0.56.0-rc2` publicada en
  https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc2.
  ZIP `pipelinek-0.56.0.zip` (88.4 MB) subido como asset con
  SHA-256 `0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **Veredicto del harness:** pendiente. Se añadirá al
  `release-receipt.md` cuando llegue.
- **`v0.56.0-rc1`** queda en GitHub como Prerelease histórica
  (T1-T4, T7-T8, T11, T14-T18); la candidata canónica para
  `PAIR_CERTIFIED` es **`v0.56.0-rc2`**.

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 final).
