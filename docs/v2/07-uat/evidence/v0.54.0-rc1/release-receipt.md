# PipelineK v0.54.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y
limpio de `release/cric-m7-v0.54.0-rc1`, material en
`dist/candidates/v0.54.0-rc1/`, ZIP + 5 SDK coordinates + SDK BOM
publicados en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/` a la
versión `0.54.0`. Pendiente: `CERTIFIED` por el release harness tras la
batería de certificación real. El recibo del fallo de certificación,
si lo hay, se añadirá al final de este documento como sección
"Resultado de la certificación" cuando el harness responda.

> **Origen y procedencia.** Esta candidata abre el **train 0.54.0**
> (MINOR). El train anterior (`0.53.0`) está cerrado: su única
> Prerelease publicada es `v0.53.0-rc1` (M6), que permanece en
> `CANDIDATE_PUBLISHED` a la espera del veredicto del certifier. El
> cambio de train refleja la **primera capacidad CRIC-1 sin PARTIAL**:
> la candidata cierra el M6 I.6 PARTIAL con el `EventShapeReadPort`
> tipado, completando el ciclo de garantías que la cadena CRIC-1
> prometía. Las capacidades M1+M2+M3+M6 (10) se mantienen; se añade
> 1 capacidad nueva M7 (`runtime.eventshape.v1`).

## Identidad y procedencia

- **Producto:** PipelineK (`Rubentxu/pipeline-kotlin`).
- **Versión declarada:** `0.54.0` (de `v2/build.gradle.kts` línea 94; los
  subproyectos heredan; ningún subproyecto declara su propia versión).
- **Rama de release:** `release/cric-m7-v0.54.0-rc1`.
- **Tag candidato:** `v0.54.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):**
  `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.54.0-rc1`.
- **Train:** 0.54.0 (MINOR, primer train sin PARTIAL).
- **TRAIN P3 identity law:** la identidad de la candidata es
  `CandidateId = SHA256(ZIP)`, no la versión. La versión `0.54.0` es lo
  que el binario REPORTA; el tag se aplica después a esos bytes exactos.

## Capacidades vigentes (11)

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
| `runtime.eventshape.v1` | v1 | `v0.54.0-rc1` (2026-10-12) | **EXPERIMENTAL** |

## Cambios sobre la candidata anterior (v0.53.0-rc1 → v0.54.0-rc1)

1. **M6 I.6 PARTIAL closure** — `EventShapeReadPort` (puerto fun-interface
   SAM en `:pipeline-events`) devuelve los eventos tipados
   (`StageStarted` / `StageFinished` / `StageSkipped` / `StepStarted` /
   `StepFinished` — 5 casos, sealed ADT). El
   `RuntimeIntrospectionPort.runStructure` lo compone para reemplazar
   la agrupación sintética M6 con agrupación dirigida por eventos.
   El fallback sintético se preserva para runs sin eventos tipados.
2. **`IntrospectionRefusal.UnknownOperation(operationId, attempt)`** —
   caso aditivo (B.2 hardening). El `inspectOperation` adapter lo
   usa cuando el journal no tiene fila; el M6 first-cut
   `StorageError(cause)` se reemplaza.
3. **`DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L`** — añadido al
   `RuntimeIntrospectionPort` companion (B.1 hardening).
4. **Tipos cerrados nuevos** en `:pipeline-events`:
   - `EventShapeReadPort` (fun interface SAM)
   - `EventShapeResult` (sealed: `Events` / `Empty` / `Refused`)
   - `EventShapeRefusal` (sealed: `UnknownRun` / `NoEventStore` / `StorageError`)
   - `EventShapeOptions` (data class con companion `Default` + `MAX_EVENT_SHAPE_PAGE_SIZE = 4096`)
   - `EventShapeCursor` (data class con `eventOrdinal`)
   - `TypedEvent` (sealed interface con 5 casos pineados por test 9)
5. **Nueva batería de tests** (M7):
   - `EventShapeReadPortAdapterTest` — 10 casos (en `:pipeline-events`)
   - `IntrospectionUnknownOperationRefusalTest` — 3 casos (en `:pipeline-runtime`)
   - `RuntimeIntrospectionPortStructureAdapterTest` — 3 casos M7 nuevos
     (event-driven, most-recent-wins, synthetic fallback), total 11
6. **`CapabilityRegistrationTest` extendido** a 7 casos (era 6);
   pinea byte-a-byte SHA, las 11 capacidades, las 15 clases de test
   certificadoras, y la nueva tabla "Capacidades publicadas (CRIC-M7)".
7. **`INTERFACE_CONTRACT.md` actualizado**:
   - §6 extendido: bloque `>` "CRIC-M7 (v0.54.0-rc1, 2026-10-12)"
   - Tabla "Capacidades publicadas (CRIC-M7)" al final
   - Los 3 hardening items B.1 / B.2 / B.3 cerrados explícitamente
8. **`CONTRACT_SHA256.txt` refrescado**:
   `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3`
   → `e225ef4ddcb95bccb5e97da1c39a585df743809952509c2949a5161b182cb349`.
9. **`v2/build.gradle.kts` bumped** `0.53.0` → `0.54.0` (CRIC-M7 train open).
10. **PK_CONTRACT_HANDOFF CRIC-M7 a Fabric:** sigue a este release-receipt
    como `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m7.md`.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.54.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.
- **5 SDK coordinates** publicadas a `0.54.0` en
  `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.54.0.{jar,pom,module}`
  - `pipeline-events-0.54.0.{jar,pom,module}` (M7 surface aquí)
  - `pipeline-output-0.54.0.{jar,pom,module}`
  - `pipeline-runtime-0.54.0.{jar,pom,module}` (M6 surface)
  - `pipeline-scripting-api-0.54.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.54.0.{pom}`.
- **`candidate_id`** (registrado en `candidate-handoff.json`):
  `sha256:a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.

## Tests certificadores (M7)

| Test class | Módulo | Casos | UAT IDs |
|---|---|---|---|
| `EventShapeReadPortAdapterTest` | `:pipeline-events` | 10 | UAT-PK-M7-001 |
| `IntrospectionUnknownOperationRefusalTest` | `:pipeline-runtime` | 3 | UAT-PK-M7-002 |
| `RuntimeIntrospectionPortStructureAdapterTest` (M7 row) | `:pipeline-runtime` | 3 (M7) / 11 (total) | UAT-PK-M7-003 |
| `CapabilityRegistrationTest` (M7 row added) | `:pipeline-application` | 7 (was 6) | — |
| **Subtotal M7** | — | **16** | — |

Tests M1 (36 casos cross-store + 6 cross-JVM), M2 (24 casos adapter +
12 fitness), M3 (27 casos), M6 (19 casos) — todos verdes sin
regresiones.

## Hardening cerrado en M7

- **B.1** `DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L` en
  `RuntimeIntrospectionPort` companion.
- **B.2** Caso `IntrospectionRefusal.UnknownOperation(operationId, attempt)`
  aditivo; pineado por `IntrospectionUnknownOperationRefusalTest`.
- **B.3** 5 casos del sealed `TypedEvent` pineados por
  `EventShapeReadPortAdapterTest` test 9.

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit M7
- ✅ `git sddk-close` registrado para cada commit M7
- ✅ `CapabilityRegistrationTest` 7/7 verde (pinea SHA, 11 capacidades, 15 test classes, tabla CRIC-M7)
- ✅ `:pipeline-runtime:test` 19 M6 + 11 M7 + 24 M2 = 54+ casos verdes
- ✅ `:pipeline-events:test` 10 M7 = 10 casos verdes
- ✅ `:pipeline-application:test --tests CapabilityRegistrationTest` 7/7 verde
- ✅ `./gradlew publishSdkForExternalPlugin` 5 SDK coords + BOM a 0.54.0
- ✅ `./gradlew :pipeline-application:distZip` `pipelinek-0.54.0.zip` (88.4 MB)

## Pendiente

- `CERTIFIED` por `pipelinek-release-harness` — el harness reconstruye
  la imagen desde el ZIP publicado y emite el veredicto. El recibo
  del veredicto se añadirá a este documento como "Resultado de la
  certificación" cuando llegue.
- `PAIR_CERTIFIED` con Fabric — la `PK_CONTRACT_HANDOFF CRIC-M7` a
  Fabric es el input; el par se certifica solo cuando Fabric publica
  su release contra el artefacto `v0.54.0-rc1`.
- **M6.4 follow-up (consumer):** el `example-external-consumer` en el
  worktree principal necesita ser extendido con el M7 surface
  (relaxing the fun-interface assertion, adding `EventShapeReadPort`
  capability check). Es un follow-up que ocurre después de que
  `v0.54.0-rc1` se publique; no bloquea la candidata M7.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras CERTIFIED, en un
  release posterior.

## Estado de la cadena CRIC-1

```
M1 (v0.49.0-rc1): 2 PUBLICADA        (output.follow, events.follow)
M2 (v0.50.0-rc1): +3 EXPERIMENTAL   (runtime.inspect, cancel, recover)
M3 (v0.51.0-rc1): +3 EXPERIMENTAL   (output.read.digested, pin, refusal.retention)
M4 (handoff only): no new surface
M5 (handoff only): no new surface
M6 (v0.53.0-rc1): +2 EXPERIMENTAL   (runtime.structure, operation) [I.6 PARTIAL]
M7 (v0.54.0-rc1): +1 EXPERIMENTAL   (runtime.eventshape) — PARTIAL CLOSED
```

**TOTAL:** 11 capabilities, 0 PARTIAL rows, ready for `PAIR_CERTIFIED`.

---

**Firma:** PK release maintainer (autoridad del release-receipt M7 vigente).
