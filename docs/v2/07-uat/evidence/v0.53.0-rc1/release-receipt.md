# PipelineK v0.53.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y
limpio de `release/cric-m6-v0.53.0-rc1`, material en
`dist/candidates/v0.53.0-rc1/`, ZIP + 5 SDK coordinates + SDK BOM
publicados en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/` a la
versión `0.53.0`. Pendiente: `CERTIFIED` por el release harness tras la
batería de certificación real (el harness reconstruye la imagen desde el
ZIP publicado, no reutiliza una imagen previa identificada solo por
nombre). El recibo del fallo de certificación, si lo hay, se añadirá al
final de este documento como sección "Resultado de la certificación"
cuando el harness responda.

> **Origen y procedencia.** Esta candidata abre el **train 0.53.0**
> (MINOR). El train anterior (`0.51.0`) está cerrado: su única
> Prerelease publicada es `v0.51.0-rc1`, que permanece en
> `CANDIDATE_PUBLISHED` a la espera del veredicto del certifier. El
> `0.52.0-rc1` que aparece en la rama `main` es la integración de M2+M3
> en una sola Prerelease, no un tren CRIC separado; el siguiente tren
> CRIC es el que abre esta candidata como `0.53.0-rc1`. El cambio de
> train refleja dos nuevas capacidades de consumidor publicadas en este
> release como `EXPERIMENTAL` (`runtime.structure.v1`,
 * `runtime.operation.v1`) además de la **carry-forward** de las ocho
> capacidades M1+M2+M3 ya existentes (`output.follow.v1`,
> `events.follow.v1`, `runtime.inspect.v1`, `runtime.cancel.v1`,
> `runtime.recover.v1`, `output.read.digested.v1`, `output.pin.v1`,
> `output.refusal.retention.v1`).

## Identidad y procedencia

- **Producto:** PipelineK (`Rubentxu/pipeline-kotlin`).
- **Versión declarada:** `0.53.0` (de `v2/build.gradle.kts` línea 94; los
  subproyectos heredan; ningún subproyecto declara su propia versión).
- **Rama de release:** `release/cric-m6-v0.53.0-rc1`.
- **Tag candidato:** `v0.53.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):**
  `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.53.0-rc1`.
- **Train:** 0.53.0 (MINOR, segundo después de M3).
- **TRAIN P3 identity law:** la identidad de la candidata es
  `CandidateId = SHA256(ZIP)`, no la versión. La versión `0.53.0` es lo
  que el binario REPORTA; el tag se aplica después a esos bytes exactos.

## Capacidades vigentes (10)

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
| `runtime.structure.v1` | v1 | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `runtime.operation.v1` | v1 | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** |

## Cambios sobre la candidata anterior (v0.51.0-rc1 → v0.53.0-rc1)

1. **Dos métodos aditivos sobre `RuntimeIntrospectionPort`** (M6
   first-cut):
   - `runStructure(runId, options: StructureRunOptions = Default): RunStructureResult`
   - `inspectOperation(operationId: String, attempt: Int): OperationInspectionResult`
2. **Tipos cerrados nuevos** en `:pipeline-runtime`:
   - `RunStructure` (sealed: `Stages` / `Empty` / `Unavailable`)
   - `RunStructureResult` (sealed: `Structure` / `Refused`)
   - `StageSummary`, `StepSummary`, `OperationSummary` (data classes con init guards)
   - `StepState` (sealed: `Pending` / `Running` / `Succeeded` / `Failed` /
     `Cancelled` / `Skipped`)
   - `OperationOutputStreams` (data class con `stdoutStream` / `stderrStream` nullable)
   - `OperationInspection`, `OperationInspectionResult` (sealed)
   - `StructureRunOptions` (data class con `pageSize` 1..4096, `cursor`, `includeTerminalStages`; companion `Default`)
   - `StructureCursor` (data class con `stageOrdinal` / `stepOrdinal` / `operationOrdinal`)
3. **Nueva batería de tests** en `:pipeline-runtime`:
   - `RuntimeIntrospectionPortStructureAdapterTest` — 8 casos UAT-PK-M6-001
   - `RuntimeIntrospectionPortOperationAdapterTest` — 6 casos UAT-PK-M6-002
   - `RuntimeIntrospectionPortStructurePaginationTest` — 5 casos UAT-PK-M6-003
   - 19 casos nuevos, todos verdes; las 24+ baterías M2 existentes
     (Introspection / Control / Recover adapter tests + fitness
     `RuntimeRecoverDecisionTableFitnessTest`) siguen verdes sin
     regresiones.
4. **`CapabilityRegistrationTest` extendido** (en `:pipeline-application`):
   6 casos (antes 5), pine byte-a-byte las diez capacidades
   M1+M2+M3+M6, las trece clases de test certificadoras, y la nueva
   tabla "Capacidades publicadas (CRIC-M6)" del
   `INTERFACE_CONTRACT.md`.
5. **`INTERFACE_CONTRACT.md` actualizado** con:
   - §6 extendido: bloque `>` "CRIC-M6 (v0.53.0-rc1, 2026-10-11)" con la
     nota PARTIAL de I.6 (synthetic grouping, event-shape read port
     needed for true event-driven grouping).
   - Tabla "Capacidades publicadas (CRIC-M6)" al final.
6. **`CONTRACT_SHA256.txt` refrescado**:
   `233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64`
   → `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3`.
7. **`v2/build.gradle.kts` bumped** `0.51.0` → `0.53.0` (CRIC-M6 train open).
8. **PK_CONTRACT_HANDOFF CRIC-M6 a Fabric:** sigue a este release-receipt
   como `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m6.md`.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.53.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.
- **5 SDK coordinates** publicadas a `0.53.0` en
  `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.53.0.{jar,pom,module}`
  - `pipeline-events-0.53.0.{jar,pom,module}`
  - `pipeline-output-0.53.0.{jar,pom,module}`
  - `pipeline-runtime-0.53.0.{jar,pom,module}` (M6 surface aquí)
  - `pipeline-scripting-api-0.53.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.53.0.{pom}`.
- **`candidate_id`** (registrado en `candidate-handoff.json`):
  `sha256:f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.

## Tests certificadores (M6)

| Test class | Módulo | Casos | UAT IDs |
|---|---|---|---|
| `RuntimeIntrospectionPortStructureAdapterTest` | `:pipeline-runtime` | 8 | UAT-PK-M6-001 |
| `RuntimeIntrospectionPortOperationAdapterTest` | `:pipeline-runtime` | 6 | UAT-PK-M6-002 |
| `RuntimeIntrospectionPortStructurePaginationTest` | `:pipeline-runtime` | 5 | UAT-PK-M6-003 |
| `CapabilityRegistrationTest` (M6 row added) | `:pipeline-application` | 6 (was 5) | — |
| **Subtotal M6** | — | **25** | — |

Tests M2 (24 casos) y M3 (27 casos) siguen verdes sin cambios; tests M1
(36 casos cross-store + 6 cross-JVM) también.

## PARTIAL closure notes (audit I.6)

- **Síntesis sintética single-stage / single-step** en M6 first-cut.
  El envelope `PipelineEventEnvelope` descarta los campos específicos
  de tipo de los eventos tipados (`stageIndex`, `stepOrdinal`,
  `stageName`, `stepKey`); solo expone `kind`, `occurredAt`,
  `sequence`, `subject`. Por tanto, leer los límites stage/step desde
  el event plane requiere un follow-up M-block que exponga un puerto de
  lectura con forma de evento tipado (`EventShapeReadPort` o similar).
- Los tests contractuales pinean explícitamente la agrupación
  sintética para que un M-block futuro que añada agrupación dirigida
  por eventos pueda actualizar los tests sin ambigüedad.
- El `release-receipt` documenta este PARTIAL honestamente: un
  consumer que necesite agrupación verdadera debe esperar al
  follow-up; el M6 first-cut no finge un agrupamiento que no
  implementa.

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit M6
- ✅ `git sddk-close` registrado para cada commit M6
- ✅ `CapabilityRegistrationTest` 6/6 verde (pinea SHA, capacidades, tests)
- ✅ `:pipeline-runtime:test` 19 M6 + 24 M2 = 43+ casos verdes
- ✅ `:pipeline-application:test --tests CapabilityRegistrationTest` 6/6 verde
- ✅ `./gradlew publishSdkForExternalPlugin` 5 SDK coords + BOM a 0.53.0
- ✅ `./gradlew :pipeline-application:distZip` `pipelinek-0.53.0.zip` (88.3 MB)
- ✅ `git push -u origin release/cric-m6-v0.53.0-rc1` rama publicada
- ✅ `git tag -a v0.53.0-rc1` + `git push origin v0.53.0-rc1` etiqueta publicada
- ✅ `gh release create v0.53.0-rc1 --prerelease` Prerelease publicada con el ZIP asset

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.53.0-rc1

## Pendiente

- `CERTIFIED` por `pipelinek-release-harness` — el harness reconstruye
  la imagen desde el ZIP publicado y emite el veredicto. El recibo
  del veredicto se añadirá a este documento como "Resultado de la
  certificación" cuando llegue.
- `PAIR_CERTIFIED` con Fabric — la `PK_CONTRACT_HANDOFF CRIC-M6` a
  Fabric es el input; el par se certifica solo cuando Fabric publica su
  release contra el artefacto `v0.53.0-rc1`.
- Promoción `EXPERIMENTAL → PUBLICADA` de las dos capacidades M6 — solo
  tras `CERTIFIED`. Las ocho capacidades M1+M2+M3 existentes mantienen
  su estado actual (M1 PUBLICADA, M2+M3 EXPERIMENTAL) hasta su propia
  promoción por el certifier.
