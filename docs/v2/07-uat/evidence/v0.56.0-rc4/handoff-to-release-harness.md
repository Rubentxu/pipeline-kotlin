# Handoff a `pipelinek-release-harness` — v0.56.0-rc4

**Fecha:** 2026-10-12
**Candidata:** v0.56.0-rc4 (train `0.56.0`, sequence 4) — **release correctiva** sobre
`v0.56.0-rc3` que elimina el drift de compatibilidad de los plugins oficiales
(PK-RC-PLUGIN-001, P0).
**Tag:** `v0.56.0-rc4` (anotado, peel = `f6b020e4` sobre `main`).
**Estado 1.D:** `CANDIDATE_PUBLISHED`.

## Por qué `v0.56.0-rc4` y no solo `v0.56.0-rc3`

`v0.56.0-rc3` se publicó con los 4 plugins oficiales llevando
`apiRange=[0.47.0, 0.53.0)` mientras el host era `0.56.0`. `PluginAdmissionGate`
habría rechazado todos los plugins en su propia distribución. La rc3 se conserva
inmutable como **historical-incompatible**; la candidata canónica final para
`CERTIFIED` es `v0.56.0-rc4`.

## Cambios sobre `v0.56.0-rc3`

1. **`buildSrc/PluginCompatibilityPolicy`** (nuevo, Java puro): lee
   `rootProject.version` y deriva el extremo superior exclusivo como siguiente
   minor (0.56.0 → 0.57.0).
2. **`pipeline-step-sdk:runtime/PluginCompatibilityResource`** (nuevo): lector
   runtime, fail-closed.
3. **Los 4 `*PluginDeclaration.kt`**: el literal `PipelineKApiRange(0.47.0, 0.53.0)`
   eliminado; `API_RANGE` ahora es un property que carga del recurso generado.
4. **Los 4 plugin `build.gradle.kts`**: el `inputs.property("apiRange", "...")`
   eliminado. Nueva task `generatePluginCompatibilityMetadata` wired a
   `processResources` + `jar` + `emitXxxManifest`.
5. **Plugins externos NO modificados** — su `apiRange` es suyo.

## Reproducción de la causa raíz (con bytes oficiales de GitHub)

```bash
$ curl -sL https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.56.0-rc3/pipelinek-0.56.0.zip -o rc3.zip
$ unzip -p rc3.zip 'pipelinek/lib/http-0.56.0.jar' META-INF/pipelinek/plugin-manifest.json \
    | jq '{apiRange, releaseVersion}'
{
  "apiRange": "[0.47.0, 0.53.0)",
  "releaseVersion": "0.56.0"
}
# 0.56.0 está FUERA de [0.47.0, 0.53.0) — admission rechaza

$ ./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=3 -Pcandidate.tag=v0.56.0-rc3
[release] candidate REFUSED: bundled-plugin compatibility FAILED for host 0.56.0:
  - pipeline-plugin-http@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.junit@0.1.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.scm-git@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.utilities@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
```

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`
  (verificado re-descargado desde GitHub: idéntico).
- **5 SDK coordinates** a `0.56.0`: idénticas a rc1+rc2+rc3.
- **`candidate_id`:** `sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.

## Batería de certificación (PK-RC-PLUGIN-001)

- `PluginCompatibilityPolicyTest` (5 casos) — verde.
- `PluginCompatibilityResourceTest` (6 casos) — verde.
- `BuiltPluginManifestArtifactTest` (6 casos, con `RuntimeApiVersion.current()`) — verde.
- `DistributionMandatoryPluginsTest` (3 casos) — **3/3 verde** contra rc4 + reproducer
  rc3 (gated on `PK_RC_PLUGIN_001_RC3_ZIP`).
- `BundledPluginCompatibilityTest` (9 casos sintéticos) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 161+ casos certificadores, todos verdes.

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado** (candidate_id =
   `sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`).
2. **Correr la batería completa** (los 161+ casos arriba).
3. **Verificar el `--correlation-id`** (regresión de PK-1): `pipeline run --correlation-id <uuid> --db <path> <script>` y parsear la línea `pipelinek.launch/1` de stdout.
4. **Verificar los 4 manifests en el ZIP**: cada uno debe llevar `apiRange="[0.47.0, 0.57.0)"` y
   `releaseVersion="0.56.0"` (junit puede llevar `0.1.0`, su `releaseVersion` propio).
5. **Correr el gate de reproducibilidad**:
   ```bash
   ./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=4 -Pcandidate.tag=v0.56.0-rc4 --rerun-tasks
   # Esperado: candidate admission PASSED, candidate_id coincide con el asset SHA-256
   ```
6. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED, las
   9 capabilities EXPERIMENTAL pueden promocionarse a PUBLICADA en un release
   posterior.

## Estado del bloque PK-RC-PLUGIN-001 (final)

```
B1 — Causa raíz:           ✓
B2 — Fuente única:         ✓
B3 — Tests + gate:         ✓
B4 — Release + recert:     ✓
```

## Pendiente

- ✅ **Tag + Prerelease en GitHub:** `v0.56.0-rc4` publicada en
  https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc4
  ZIP `pipelinek-0.56.0.zip` (88.3 MB) subido como asset con
  SHA-256 `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.
- ✅ **Merge en `origin/main`:** commit `f6b020e4`.
- **Veredicto del harness:** pendiente. Se añadirá al `release-receipt.md` cuando llegue.
- **`v0.56.0-rc3`** queda en GitHub como Prerelease **historical-incompatible** (no
  reescrita, no refirmada, no tocada); la candidata canónica para `CERTIFIED` es
  **`v0.56.0-rc4`**.

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-RC-PLUGIN-001 final).
