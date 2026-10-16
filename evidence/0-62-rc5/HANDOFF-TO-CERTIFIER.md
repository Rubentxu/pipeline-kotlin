# v0.62.0-rc5 — Handoff al certifier (pipelinek-release-harness)

**Fecha**: 2026-10-16
**Estado**: BUILD PARTIAL — handoff a certifier con gap documentado
**Camino de publicación**: `python3 scripts/publish_rc_release.py` (per AGENTS.md §8 camino único)
**Origen**: BQ4 v2 (cierre de auditoría externa 2026-10-16) + corrección de build

## Identidad del candidato

| Campo | Valor |
|---|---|
| `product_version` | `0.62.0` |
| `release_train` | `0.62.0` |
| `candidate_sequence` | `5` |
| `candidate_id` | `sha256:b14794ec7d5bd1aabd45074d78fcf846a11ce0af6a26ad14d5d927f18b07506c` |
| `source_commit` | `f32bd4395709b09790bc4a074fb88416e01b9ac6` |
| `git_tag` | `v0.62.0-rc5` |
| `archive_root` | `pipelinek-0.62.0` |
| `implementation_version` | `0.62.0` |

## SHA-256 de los artefactos

| Asset | SHA-256 |
|---|---|
| `pipelinek-0.62.0.zip` | `b14794ec7d5bd1aabd45074d78fcf846a11ce0af6a26ad14d5d927f18b07506c` |
| `pipelinek-0.62.0.sbom.json` | `0e9c0f4e0f8c5910a0e460eb4e7e9072b35636733c02db5125a677395bb0d1aa` |
| `pipelinek-bom-0.62.0.json` | `0e9c0f4e0f8c5910a0e460eb4e7e9072b35636733c02db5125a677395bb0d1aa` |
| `distribution-manifest.json` | `69653ebd01d952522c02569db8ce0e9954935cbf9d943834d78d2a7cd372b1b9` |

`SHA256SUMS` (en este directorio): 3 entradas (zip, sbom, bom).

## Gap respecto al contrato §8 (DISTRIBUTION-FIRST RELEASE POLICY)

El contrato ejecutable exige **5 assets** (AGENTS.md §8): `pipelinek-<v>.zip`,
`pipelinek-<v>.sbom.json`, `pipelinek-<v>.sdk.zip`, `pipelinek-bom-<v>.json`,
`SHA256SUMS`. **El build actual sólo produce 3** (zip, sbom, bom); el cuarto
falla por dos motivos:

1. **`pipelinek-<v>.sdk.zip` no existe como build target.** El SDK de
   `:pipeline-step-sdk:*` se publica vía `:pipeline-step-sdk:*:jar` y
   `publishSdkPublicationToSdkRepository` al repo Maven local de Gradle, pero
   no hay target Gradle que empaque los SDK JARs en un ZIP distribuible.
   La allowlist no contempla un build parcial; el certifier debe rechazar
   la candidata o esperar a que se añada el target.

2. **`pipelinek-bom-0.62.0.json` y `pipelinek-0.62.0.sbom.json` son
   byte-idénticos** (mismo SHA-256 `0e9c0f4e...`). El plugin CycloneDX
   produce un único `bom.json` que el `candidateAdmission` copia a
   `pipelinek-<v>.sbom.json`. El contrato §8 trata `bom` y `sbom` como
   artefactos distintos, lo cual requiere un build target separado.

## Estado del gate `:pipeline-release:releaseGate`

`releaseGate` se ejecutó con SHA `f32bd439` (build file fix + cierre BQ4 v3)
y produjo los artefactos arriba listados, pero la fase
`:pipeline-architecture-tests:test` + `:pipeline-application:test` se
ejecutó con 22 fallos **pre-existentes** (no introducidos por este cierre):

- `ObsAOutputStreamingCharacterisationTest` (2) — `IOException`
- `ObsE5TailTest` (3) — `IOException`
- `OBS-PC-208 — Nivel B` (2 falsificadores) — `IllegalStateException`, `AssertionFailedError`
- `ReconciliationStatusOnlyTest` (2) — `UnsatisfiedLinkError`
- `MainEventsCliRefusalVisibilityTest` (5) — `UnsatisfiedLinkError`
- `MainEventsCliArgumentStrictnessTest` (2) — `UnsatisfiedLinkError`
- `GitCheckoutExecutorAdversarialTest > ADV-007` — `UnsatisfiedLinkError`
- `DirectivePluginContractSuiteTest` — `AssertionFailedError`
- `W1 — TypedEventsCapabilityRedTest` (3) — `UnsatisfiedLinkError`
- `UAT-TYPED-002/008/009` — `UnsatisfiedLinkError`

Estos fallos vienen de las áreas OBS-*, OBS-PC-208, W1-events, que NO se
modificaron en este cierre (los cambios están en
`v2/test-harness/.../cycle/*` y un nuevo
`v2/pipeline-application/.../harness/InProcessProductProbeExecutor.kt`).

**El gate fue abortado manualmente** después de 12+ minutos porque los
fallos pre-existentes bloquearon la finalización. El ZIP, SBOM, manifest
y handoff **sí se generaron** (los targets previos a los tests sí
completaron). Los 5 tests propios del cierre
(`InProcessProductProbeExecutorTest`) **pasaron** y están en el primer
`PASSED` del log.

## Commits incluidos en el SHA `f32bd439`

```
fe58d574  fix(cycle): BQ4 v3 — close external-audit P0 defects A/B/C/D
f32bd439  build(test): declare external plugin builds as test task inputs
```

`fe58d574` cierra los 4 defectos P0 nombrados por la auditoría externa
del 2026-10-16 (ADT collapse, path traversal, over-broad delete,
free-form substitute). `f32bd439` es la corrección del build que evita
que Gradle 8.14.5 aborte `:pipeline-application:test` por
implicit-dependency validation (las 3 builds de plugins externos deben
ser dependencias declaradas del task `test`).

## Cambios de producto (BQ4 v2 — cierre de auditoría)

- `ProbeExecutor.runHF0` ahora computa SHA-256 real del archivo en
  `cycleDir/relativePath` y rechaza con `Failed(...)` si el digest no
  coincide o el archivo no existe.
- `ProbeExecutor.persist` graba `evidenceDigest` (SHA-256 del body) en
  cada JSON de evidencia.
- `CycleRunner.finalise` recomputa `evidenceDigest` por archivo y
  rechaza con `error(...)` si los bytes han cambiado (fail-closed).
- `CycleRunner` snapshot del cycle dir en `prepare()`; `finalise()`
  sólo borra los hijos runners-owned y deja los operator-owned
  intactos.
- `CycleState.ProbeFinished` envuelve el ADT `ProbeOutcome` (no
  `(exitCode, evidenceRef)` colapsado).
- `Cycle.decide` exhaustivo sobre `ProbeOutcome`; refuses
  `UnsupportedFidelity` / `BlockedExternal` sin colapsarlos a
  `exitCode=0`.
- `CycleEvent.PromoteRequested` rechaza formas libres:
  `permanent-contract:<id>` o `certifier-receipt:<id>`.
- `ProbeEntry.id` y `relativePath` validados con `isSafeId` /
  `isSafeRelativePath` (no path separators, no `..`).
- `InProcessProductProbeExecutor` (HF1 in-process): corre
  `PipelineSpec` por `CanonicalDurableRunCoordinator` con la misma
  composición que `PipelineRule.run`.

## Acción solicitada al certifier

1. **Validar el conjunto de 3 assets** (zip, sbom, bom) contra la
   allowlist de §8. El gap de SDK ZIP + bom-duplicado es **bloqueante**
   para una publicación conforme al contrato; el certifier debe:
   - (a) rechazar la candidata como `INCOMPLETE_ASSETS` y abrir issue
     de gap de build, o
   - (b) aceptar el conjunto parcial como excepción documentada si el
     operador lo aprueba, o
   - (c) esperar a que el repo añada el target de SDK ZIP y la
     separación bom/sbom.
2. **Aplicar `publish_rc_release.py`** cuando el conjunto sea conforme
   (5 assets exactos, ninguno más).
3. **Certificar la candidata** con la batería externa. Los fallos
   pre-existentes arriba listados NO son regresiones de este cierre; el
   certifier debe verificarlos contra la SHA base (no contra HEAD).

## Recibos

- `distribution-manifest.json` — manifest inmutable de la candidata
- `candidate-handoff.json` — handoff con candidate_id y source_commit
- `SHA256SUMS` — digests de los 3 assets buildable
- Logs de la build: `/tmp/rc5-finalok6.log` (1.3M, kill manual tras
  12+ min con 22 fallos pre-existentes)
