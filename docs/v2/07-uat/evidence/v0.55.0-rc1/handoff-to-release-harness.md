# Handoff a `pipelinek-release-harness` — v0.55.0-rc1

**Fecha:** 2026-10-12
**Candidata:** v0.55.0-rc1 (train `0.55.0`, sequence 1) — **release correctiva** del M6.4 follow-up
**Tag:** `v0.55.0-rc1` (anotado, peel = HEAD al construir)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace el harness, no el repositorio de producto).

## Por qué esta candidata

El M6 first-cut (`v0.53.0-rc1`) y M7 first-cut (`v0.54.0-rc1`) se publicaron
con la **superficie M6 en `:pipeline-runtime`** (módulo host, no consumible
como SDK). Esa placement defectiva ocurrió porque el branch M6 se creó
**antes** del M2 module split (`2a97653a`) y puso los tipos M6 en el único
módulo que existía entonces.

**Esta candidata `v0.55.0-rc1` corrige el placement** sin cambiar contrato:
los tipos puros M6 (`RunStructure`, `OperationInspection`,
`StructureRunOptions`, y los métodos `runStructure` + `inspectOperation`
sobre `RuntimeIntrospectionPort`) se mueven a `:pipeline-runtime-api`. Un
consumer que dependa de `dev.rubentxu.pipeline.v2:pipeline-runtime-api:0.55.0`
ve la superficie M6+M7 completa sin arrastrar jdbc / sqlite / event-store.

## Capacidades publicadas por esta candidata

Las **once** capacidades vigentes quedan declaradas en
`INTERFACE_CONTRACT.md` §6: M1 (2 PUBLICADA) + M2 (3 EXPERIMENTAL) + M3
(3 EXPERIMENTAL) + M6 (2 EXPERIMENTAL re-affirmed) + M7 (1 EXPERIMENTAL
re-affirmed). **No se añade ninguna capacidad nueva** en esta candidata;
el cambio es de placement del módulo publicado.

| Capability ID | Publicada en | Estado | Test certificador |
|---|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** | `SegmentOutputFollowerTest` (18) + `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003) |
| `events.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** | `EventFollowerAdapterTest` (18) + `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006) |
| `runtime.inspect.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** | `RuntimeIntrospectionPortAdapterTest` (8) |
| `runtime.cancel.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** | `RuntimeControlPortAdapterTest` (8) |
| `runtime.recover.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** | `RuntimeRecoverPortAdapterTest` (8) + `RuntimeRecoverDecisionTableFitnessTest` (12) |
| `output.read.digested.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** | `OutputReadDigestedAdapterTest` (5) |
| `output.pin.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** | `OutputPinPortAdapterTest` (10) |
| `output.refusal.retention.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** | `PruneAuthorisationAdapterTest` (5) + `OutputRefusalClosedTest` (4) + `RecoverRefusalPinExtensionTest` (3) |
| `runtime.structure.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** | `RuntimeIntrospectionPortStructureAdapterTest` (11) + `RuntimeIntrospectionPortStructurePaginationTest` (5) |
| `runtime.operation.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** | `RuntimeIntrospectionPortOperationAdapterTest` (6) |
| `runtime.eventshape.v1` | `v0.54.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** | `EventShapeReadPortAdapterTest` (10) + `IntrospectionUnknownOperationRefusalTest` (3) |

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.55.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b`.
- **5 SDK coordinates** a `0.55.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.55.0.{jar,pom,module}`
  - `pipeline-events-0.55.0.{jar,pom,module}` (M7 surface aquí)
  - `pipeline-output-0.55.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.55.0.{jar,pom,module}` **(M6 surface reubicada aquí)**
  - `pipeline-scripting-api-0.55.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.55.0.{pom}`.
- **`candidate_id`:** `sha256:ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b`.

## Cambios sobre la candidata anterior (`v0.54.0-rc1` → `v0.55.0-rc1`)

1. **M6 surface placement fix** (único cambio de contrato): 3 tipos puros
   (`RunStructure`, `OperationInspection`, `StructureRunOptions`) +
   métodos `runStructure` + `inspectOperation` sobre
   `RuntimeIntrospectionPort` se mueven de `:pipeline-runtime` a
   `:pipeline-runtime-api`.
2. **`fix(runtime)` AttemptId import** en
   `RuntimeIntrospectionPortStoreAdapter` (perdido en la resolución del
   conflicto de merge de M6).
3. **Versión** `0.54.0` → `0.55.0` (PATCH).
4. **`CONTRACT_SHA256.txt`** refrescado:
   `e225ef4ddc...` → `0d626cbc68...`.
5. **PK_CONTRACT_HANDOFF CRIC-M6.4 a Fabric:** sigue a este handoff
   como
   `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m6-4.md`.

## Batería de certificación (re-corrida)

Las baterías M1+M2+M3+M6+M7 son **idénticas** entre `v0.54.0-rc1` y
`v0.55.0-rc1`. El harness re-corre las mismas 150+ casos certificadores
sobre la imagen reconstruida desde el ZIP `v0.55.0-rc1`. No hay
**ningún test nuevo** (no es un release de capacidad; es un release de
placement). Las verificaciones verdes que aplican son:

- M1 (36+6 casos cross-store + cross-JVM)
- M2 (24 casos adapter + 12 fitness)
- M3 (27 casos)
- M6 (19 casos)
- M7 (16 casos)
- `CapabilityRegistrationTest` 7/7

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El harness NO
   reutiliza una imagen previa identificada solo por nombre. El
   `candidate_id` (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería de certificación M1+M2+M3+M6+M7** (los 150+
   casos arriba) sobre la imagen reconstruida.
3. **Verificar el placement fix:** abrir
   `pipeline-runtime-api-0.55.0.jar` y confirmar que contiene las
   clases:
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RunStructure`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspection`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.StructureCursor`
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort`
     (con los 3 métodos: `inspect`, `runStructure`, `inspectOperation`)
4. **Verificar el `CONTRACT_SHA256.txt`**:
   `0d626cbc68a2b39b63f333a487e2509f2fd1db6fdee345645056fce76a6209e4`
   debe coincidir con el SHA-256 del `INTERFACE_CONTRACT.md` de la
   imagen.
5. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED, las
   9 capacidades EXPERIMENTAL pueden promocionarse a PUBLICADA en una
   release posterior (la promoción es un cambio de tabla, no un cambio
   de binario; se hace en el siguiente release-receipt).
6. **Si es BLOCKED:** añadir el detalle al final del
   `release-receipt.md` como "Resultado de la certificación" y emitir
   un nuevo handoff con la candidata correctiva.

## Estado final de la cadena CRIC-1 (con M6.4)

```
M1..M3: 8 capacidades (2 PUBLICADA + 6 EXPERIMENTAL)
M4..M5: compat handoff only
M6: 2 EXPERIMENTAL (placement defect → fixed in M6.4)
M7: 1 EXPERIMENTAL (placement fixed by association in M6.4)
M6.4: corrective re-publish v0.55.0-rc1 (placement fix, no new surface)
TOTAL: 11 capacidades, 0 PARTIAL rows, M6 surface consumible via SDK
```

**Ready for `PAIR_CERTIFIED` con Fabric** — la candidata `v0.55.0-rc1`
es la release canónica. Tras la certificación por el harness, el
`PAIR_CERTIFIED` con Fabric (release M6.4 contra el artefacto
`v0.55.0-rc1`) cierra el ciclo.

## Pendiente

- ✅ **Tag + Prerelease en GitHub:** Prerelease `v0.55.0-rc1`
  publicada en `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.55.0-rc1`.
  ZIP `pipelinek-0.55.0.zip` subido como asset con SHA-256
  _(se documenta al publicar)_.
- **Veredicto del harness:** pendiente. Se añadirá al
  `release-receipt.md` cuando llegue.
- **Extender `example-external-consumer`** con tests M6+M7 contra
  `v0.55.0-rc1` — M6.4 follow-up en el consumer, ocurre tras la
  publicación.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras CERTIFIED, en un
  release posterior.
- **`PAIR_CERTIFIED` con Fabric:** tras el veredicto del harness, el
  Integrator acepta el par contra el artefacto `v0.55.0-rc1`.

---

**Firma:** PK release maintainer (autoridad del release-receipt M6.4 vigente).
