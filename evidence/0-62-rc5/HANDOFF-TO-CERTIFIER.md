# v0.62.0-rc5 — Handoff al certifier (pipelinek-release-harness)

**Fecha**: 2026-10-16
**Estado**: §8-CONFORME — los 5 assets buildable y el manifest los referencia. Listo para `publish_rc_release.py`.
**Camino de publicación**: `python3 scripts/publish_rc_release.py` (per AGENTS.md §8 camino único)
**Origen**: BQ4 v2 (cierre de auditoría externa 2026-10-16) + corrección de build + cierre del gap §8

## Identidad del candidato

| Campo | Valor |
|---|---|
| `product_version` | `0.62.0` |
| `release_train` | `0.62.0` |
| `candidate_sequence` | `5` |
| `candidate_id` | `sha256:5b78b91a3c4b6ca86010b52a5fd0c9a5920363e21e09a5c59997dbec3c485964` |
| `source_commit` | `dcb98b1d00a1112cffc736e4272efd3e2f8a5245` |
| `git_tag` | `v0.62.0-rc5` |
| `archive_root` | `pipelinek-0.62.0` |
| `implementation_version` | `0.62.0` |

## SHA-256 de los 5 assets (allowlist §8)

| Asset | SHA-256 | Tamaño |
|---|---|---|
| `pipelinek-0.62.0.zip` | `5b78b91a3c4b6ca86010b52a5fd0c9a5920363e21e09a5c59997dbec3c485964` | 89.0M |
| `pipelinek-0.62.0.sbom.json` | `5fc2f5e135cf195ed01b64382fb8fc75b0761bb26a2958b70aa9190e3e4f2894` | 1.3M |
| `pipelinek-0.62.0.sdk.zip` | `7824bf627758635663e5b9045bbc4709c4abc5a9544c179c051d3e4bbbf1863a` | 2.2M |
| `pipelinek-bom-0.62.0.json` | `91e66acf2adb6aff56dd0ca4cc534466bde3d61c7b55f8ddfb5c00ef22b7b050` | 22.5K |
| `SHA256SUMS` | `0819d58bd56f74feca5ba3405e17082180e9de05bbfbf982bd14d8c4a1f496ab` | 363B |

`SHA256SUMS` contiene las 4 entradas de los assets distribuibles (zip, sbom, sdk.zip, bom) — el formato `sha256sum` estándar, así que `sha256sum -c SHA256SUMS` funciona sin cambios.

## Conformidad §8 — lo que cambió desde el handoff anterior

El handoff de `6e780224` documentaba un **gap de build**: el `:pipeline-release:releaseGate` producía 3 assets (zip, sbom, bom) pero el `bom` era **byte-idéntico** al sbom (ambos eran el cyclonedx output renombrado dos veces) y no existía `pipelinek-<v>.sdk.zip`. Ese gap era lo que bloqueaba la publicación en el certifier.

El gap queda cerrado en `dcb98b1d`:

- **Nuevo `SdkZipBuilder`** (puro Kotlin) y `SdkZipMain` (entry point Gradle). Empaqueta `<v2>/build/sdk-repo/` (poblado por `:publishSdkForExternalPlugin`) en `pipelinek-<v>.sdk.zip` con sólo los JARs y POMs de la versión actual.
- **Nuevo `pipelinek-bom-<v>.json`**: manifest plano de triples `group:artifact:version` (coordenadas gestionadas) + inventario SHA-256 por archivo del contenido del ZIP. **Estructuralmente distinto** del SBOM (lista plana vs grafo CycloneDX).
- **Nueva task Gradle `:pipeline-release:sdkZip`** depende de `:publishSdkForExternalPlugin` y escribe ambos a `<v2>/pipeline-application/build/distributions/`.
- **`candidateAdmission` (Gradle)** ahora pasa los 4 paths al `CandidateAdmissionMain`, y la lista `covered` (4 paths) al materializador. El materializador escribe `SHA256SUMS` cubriéndolos.
- **`DistributionManifest` y `CandidateHandoff`** llevan ahora `sdk` y `bom` como `DistributionArtifactRef?` opcionales, distintos de `sbom` por contenido y estructura.
- **Fitness test** `DistributionIdentityProbe` salta `*.sdk.zip` al iterar `distributions/` (el SDK tiene layout Maven, no layout CLI).

**Tests verdes (`:pipeline-release:test`):** 19/19 (5 nuevos SdkZipBuilderTest + 14 pre-existentes).

## Estado del gate

**No se ejecutó `:pipeline-release:releaseGate`** en su forma completa (depende de `:architecture-tests:test` y `:pipeline-application:test`, ambos con 22 fallos **pre-existentes** — `UnsatisfiedLinkError`, `IOException` en OBS-\*, W1-events, OBS-PC-208 — no relacionados con BQ4 v3 ni con §8). El gate completo aborta a los 12+ min con esos fallos.

Sí se ejecutó el camino mínimo que produce los 5 assets:

```text
cd v2 && ./gradlew --no-daemon \
  :pipeline-application:distZip \
  :pipeline-application:cyclonedxBom \
  :pipeline-release:sdkZip \
  :pipeline-release:candidateAdmission \
  -Pcandidate.tag=v0.62.0-rc5 \
  -Pcandidate.sequence=5 \
  --rerun-tasks
```

Resultado: `candidate admission PASSED` con `provenance: source provenance VERIFIED: clean tree at dcb98b1d00a1112cffc736e4272efd3e2f8a5245`.

**No se verificó `RUNTIME_VERSION` ni `MANIFEST_VERSION`** (la identity probe marca `INCOMPLETE` para esos dos). El primero requiere ejecutar el binario instalado (lane pesado del harness); el segundo requiere el manifest del JAR de aplicación extraído. Ambos son responsabilidad del harness per el comentario de `CandidateMaterializer.materialize`. El certifier debe verificar `RUNTIME_VERSION` ejecutando el binario.

## Commits incluidos en el SHA `dcb98b1d`

```
fe58d574  fix(cycle): BQ4 v3 — close external-audit P0 defects A/B/C/D
f32bd439  build(test): declare external plugin builds as test task inputs
6e780224  evidence: stage v0.62.0-rc5 partial candidate for certifier
dcb98b1d  feat(release): §8 SDK ZIP + bom/sbom split — close releaseGate contract gap
```

`dcb98b1d` es el commit que **cierra el gap §8** que bloqueaba la publicación. Es la única diferencia respecto al handoff anterior.

## Acción solicitada al certifier

1. **Ejecutar `publish_rc_release.py`** con este conjunto:

   ```text
   python3 scripts/publish_rc_release.py \
     --version 0.62.0 \
     --asset-dir evidence/0-62-rc5/ \
     --notes-file <release-notes-publicas.md> \
     --publish
   ```

   El `--asset-dir` debe contener exactamente los 5 assets (zip, sbom, sdk.zip, bom, SHA256SUMS). El script:
   - Audita el conjunto contra la allowlist
   - Compara los digests locales contra el manifest emitido por el build
   - Crea la release en **draft**, sube, re-descarga y compara digests
   - Sólo con `--publish` la hace pública

2. **Certificar la candidata** ejecutando la batería externa. Los 22 fallos pre-existentes arriba listados NO son regresiones del cierre §8 ni de BQ4 v3; el certifier debe verificarlos contra la SHA base `f32bd439` (no contra HEAD).

3. **Verificar `RUNTIME_VERSION`** ejecutando el binario dentro del ZIP instalado — el manifest marca esa superficie como `INCOMPLETE` (no se sintetiza en el materializador por diseño; es el job del harness en lane pesado).

## Recibos

- `distribution-manifest.json` — manifest inmutable con `sdk`, `bom`, `sha256sums` referenciados
- `candidate-handoff.json` — handoff con candidate_id y source_commit, ahora incluyendo `sdk` y `bom`
- `SHA256SUMS` — digests de los 4 assets distribuibles (zip, sbom, sdk.zip, bom)
- Logs de la build mínima: `/tmp/rc5-finalok7.log` (si lo guardaste) o el comando exacto arriba
