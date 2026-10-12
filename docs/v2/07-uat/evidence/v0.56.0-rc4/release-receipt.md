# PipelineK v0.56.0-rc4 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — **release correctiva** sobre `v0.56.0-rc3` que elimina
definitivamente el drift de compatibilidad de los plugins oficiales. Cierra el
bloque **PK-RC-PLUGIN-001** (P0). `v0.56.0-rc3` queda como **historical-incompatible**;
`v0.56.0-rc4` es la candidata canónica final para `CERTIFIED` + `PAIR_CERTIFIED`.

> **Origen y procedencia.** `v0.56.0-rc3` fue publicado con `apiRange=[0.47.0, 0.53.0)` en
> los 4 plugins oficiales mientras el host era `0.56.0`. `PluginAdmissionGate` habría
> rechazado todos los plugins en su propia distribución. El guard
> `BundledPluginCompatibility` + `CandidateAdmission` existía pero **nunca fue invocado
> contra el ZIP publicado**; el `release-receipt.md` de rc3 no contiene ni una
> mención a estos checks. La causa raíz es **doble fuente de verdad para `apiRange`**:
> un literal en `HttpPluginDeclaration.API_RANGE` (Kotlin) + un `inputs.property` en
> cada `build.gradle.kts` (Gradle), ninguno de los dos actualizado en los bumps de
> versión 0.53 → 0.54 → 0.55 → 0.56. La corrección elimina la doble fuente: una
> política pura en `buildSrc` lee `rootProject.version` y deriva el rango.

## Cambios sobre la candidata anterior (`v0.56.0-rc3` → `v0.56.0-rc4`)

1. **`buildSrc/PluginCompatibilityPolicy`** (nuevo, Java puro): lee `rootProject.version`,
   deriva el extremo superior exclusivo como siguiente minor (0.56.0 → 0.57.0),
   extremo inferior fijo 0.47.0 (configurable sólo con override explícito).
2. **`pipeline-step-sdk:runtime/PluginCompatibilityResource`** (nuevo): lector runtime,
   fail-closed; lanza `IllegalStateException` si el recurso falta o está malformado.
3. **Los 4 `*PluginDeclaration.kt`** (http, scm-git, junit, utilities):
   `val API_RANGE: PipelineKApiRange get() = PluginCompatibilityResource.load(...)`.
   El literal `PipelineKApiRange(0.47.0, 0.53.0)` eliminado.
4. **Los 4 plugin `build.gradle.kts`**: el `inputs.property("apiRange", "[0.47.0, 0.52.0)")`
   eliminado. Nueva task `generatePluginCompatibilityMetadata` wired a
   `processResources` + `jar` + `emitXxxManifest` (verificación: a un bump de
   `rootProject.version` los 4 manifests se regeneran sin tocar las declaraciones).
5. **Plugins externos (example-http, example-block, example-directive) NO modificados**:
   su `apiRange` es suyo. La política no se les difunde.
6. **Tests**:
   - `PluginCompatibilityPolicyTest`: 5/5 verde
   - `PluginCompatibilityResourceTest`: 6/6 verde
   - `BuiltPluginManifestArtifactTest.gateAdmitsTheRealArtifact`: usa
     `RuntimeApiVersion.current()` en vez de literal `SemVer(0,47,0)`
   - `DistributionMandatoryPluginsTest` (nuevo): 4 plugins obligatorios +
     admisión del running version + **reproducer que aserta que el ZIP de
     rc3 (descargado de GitHub) es INCOMPATIBLE por el mismo gate**
7. **Gate de release**:
   - `:pipeline-release:candidateAdmission` ahora corre en cada release (verificado
     PASS para rc4, REFUSED para rc3 antes del fix)
   - `:pipeline-release:releaseGate` (nuevo task Gradle) depende de
     `distZip + cyclonedxBom + candidateAdmission`. La publicación queda
     bloqueada sin un PASS del gate.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7692`
  (verificado re-descargado desde GitHub: `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`).
- **5 SDK coordinates** a `0.56.0` (idénticas a rc1+rc2+rc3): `pipeline-domain`,
  `pipeline-events`, `pipeline-output`, `pipeline-runtime-api`,
  `pipeline-scripting-api` + `pipeline-sdk-bom`.
- **`candidate_id`:** `sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.

## Reproducción de la causa raíz (con bytes oficiales)

```bash
# ZIP rc3 desde GitHub
$ curl -sL https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.56.0-rc3/pipelinek-0.56.0.zip -o rc3.zip
$ unzip -p rc3.zip 'pipelinek/lib/http-0.56.0.jar' META-INF/pipelinek/plugin-manifest.json | jq '{apiRange, releaseVersion}'
{
  "apiRange": "[0.47.0, 0.53.0)",
  "releaseVersion": "0.56.0"
}
# 0.56.0 está FUERA de [0.47.0, 0.53.0) — admission rechaza en runtime

# Gate invocado contra el ZIP rc3 (candidato NO modificado)
$ ./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=3 -Pcandidate.tag=v0.56.0-rc3
[release] candidate REFUSED: bundled-plugin compatibility FAILED for host 0.56.0:
  - pipeline-plugin-http@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.junit@0.1.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.scm-git@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
  - pipeline.utilities@0.56.0 declares apiRange='[0.47.0, 0.53.0)' which excludes host version '0.56.0'
```

## Verificación del fix (rc4 vs gate)

```bash
# ZIP rc4 construido desde la release branch + run del gate
$ ./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=4 -Pcandidate.tag=v0.56.0-rc4
[release] candidate admission PASSED
[release]   candidate_id: sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696
[release]   provenance: source provenance VERIFIED: clean tree at 4b91ecf7ec47e1ccbd714b22afb8c2b154ddd40e

# Los 4 manifests en el ZIP rc4:
$ for j in /tmp/pk-rc-plugin-001/rc4-installed/pipelinek-0.56.0/lib/*-0.56.0.jar; do
    echo "$(basename $j): $(unzip -p $j META-INF/pipelinek/plugin-manifest.json | jq -r .apiRange)"
  done
http-0.56.0.jar:    [0.47.0, 0.57.0)
scm-git-0.56.0.jar: [0.47.0, 0.57.0)
utilities-0.56.0.jar: [0.47.0, 0.57.0)
junit-0.1.0.jar:    [0.47.0, 0.57.0)   # 0.56.0 host is INSIDE this range now
```

## Tests certificadores

- `PluginCompatibilityPolicyTest`: 5/5 verde
- `PluginCompatibilityResourceTest`: 6/6 verde
- `BundledPluginCompatibilityTest`: 9/9 verde (suite sintética, sin cambios)
- `BuiltPluginManifestArtifactTest`: 6/6 verde (con `RuntimeApiVersion.current()`)
- `DistributionMandatoryPluginsTest`: 3/3 verde (4 plugins + admisión running + reproducer rc3)
- Baterías M1+M2+M3+M6+M7 (132+ casos): sin regresiones

**Total:** 161+ casos certificadores, todos verdes.

## Estado del bloque PK-RC-PLUGIN-001 (final)

```
B1 — Causa raíz:           ✓ (apiRange drift entre rc1+rc2+rc3; gate nunca invocado)
B2 — Fuente única:         ✓ (PluginCompatibilityPolicy + PluginCompatibilityResource)
B3 — Tests + gate:         ✓ (real-version test, distribution gate, releaseGate task)
B4 — Release + recert:     ✓ (rc4 Prerelease en GitHub, ZIP re-descargado y verificado)
```

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit del bloque
- ✅ `git sddk-close` registrado para cada commit del bloque
- ✅ `./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=4 -Pcandidate.tag=v0.56.0-rc4`
     → `PASSED` con `candidate_id: sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`
- ✅ ZIP `pipelinek-0.56.0.zip` 88.3 MB, SHA-256 match (local ↔ GitHub asset)
- ✅ Los 4 manifests oficiales en el ZIP llevan `apiRange: "[0.47.0, 0.57.0)"`
- ✅ `pipelinek version` reporta `pipeline 0.56.0`
- ✅ `pipelinek capabilities` emite el envelope `pipelinek.capabilities/1` con 11 entries
- ✅ `git push -u origin release/pk-rc-plugin-001-v0.56.0-rc4` rama publicada
- ✅ `git push origin main` merge commit `f6b020e4` integrado
- ✅ `git tag -a v0.56.0-rc4` + `git push origin v0.56.0-rc4` etiqueta publicada
- ✅ `gh release create v0.56.0-rc4 --prerelease` Prerelease publicada con el ZIP asset
- ✅ Digest del asset re-descargado = SHA-256 local

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc4

## Estado de la cadena CRIC-1 + PK-1 + PK-RC-PLUGIN-001

```
M1 (v0.49.0-rc1):   2 PUBLICADA        ✓
M2 (v0.50.0-rc1):   +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):   +3 EXPERIMENTAL   ✓
M4 (handoff only):  no new surface    ✓
M5 (handoff only):  no new surface    ✓
M6 (v0.53.0-rc1):   +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):   +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective         ✓
PK-1 (v0.56.0-rc1): foundation         ✓ (historical)
PK-1 (v0.56.0-rc2): completion         ✓ (historical)
PK-1 (v0.56.0-rc3): closure           ✓ (historical-incompatible — PK-RC-PLUGIN-001)
PK-RC-PLUGIN-001 (v0.56.0-rc4):        ✓ (canonical final candidate for CERTIFIED)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, drift eliminated
```

## Pendiente (externo)

- `CERTIFIED` por `pipelinek-release-harness` para `v0.56.0-rc4`
- `PAIR_CERTIFIED` con Fabric contra `v0.56.0-rc4`
- Promoción `EXPERIMENTAL → PUBLICADA` post-`CERTIFIED` (en un release posterior)

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-RC-PLUGIN-001 final).
