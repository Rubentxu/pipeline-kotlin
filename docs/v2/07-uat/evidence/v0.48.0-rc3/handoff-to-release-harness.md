# Handoff a `pipelinek-release-harness` — v0.48.0-rc3

**Fecha:** 2026-10-10
**Candidata:** v0.48.0-rc3 (train `0.48.0`, sequence 2)
**Tag:** `v0.48.0-rc3` (anotado, peel = `91579c66`)
**Estado B2-rc3:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a
estable la hace el harness, no el repositorio de producto).

## Lo que el repositorio entrega

1. **Tag anotado inmutable** sobre el commit de build: `v0.48.0-rc3 → 91579c66`.
2. **Prerelease en GitHub** con el asset re-descargado y verificado:
   `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc3`.
3. **Material inmutable** en `dist/candidates/v0.48.0-rc3/`:
   - `pipelinek-0.48.0.zip` (88.2 MB, SHA-256 `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6`)
   - `pipelinek-0.48.0.sbom.json` (1.3 MB, SHA-256 `b5227918cccb02f1b99ec18600ef7f1f072eaa2deb599e8f7ee8797839856ef3`)
   - `candidate-handoff.json` (CandidateId = sha256:4bec0844…)
   - `distribution-manifest.json`
   - `SHA256SUMS`
4. **Recibo de la candidata** con la traza del gate y del incidente de procedencia
   previo: `docs/v2/07-uat/evidence/v0.48.0-rc3/release-receipt.md`.
5. **Recibo de la candidata anterior** como incidente histórico:
   `docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md` (status
   `PROVENIENCIA_INCIDENT`, tag `v0.48.0-rc2` preservado).

## Reproducibilidad del build

- **ZIP reproducible byte a byte**: dos construcciones independientes (worktrees
  `pk-rc3-buildA` y `pk-rc3-buildB` desde `91579c66`, `GRADLE_USER_HOME`
  independientes) producen ambas el SHA-256 `4bec0844…`. La `candidate_id` que
  el harness consume es estable entre construcciones.
- **SBOM NO reproducible byte a byte** entre worktrees: tres causas
  upstream del plugin `org.cyclonedx.bom:1.8.2`:
  1. `metadata.timestamp` (hora de pared por build)
  2. `serialNumber: "urn:uuid:..."` (UUID fresco por build)
  3. Orden de `components[]` (HashMap interno del plugin)
  Registrado en el recibo, **no normalizado** por directiva del productor. El
  certifier re-deriva el SBOM a partir de los bytes certificados; el SHA del
  SBOM publicado es histórico y no necesita re-producirse byte a byte.
- **Handoff y manifest NO reproducibles** entre worktrees porque incluyen el
  SHA del SBOM (cascada de la no-reproducibilidad del SBOM).

## Cómo el harness verifica la candidata

```bash
# 1. Inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.48.0-rc3^{commit}     # debe imprimir 91579c660c618f10d6c3ee87db6e0f7445407118

# 2. Validar la publicación remota.
gh release view v0.48.0-rc3 --json assets
# Confirmar 5 assets: pipelinek-0.48.0.zip, .sbom.json, SHA256SUMS,
# candidate-handoff.json, distribution-manifest.json

# 3. Re-descargar el ZIP y verificar SHA-256 byte a byte.
rm -rf /tmp/rc3-verify && mkdir -p /tmp/rc3-verify
gh release download v0.48.0-rc3 --pattern 'pipelinek-0.48.0.zip' --dir /tmp/rc3-verify
sha256sum /tmp/rc3-verify/pipelinek-0.48.0.zip
# Debe imprimir 4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6

# 4. Confirmar que el binario reporta 0.48.0 (no -rc3, no -rc2).
unzip -p /tmp/rc3-verify/pipelinek-0.48.0.zip \
  'pipelinek-0.48.0/lib/pipeline-application-0.48.0.jar' \
  | strings | grep -E 'Implementation-Version|version=0' | head -1
# Debe imprimir Implementation-Version: 0.48.0  /  version=0.48.0

# 5. Reconstruir la imagen del harness desde este ZIP (NO reutilizar la
#    imagen del run wu-rp-053; identificarla solo por nombre es el patrón
#    que el operador prohíbe explícitamente).
podman build -f container/Containerfile \
  --build-arg ZIP_SHA256=4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6 \
  --no-cache -t pipelinek-harness:dogfood-pipelinek-0.48.0-rc3 .

# 6. Ejecutar la batería de certificación.
python3 -m harness.cli receive-candidate \
  --zip /tmp/rc3-verify/pipelinek-0.48.0.zip \
  --sha256 4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6 \
  --version pipelinek-0.48.0-rc3
scripts/certify_battery.sh \
  pipelinek-harness:dogfood-pipelinek-0.48.0-rc3 \
  <candidate-short-id>
```

## Resultado esperado del certifier

- `decision: CERTIFIED`
- `candidate_id == sha256:4bec0844…`
- `artifact_sha256 == 4bec0844…`
- `source_commit: 91579c66`

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Certificación (`CERTIFIED`)** y promoción a estable. La.release model v2
  §3 / §5: el repositorio entrega candidatas inmutables; `pipelinek-release-harness`
  decide cuándo promover, sin reconstrucción, reutilizando los mismos bytes
  del ZIP publicado. GA se haría como tag `v0.48.0` con assets renombrados
  a `pipelinek-0.48.0.{zip,sbom.json}` + `SHA256SUMS`, según el patrón de
  `docs/pipelinek-release-evolution/shared/02-release-model-v2.md` §6.

## Hand-off

- **Provenance decider (nuevo):** `CandidateTagProvenance` en
  `v2/pipeline-release/src/main/kotlin/.../release/CandidateTagProvenance.kt`.
  Evalúa `tagPeel=91579c66, sourceCommit=91579c66, publishedSha=4bec0844…,
  rebuildSha=4bec0844…` → `Clean`. Esto es la razón por la que la candidata
  rc3 es admisible y la rc2 no.
- **Regression test (nuevo):**
  `v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
  codifica el incidente rc2 como fixture rechazada.
- **Próximo bloque del roadmap:** CRIC-M1 (output.follow.v1 + events.follow.v1)
  en rama independiente, sin acoplar a esta candidata.
