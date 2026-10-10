# Handoff a `pipelinek-release-harness` — v0.49.0-rc1

**Fecha:** 2026-10-10
**Candidata:** v0.49.0-rc1 (train `0.49.0`, sequence 1) — **abre el train 0.49.0**
**Tag:** `v0.49.0-rc1` (anotado, peel = `a96c70ae`)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a
estable la hace el harness, no el repositorio de producto).

## Capacidades publicadas por esta candidata

Las dos nuevas capacidades negociables del train 0.49.0 quedan declaradas
`PUBLICADA` en `INTERFACE_CONTRACT.md` §6 desde esta candidata. Los
consumidores deben actualizar su rama de negociación para reconocerlas;
los releases anteriores siguen bajo el contrato
"ausencia => fallback o refusal".

| Capability ID | Versión | Test unitario | Test cross-JVM |
|---|---|---|---|
| `output.follow.v1` | v1 | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) |
| `events.follow.v1` | v1 | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) |

## Lo que el repositorio entrega

1. **Tag anotado inmutable** sobre el commit de build: `v0.49.0-rc1 → a96c70ae`.
2. **Prerelease en GitHub** con el asset re-descargado y verificado:
   `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.49.0-rc1`.
3. **Material inmutable** en `dist/candidates/v0.49.0-rc1/`:
   - `pipelinek-0.49.0.zip` (88.2 MB, SHA-256
     `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583`)
   - `pipelinek-0.49.0.sbom.json` (1.3 MB, SHA-256
     `41efccf96fdd63171160fad4221487637c01fabce5b348793ecfd61ac371b977`)
   - `candidate-handoff.json` (CandidateId = sha256:24f9e967…)
   - `distribution-manifest.json`
   - `SHA256SUMS`
4. **Recibo de la candidata** con la traza del gate, las capacidades
   publicadas y el verdict de determinismo:
   `docs/v2/07-uat/evidence/v0.49.0-rc1/release-receipt.md`.
5. **PK_CONTRACT_HANDOFF** a Fabric con la petición de actualizar
   `INTERFACE_CONTRACT.md` §6 (ver
   `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m1.md`).
6. **Recibo de la candidata anterior** como referencia histórica del train
   anterior: `docs/v2/07-uat/evidence/v0.48.0-rc3/release-receipt.md` (status
   `CANDIDATE_PUBLISHED` a la espera del veredicto del certifier; el tag
   `v0.48.0-rc3` está preservado).

## Reproducibilidad del build

- **ZIP reproducible byte a byte**: dos construcciones independientes
  (worktree A inicial + worktree B con `rm -rf pipeline-application/build
  .gradle && GRADLE_USER_HOME=/tmp/gradle-home-rc1-verify` desde
  `a96c70ae`) producen ambas el SHA-256 `24f9e967…`. La `candidate_id` que
  el harness consume es estable entre construcciones.
- **SBOM NO reproducible byte a byte** entre builds: tres causas upstream
  del plugin `org.cyclonedx.bom`:
  1. `metadata.timestamp` (hora de pared por build)
  2. `serialNumber: "urn:uuid:..."` (UUID fresco por build)
  3. Orden de `components[]` (HashMap interno del plugin)
  Registrado en el recibo, **no normalizado** por directiva del productor. El
  certifier re-deriva el SBOM a partir de los bytes certificados; el SHA del
  SBOM publicado es histórico y no necesita re-producirse byte a byte.
- **Handoff y manifest NO reproducibles** entre builds porque incluyen el
  SHA del SBOM (cascada de la no-reproducibilidad del SBOM).

## Cómo el harness verifica la candidata

```bash
# 1. Inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.49.0-rc1^{commit}     # debe imprimir a96c70ae000b6f223b76bc5bb409033acd47859b

# 2. Validar la publicación remota.
gh release view v0.49.0-rc1 --json assets
# Confirmar 5 assets: pipelinek-0.49.0.zip, .sbom.json, SHA256SUMS,
# candidate-handoff.json, distribution-manifest.json

# 3. Re-descargar el ZIP y verificar SHA-256 byte a byte.
rm -rf /tmp/rc1-verify && mkdir -p /tmp/rc1-verify
gh release download v0.49.0-rc1 --pattern 'pipelinek-0.49.0.zip' --dir /tmp/rc1-verify
sha256sum /tmp/rc1-verify/pipelinek-0.49.0.zip
# Debe imprimir 24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583

# 4. Confirmar que el binario reporta 0.49.0 (no -rc1, no 0.48.0).
unzip -p /tmp/rc1-verify/pipelinek-0.49.0.zip \
  'pipelinek-0.49.0/lib/pipeline-application-0.49.0.jar' \
  | strings | grep -E 'Implementation-Version|version=0' | head -1
# Debe imprimir Implementation-Version: 0.49.0  /  version=0.49.0

# 5. Reconstruir la imagen del harness desde este ZIP (NO reutilizar una
#    imagen previa identificada solo por nombre; el operador prohíbe
#    explícitamente ese patrón).
podman build -f container/Containerfile \
  --build-arg ZIP_SHA256=24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583 \
  --no-cache -t pipelinek-harness:dogfood-pipelinek-0.49.0-rc1 .

# 6. Ejecutar la batería de certificación.
python3 -m harness.cli receive-candidate \
  --zip /tmp/rc1-verify/pipelinek-0.49.0.zip \
  --sha256 24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583 \
  --version pipelinek-0.49.0-rc1
scripts/certify_battery.sh \
  pipelinek-harness:dogfood-pipelinek-0.49.0-rc1 \
  <candidate-short-id>
```

## Resultado esperado del certifier

- `decision: CERTIFIED`
- `candidate_id == sha256:24f9e967…`
- `artifact_sha256 == 24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583`
- `source_commit: a96c70ae000b6f223b76bc5bb409033acd47859b`
- `product_version: 0.49.0`

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Certificación (`CERTIFIED`)** y promoción a estable. La release model v2
  §3 / §5: el repositorio entrega candidatas inmutables;
  `pipelinek-release-harness` decide cuándo promover, sin reconstrucción,
  reutilizando los mismos bytes del ZIP publicado. GA se haría como tag
  `v0.49.0` con assets renombrados a `pipelinek-0.49.0.{zip,sbom.json}` +
  `SHA256SUMS`, según el patrón de
  `docs/pipelinek-release-evolution/shared/02-release-model-v2.md` §6.

## Hand-off

- **Provenance decider (existente):** `CandidateTagProvenance` en
  `v2/pipeline-release/src/main/kotlin/.../release/CandidateTagProvenance.kt`.
  Evalúa `tagPeel=a96c70ae, sourceCommit=a96c70ae, publishedSha=24f9e967…,
  rebuildSha=24f9e967…` → `Clean`. Esta candidata es admisible bajo la
  misma ley que la rc3.
- **Regression test (existente):**
  `v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
  codifica los incidentes rc2 (proveniencia) y v0.40.0/v0.43.0 (version
  laundering) como fixtures rechazadas. Mantiene la invariante.
- **PK_CONTRACT_HANDOFF** paralelo: ver
  `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m1.md`.
  Es **un handoff de proveedor**, no una declaración de `PAIR_CERTIFIED` —
  la promoción del par de releases la decide el Integrador tras
  `PAIR_CERTIFIED` mutuo entre PK × Fabric.
- **Próximo bloque del roadmap:** depende de la decisión del Integrador.
  CRIC-M1 entrega el output plano live + eventos live; los siguientes
  hitos (CRIC-M2..M7) son trabajo de bloques 2-7 sobre ramas
  independientes.