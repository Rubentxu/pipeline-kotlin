# Handoff a `pipelinek-release-harness` — v0.53.0-rc1

**Fecha:** 2026-10-11
**Candidata:** v0.53.0-rc1 (train `0.53.0`, sequence 1) — **abre el train 0.53.0**
**Tag:** `v0.53.0-rc1` (anotado, peel = HEAD al construir)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace el harness, no el repositorio de producto).

## Capacidades publicadas por esta candidata

Las **dos** nuevas capacidades negociables del train 0.53.0 quedan declaradas `EXPERIMENTAL` en `INTERFACE_CONTRACT.md` §6 desde esta candidata, junto con la **carry-forward** de las ocho capacidades M1+M2+M3 ya existentes (M1 PUBLICADA desde `v0.49.0-rc1`; M2 EXPERIMENTAL desde `v0.50.0-rc1`; M3 EXPERIMENTAL desde `v0.51.0-rc1`). Los consumidores deben actualizar su rama de negociación para reconocer las dos capacidades M6 (`runtime.structure.v1`, `runtime.operation.v1`) como ofrecidas en `v0.53.0-rc1+`.

| Capability ID | Publicada en | Estado | Test certificador |
|---|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** | `SegmentOutputFollowerTest` (18 casos) + `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003) |
| `events.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** | `EventFollowerAdapterTest` (18 casos) + `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006) |
| `runtime.inspect.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeIntrospectionPortAdapterTest` (8 casos) |
| `runtime.cancel.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeControlPortAdapterTest` (8 casos) |
| `runtime.recover.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeRecoverPortAdapterTest` (8 casos) + `RuntimeRecoverDecisionTableFitnessTest` (12 casos) |
| `output.read.digested.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `OutputReadDigestedAdapterTest` (5 casos) |
| `output.pin.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `OutputPinPortAdapterTest` (10 casos) |
| `output.refusal.retention.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `PruneAuthorisationAdapterTest` (5 casos) + `OutputRefusalClosedTest` (4 casos) + `RecoverRefusalPinExtensionTest` (3 casos) |
| `runtime.structure.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `RuntimeIntrospectionPortStructureAdapterTest` (8 casos) + `RuntimeIntrospectionPortStructurePaginationTest` (5 casos) |
| `runtime.operation.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `RuntimeIntrospectionPortOperationAdapterTest` (6 casos) |

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.53.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.
- **5 SDK coordinates** a `0.53.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.53.0.{jar,pom,module}`
  - `pipeline-events-0.53.0.{jar,pom,module}`
  - `pipeline-output-0.53.0.{jar,pom,module}`
  - `pipeline-runtime-0.53.0.{jar,pom,module}` (M6 surface aquí)
  - `pipeline-scripting-api-0.53.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.53.0.{pom}`.
- **`candidate_id`:** `sha256:f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.

## Gaps M6 que esta release cierra

| Audit gap | Solución PK |
|---|---|
| F.1 — No public indexed enumeration of stages / steps / operations | `RuntimeIntrospectionPort.runStructure(runId, options): RunStructureResult` |
| F.2 — No public per-operation terminality typed query | `RuntimeIntrospectionPort.inspectOperation(operationId, attempt): OperationInspectionResult` |
| F.3 — Bounded scan + resume cursor | `StructureRunOptions { pageSize, cursor, includeTerminalStages }` + `StructureCursor` |
| F.4 (negative scope) — No Governor / fairness scheduler / query engine in PK | PRESERVED (composition table en design §C) |
| F.5 (negative scope) — No new lease / scheduler / transport in PK | PRESERVED (composition table en design §C) |

**PARTIAL closure notes (audit I.6):** la implementación M6 first-cut usa agrupación sintética single-stage / single-step (el envelope `PipelineEventEnvelope` descarta los campos específicos de tipo de los eventos tipados; la agrupación dirigida por eventos requiere un follow-up M-block con un `EventShapeReadPort` tipado). Los tests contractuales pinean explícitamente la agrupación sintética para que un M-block futuro pueda actualizar los tests sin ambigüedad.

## Batería de certificación (M6)

- `RuntimeIntrospectionPortStructureAdapterTest` — 8 casos UAT-PK-M6-001 (verdes)
- `RuntimeIntrospectionPortOperationAdapterTest` — 6 casos UAT-PK-M6-002 (verdes)
- `RuntimeIntrospectionPortStructurePaginationTest` — 5 casos UAT-PK-M6-003 (verdes)
- `CapabilityRegistrationTest` (extendido) — 6 casos totales (verdes), pine SHA + 10 capacidades + 13 test classes + tabla CRIC-M6

**Subtotal M6:** 25 casos certificadores nuevos. Baterías M1+M2+M3 (87+ casos) sin regresiones.

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El harness NO
   reutiliza una imagen previa identificada solo por nombre. El
   `candidate_id` (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería de certificación M6** (los 25 casos
   certificadores arriba) sobre la imagen reconstruida.
3. **Verificar el `CONTRACT_SHA256.txt`**:
   `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3`
   debe coincidir con el SHA-256 del `INTERFACE_CONTRACT.md` de la
   imagen.
4. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED, las
   dos capacidades M6 pueden promocionarse a PUBLICADA en una release
   posterior (la promoción es un cambio de tabla, no un cambio de
   binario; se hace en el siguiente release-receipt).
5. **Si es BLOCKED:** añadir el detalle al final del
   `release-receipt.md` como "Resultado de la certificación" y emitir un
   nuevo handoff con la candidata correctiva.

## Pendiente

- **Tag + Prerelease en GitHub:** la Prerelease `v0.53.0-rc1` se
  publica desde la línea de comandos después de este handoff. El ZIP
  `pipelinek-0.53.0-rc1` se sube como asset; el SHA-256 se anota en la
  descripción de la Prerelease.
- **Veredicto del harness:** pendiente. Se añadirá al
  `release-receipt.md` cuando llegue.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras CERTIFIED, en un
  release posterior.
- **Follow-up M-block** para cerrar el I.6 PARTIAL con agrupación
  dirigida por eventos (typed `EventShapeReadPort`).

---

**Firma:** PK release maintainer (autoridad del release-receipt M6 vigente).
