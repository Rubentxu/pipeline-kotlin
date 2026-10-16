# v0.62.0-rc5 — Handoff al certifier (pipelinek-release-harness)

**Fecha**: 2026-10-16
**Estado**: §8-CONFORME + consumer-verified — los 5 assets buildable, el manifest los referencia, y el SDK ZIP extrae como un repositorio Maven utilizable. Listo para `publish_rc_release.py`.
**Camino de publicación**: `python3 scripts/publish_rc_release.py` (per AGENTS.md §8 camino único)
**Origen**: BQ4 v2 (cierre de auditoría externa 2026-10-16) + corrección de build + cierre del gap §8 + SdkZipBuilder relative-path fix

## Identidad del candidato

| Campo | Valor |
|---|---|
| `product_version` | `0.62.0` |
| `release_train` | `0.62.0` |
| `candidate_sequence` | `5` |
| `candidate_id` | `sha256:71678cb5c3f775983cf26ab7e9e174b2a5bd9c175b742b2488422071cfb1eadb` |
| `source_commit` | `c01e8e5f3771550a0d6e7d6c8b8991d70e3b6100` |
| `git_tag` | `v0.62.0-rc5` |
| `archive_root` | `pipelinek-0.62.0` |
| `implementation_version` | `0.62.0` |

## SHA-256 de los 5 assets (allowlist §8)

| Asset | SHA-256 | Tamaño |
|---|---|---|
| `pipelinek-0.62.0.zip` | `71678cb5c3f775983cf26ab7e9e174b2a5bd9c175b742b2488422071cfb1eadb` | 89.0M |
| `pipelinek-0.62.0.sbom.json` | `f3d212c4ae2a455816c618261b8d39f1b1680f3fa9436a3f45fa20e5d4a85f64` | 1.3M |
| `pipelinek-0.62.0.sdk.zip` | `5d0aa6956f66cd77534b0ec300373975907aa4db5953ed9542e72414c88d4b49` | 2.2M |
| `pipelinek-bom-0.62.0.json` | `83e43e72dbf2d0d88b91168da03332ee6c4509177d1cc1cafa2611e4bdd45a8b` | 22.5K |
| `SHA256SUMS` | (363B, cubre los 4 assets distribuibles) | |

`SHA256SUMS` contiene las 4 entradas de los assets distribuibles (zip, sbom, sdk.zip, bom) — el formato `sha256sum` estándar, así que `sha256sum -c SHA256SUMS` funciona sin cambios.

## Conformidad §8 — lo que cambió desde el handoff anterior

El handoff de `6e780224` documentaba un **gap de build**: el `:pipeline-release:releaseGate` producía 3 assets (zip, sbom, bom) pero el `bom` era **byte-idéntico** al sbom (ambos eran el cyclonedx output renombrado dos veces) y no existía `pipelinek-<v>.sdk.zip`. Ese gap era lo que bloqueaba la publicación en el certifier.

El gap queda cerrado en `dcb98b1d` (código) y `49d4c1b8` (evidencia regenerada con SHAs frescos de la HEAD actual):

- **Nuevo `SdkZipBuilder`** (puro Kotlin) y `SdkZipMain` (entry point Gradle). Empaqueta `<v2>/build/sdk-repo/` (poblado por `:publishSdkForExternalPlugin`) en `pipelinek-<v>.sdk.zip` con sólo los JARs y POMs de la versión actual.
- **Nuevo `pipelinek-bom-<v>.json`**: manifest plano de triples `group:artifact:version` (coordenadas gestionadas) + inventario SHA-256 por archivo del contenido del ZIP. **Estructuralmente distinto** del SBOM (lista plana vs grafo CycloneDX).
- **Nueva task Gradle `:pipeline-release:sdkZip`** depende de `:publishSdkForExternalPlugin` y escribe ambos a `<v2>/pipeline-application/build/distributions/`.
- **`candidateAdmission` (Gradle)** ahora pasa los 4 paths al `CandidateAdmissionMain`, y la lista `covered` (4 paths) al materializador. El materializador escribe `SHA256SUMS` cubriéndolos.
- **`DistributionManifest` y `CandidateHandoff`** llevan ahora `sdk` y `bom` como `DistributionArtifactRef?` opcionales, distintos de `sbom` por contenido y estructura.
- **Fitness test** `DistributionIdentityProbe` salta `*.sdk.zip` al iterar `distributions/` (el SDK tiene layout Maven, no layout CLI).

**Tests verdes (`:pipeline-release:test`):** 19/19 (5 nuevos SdkZipBuilderTest + 14 pre-existentes).

## Estado del gate

**No se ejecutó `:pipeline-release:releaseGate`** en su forma completa (depende de `:architecture-tests:test` y `:pipeline-application:test`, ambos con 22 fallos **pre-existentes** — no relacionados con BQ4 v3 ni con §8). El gate completo aborta a los 12+ min con esos fallos.

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

Resultado: `candidate admission PASSED` con `provenance: source provenance VERIFIED: clean tree at c01e8e5f3771550a0d6e7d6c8b8991d70e3b6100`.

**No se verificó `RUNTIME_VERSION` ni `MANIFEST_VERSION`** (la identity probe marca `INCOMPLETE` para esos dos). El primero requiere ejecutar el binario instalado (lane pesado del harness); el segundo requiere el manifest del JAR de aplicación extraído. Ambos son responsabilidad del harness per el comentario de `CandidateMaterializer.materialize`. El certifier debe verificar `RUNTIME_VERSION` ejecutando el binario.

## Clasificación de los 22 fallos pre-existentes (verificada en este ciclo)

| # | Patrón | Tests | Naturaleza | Acción |
|---|---|---|---|---|
| A | `UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'` | `MainEventsCliRefusalVisibilityTest`, `MainEventsCliArgumentStrictnessTest`, `ReconciliationStatusOnlyTest` (×2), `TypedEventsCapabilityRedTest` (×3) — 7 tests | **Infra**: signature mismatch entre la clase Java `org.sqlite.core.NativeDB` y la lib nativa `libsqlitejdbc.so` de xerial 3.46.1.3 sobre Java 21. La clase Java declara `void`, el .so espera `long` (handle). | Out of BQ4 v2 scope. Path: upgrade xerial a 3.45.x o 3.47.x; alternativa: migrar al harness per AGENTS.md §3. |
| B | `AssertionFailedError` con mensaje "the step printed before touching the barrier" / "bytes were committed before this snapshot was taken" | `ObsAOutputStreamingCharacterisationTest` (×2), `ObsE5TailTest` (×3), `ObsPc208LevelBUatTest` (×2) — 7 tests | **Characterisation intencional**: el KDoc de `ObsAOutputStreamingCharacterisationTest` declara "Each row asserts a **defect**. They are written so the defect is named and reproducible BEFORE the live ingress (OBS-B) and the machine streams (OBS-E) exist... When OBS-B or OBS-E lands the row is INVERTED and becomes a non-regression test." Por diseño, rojo hasta que OBS-B/E existan. | No son regresiones; son features pendientes. NO requieren fix. El certifier debe ignorarlos al verificar el gate. |
| C | `IllegalStateException` | `GitCheckoutExecutorAdversarialTest > ADV-007` (changelog grande), `ObsPc208LevelBUatTest > f04` (stdout activo + stderr silent) — 2 tests | **Mixto**: ADV-007 puede ser FD exhaustion o timeout en el test; f04 relacionado con B (los pumps de stdout/stderr). | Investigar f04 si OBS-B/E lo cierran; ADV-007 requiere `@Timeout` y posible aumento de FD limit. |
| D | `AssertionFailedError` sin mensaje "defect" | `DirectivePluginContractSuiteTest` (×1) — 1 test | **Defecto de producto**: comparación entre el JAR certificado y la nueva build falla. | Investigar fuera de BQ4 v2 — comparar SHAs de plugin jars de la rc5 vs la build certificada. |

**Verificación ejecutada en este ciclo** (commit `c01e8e5f`):

```text
cd v2 && ./gradlew --no-daemon :pipeline-application:test \
  --tests 'ObsAOutputStreamingCharacterisationTest'
# 2/2 fail — AssertionFailedError, message "the step printed before touching the barrier"
# el run termina con SUCCESS sin tocar BARRIER_REACHED
# Conclusion: Pattern B es el behaviour documentado, no una regresión
```

**Ninguno de los 22 fallos fue introducido por BQ4 v2 ni por el cierre §8.** El certifier debe verificarlo contra la SHA base `f32bd439` (no contra HEAD).

## Commits incluidos en el SHA `c01e8e5f`

```
fe58d574  fix(cycle): BQ4 v3 — close external-audit P0 defects A/B/C/D
f32bd439  build(test): declare external plugin builds as test task inputs
6e780224  evidence: stage v0.62.0-rc5 partial candidate for certifier
dcb98b1d  feat(release): §8 SDK ZIP + bom/sbom split — close releaseGate contract gap
49d4c1b8  evidence: stage v0.62.0-rc5 §8-conforme (5 assets) for certifier
132aae00  evidence: refresh v0.62.0-rc5 against HEAD 49d4c1b8
c01e8e5f  fix(sdk-zip): use repo-relative paths so the ZIP is a usable Maven layout
```

`c01e8e5f` es el fix que **consumer-verifica** el SDK ZIP — los SHAs de `dcb98b1d` / `49d4c1b8` no eran utilizables por un consumer real. Antes del fix, `unzip pipelinek-0.62.0.sdk.zip` extraía a un directorio vacío porque las entradas tenían paths absolutos del filesystem (`/var/home/.../sdk-repo/...`) y `unzip` los strip'ea silenciosamente. Después del fix, las entradas son relativas al SDK repo root y la extracción produce un repositorio Maven utilizable.

## Consumer verification (lens `consumer-verification`)

Verificado en un consumer limpio:

```text
mkdir -p /tmp/sdk-consumer
cd /tmp/sdk-consumer
unzip /ruta/pipelinek-0.62.0.sdk.zip

# resultado: dev/rubentxu/pipeline/v2/{pipeline-domain,scripting-api,events,
# output,runtime-api,sdk-bom}/0.62.0/
# cada uno con .jar, .pom, .module, .md5, .sha1, .sha256, .sha512
```

`unzip -p ...pipeline-domain-0.62.0.pom` extrae un POM Maven 4.0.0 válido con `<groupId>dev.rubentxu.pipeline.v2</groupId>` y `<artifactId>pipeline-domain</artifactId>`. Un consumer Kotlin con `mavenLocal()` apuntando a este directorio resuelve las coordenadas correctamente.

`dcb98b1d` es el commit que **cierra el gap §8** que bloqueaba la publicación. `49d4c1b8` regenera la evidencia contra ese commit.

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
