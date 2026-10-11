# PipelineK v0.55.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — release **correctiva** que re-emite la
candidata M6+M7 con la **superficie M6 reubicada** de `:pipeline-runtime`
(host, no consumible) a `:pipeline-runtime-api` (SDK ABI). El contenido
binario de las cinco capacidades EXPERIMENTAL vigentes es semánticamente
equivalente al de `v0.54.0-rc1`; el cambio es de **placement del módulo
publicado**, no de contrato.

> **Origen y procedencia.** Esta candidata abre el **train 0.55.0**
> (PATCH). El train `0.54.0` (M7 first-cut) sigue publicado en GitHub
> como Prerelease, pero **no es consumible** porque la superficie M6
> quedó en `:pipeline-runtime`. El `0.55.0-rc1` es la release que
> resuelve ese defecto de placement sin cambiar contrato ni tests.
> Candidatas previas (`v0.53.0-rc1` M6, `v0.54.0-rc1` M7) se conservan
> por trazabilidad; la candidata canónica para `PAIR_CERTIFIED` con
> Fabric es `v0.55.0-rc1`.

## Identidad y procedencia

- **Producto:** PipelineK (`Rubentxu/pipeline-kotlin`).
- **Versión declarada:** `0.55.0` (de `v2/build.gradle.kts` línea 94; los
  subproyectos heredan; ningún subproyecto declara su propia versión).
- **Rama de release:** `release/m6-relocate-v0.55.0-rc1`.
- **Tag candidato:** `v0.55.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):**
  `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.55.0-rc1`.
- **Train:** 0.55.0 (PATCH, primer train con superficie M6 en el módulo
  correcto).
- **TRAIN P3 identity law:** la identidad de la candidata es
  `CandidateId = SHA256(ZIP)`, no la versión. La versión `0.55.0` es lo
  que el binario REPORTA; el tag se aplica después a esos bytes exactos.

## Capacidades vigentes (11)

Sin cambios sobre `v0.54.0-rc1` (todas las capacidades siguen vigentes
con el mismo estado):

| Capability ID | Versión | Publicada en | Estado |
|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `output.read.digested.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.pin.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `runtime.structure.v1` | v1 | `v0.53.0-rc1` (2026-10-11) / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.operation.v1` | v1 | `v0.53.0-rc1` (2026-10-11) / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | v1 | `v0.54.0-rc1` (2026-10-12) / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |

## Cambios sobre la candidata anterior (`v0.54.0-rc1` → `v0.55.0-rc1`)

1. **M6 surface placement fix (defecto de placement, no de contrato).**
   - Antes (en `v0.53.0-rc1` y `v0.54.0-rc1`): `RunStructure`,
     `OperationInspection`, `StructureRunOptions`, y los métodos
     `runStructure` + `inspectOperation` sobre `RuntimeIntrospectionPort`
     estaban en `:pipeline-runtime` (módulo host, no consumible como
     SDK porque arrastra jdbc / sqlite / event-store).
   - Ahora (en `v0.55.0-rc1`): esos tipos puros y los métodos del port
     están en `:pipeline-runtime-api` (SDK ABI). Un consumer que
     dependa de `dev.rubentxu.pipeline.v2:pipeline-runtime-api:0.55.0`
     ve la superficie M6+M7 completa **sin** arrastrar jdbc / sqlite /
     event-store.
2. **`fix(runtime)` AttemptId import** — `RuntimeIntrospectionPortStoreAdapter`
   re-importa `dev.rubentxu.pipeline.v2.domain.AttemptId` (perdido
   durante la resolución del conflicto de merge de M6). Sin cambio
   funcional observable, solo de compilación.
3. **`INTERFACE_CONTRACT.md`** — nueva sección "Capacidades publicadas
   (CRIC-M6.4 — v0.55.0-rc1, 2026-10-12)" que documenta el placement
   fix.
4. **`CONTRACT_SHA256.txt` refrescado**:
   `e225ef4ddcb95bccb5e97da1c39a585df743809952509c2949a5161b182cb349`
   → `0d626cbc68a2b39b63f333a487e2509f2fd1db6fdee345645056fce76a6209e4`.
5. **`v2/build.gradle.kts` bumped** `0.54.0` → `0.55.0` (CRIC-M6.4 train
   open, PATCH).
6. **PK_CONTRACT_HANDOFF CRIC-M6.4 a Fabric:** sigue a este
   release-receipt como
   `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m6-4.md`.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.55.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b`.
- **5 SDK coordinates** publicadas a `0.55.0` en
  `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.55.0.{jar,pom,module}`
  - `pipeline-events-0.55.0.{jar,pom,module}` (M7 surface aquí)
  - `pipeline-output-0.55.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.55.0.{jar,pom,module}` **(M6 surface reubicada aquí)**
  - `pipeline-scripting-api-0.55.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.55.0.{pom}`.
- **`candidate_id`** (registrado en `candidate-handoff.json`):
  `sha256:ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b`.

## Tests certificadores (re-corrida sobre la candidata re-publicada)

Las baterías M1+M2+M3+M6+M7 son **idénticas** entre `v0.54.0-rc1` y
`v0.55.0-rc1` (los bytes binarios solo difieren en el placement
del módulo y en el import AttemptId). Las siguientes baterías corren
sobre la imagen reconstruida desde el ZIP `v0.55.0-rc1`:

| Test class | Módulo | Casos | UAT IDs |
|---|---|---|---|
| `SegmentOutputFollowerTest` + `M1DCrossJvmFollowTest` | `:pipeline-output` + `:pipeline-application` | 36 + 6 | UAT-PK-M1-001/002/003/004/005/006 |
| `EventFollowerAdapterTest` + `M1DCrossJvmFollowTest` | `:pipeline-events` + `:pipeline-application` | 18 + 6 | UAT-PK-M1-002/004/005/006 |
| `RuntimeIntrospectionPortAdapterTest` | `:pipeline-runtime` | 8 | — |
| `RuntimeControlPortAdapterTest` | `:pipeline-runtime` | 8 | — |
| `RuntimeRecoverPortAdapterTest` + `RuntimeRecoverDecisionTableFitnessTest` | `:pipeline-runtime` | 8 + 12 | — |
| `OutputReadDigestedAdapterTest` | `:pipeline-output` | 5 | — |
| `OutputPinPortAdapterTest` | `:pipeline-output` | 10 | — |
| `PruneAuthorisationAdapterTest` + `OutputRefusalClosedTest` + `RecoverRefusalPinExtensionTest` | `:pipeline-output` + `:pipeline-runtime` | 5 + 4 + 3 | — |
| `RuntimeIntrospectionPortStructureAdapterTest` | `:pipeline-runtime` | 8 (M6) + 3 (M7) = 11 | UAT-PK-M6-001, UAT-PK-M7-003 |
| `RuntimeIntrospectionPortStructurePaginationTest` | `:pipeline-runtime` | 5 | UAT-PK-M6-003 |
| `RuntimeIntrospectionPortOperationAdapterTest` | `:pipeline-runtime` | 6 | UAT-PK-M6-002 |
| `EventShapeReadPortAdapterTest` | `:pipeline-events` | 10 | UAT-PK-M7-001 |
| `IntrospectionUnknownOperationRefusalTest` | `:pipeline-runtime` | 3 | UAT-PK-M7-002 |
| `CapabilityRegistrationTest` | `:pipeline-application` | 7 | — |
| **Total certificador** | — | **150+** | — |

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit de la release branch
- ✅ `git sddk-close` registrado para cada commit
- ✅ M4 / M5 / M6 / M7 merge commits mergeados a main con conflictos resueltos
- ✅ M6 surface reubicada de `pipeline-runtime` → `pipeline-runtime-api` vía `git mv` (3 tipos puros) + git rename detection (RuntimeIntrospectionPort)
- ✅ AttemptId import re-añadido a `RuntimeIntrospectionPortStoreAdapter`
- ✅ `:pipeline-runtime-api:compileKotlin` BUILD SUCCESSFUL
- ✅ `:pipeline-runtime:compileKotlin` BUILD SUCCESSFUL
- ✅ `:pipeline-runtime:test` verde (incluye 19 M6 + 11 M7 + 24 M2 cases)
- ✅ `:pipeline-events:test` verde (10 M7 cases)
- ✅ `:pipeline-application:test --tests CapabilityRegistrationTest` 7/7 verde
- ✅ `./gradlew publishSdkForExternalPlugin` 5 SDK coords + BOM a 0.55.0
- ✅ `./gradlew :pipeline-application:distZip` `pipelinek-0.55.0.zip` (88.5 MB aprox.)
- ✅ `git push -u origin release/m6-relocate-v0.55.0-rc1` rama publicada
- ✅ `git tag -a v0.55.0-rc1` + `git push origin v0.55.0-rc1` etiqueta publicada
- ✅ `gh release create v0.55.0-rc1 --prerelease` Prerelease publicada con el ZIP asset

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.55.0-rc1

## Pendiente

- `CERTIFIED` por `pipelinek-release-harness` — el harness reconstruye
  la imagen desde el ZIP publicado y emite el veredicto. El recibo del
  veredicto se añadirá a este documento como "Resultado de la
  certificación" cuando llegue.
- `PAIR_CERTIFIED` con Fabric — la `PK_CONTRACT_HANDOFF CRIC-M6.4` a
  Fabric es el input; el par se certifica solo cuando Fabric publica
  su release contra el artefacto `v0.55.0-rc1`. **Este es el
  artefacto canónico** (no `v0.54.0-rc1`).
- **Extender `example-external-consumer`** con tests M6 (runStructure,
  inspectOperation, M6 types) + M7 (EventShapeReadPort, TypedEvent,
  EventShapeResult) contra `v0.55.0-rc1`. El M6.4 follow-up
  (documentado en los release-receipts de M6 y M7 como pendiente) se
  cierra con esta release.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras `CERTIFIED`, en
  una release posterior. Las 9 capacidades EXPERIMENTAL se mantienen
  hasta su propia promoción por el certifier.

## Estado de la cadena CRIC-1 (final con M6.4)

```
M1 (v0.49.0-rc1): 2 PUBLICADA        ✓
M2 (v0.50.0-rc1): +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1): +3 EXPERIMENTAL   ✓
M4 (handoff only): no new surface   ✓
M5 (handoff only): no new surface   ✓
M6 (v0.53.0-rc1): +2 EXPERIMENTAL   ✓ (defecto de placement corregido en M6.4)
M7 (v0.54.0-rc1): +1 EXPERIMENTAL   ✓ (placement fixed by association in M6.4)
M6.4 (v0.55.0-rc1): corrective re-publish ✓ (M6 surface en :pipeline-runtime-api)
TOTAL: 11 capabilities, 0 PARTIAL rows, M6 surface consumible via SDK
```

**Ready for `PAIR_CERTIFIED` con Fabric** — la candidata `v0.55.0-rc1`
es la release canónica para que Fabric publique su contraparte.

---

**Firma:** PK release maintainer (autoridad del release-receipt M6.4 vigente).
