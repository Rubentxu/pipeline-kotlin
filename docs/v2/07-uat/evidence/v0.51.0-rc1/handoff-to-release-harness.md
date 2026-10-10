# Handoff a `pipelinek-release-harness` — v0.51.0-rc1

**Fecha:** 2026-10-11
**Candidata:** v0.51.0-rc1 (train `0.51.0`, sequence 1) — **abre el train 0.51.0**
**Tag:** `v0.51.0-rc1` (anotado, peel = `f6e55aa3`)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción
a estable la hace el harness, no el repositorio de producto).

## Capacidades publicadas por esta candidata

Las **tres** nuevas capacidades negociables del train 0.51.0 quedan
declaradas `EXPERIMENTAL` en `INTERFACE_CONTRACT.md` §6 desde esta
candidata, junto con la **carry-forward** de las cinco capacidades
M1+M2 ya existentes (M1 PUBLICADA desde `v0.49.0-rc1`; M2 EXPERIMENTAL
desde `v0.50.0-rc1`). Los consumidores deben actualizar su rama de
negociación para reconocer las tres nuevas M3; los releases anteriores
siguen bajo el contrato "ausencia => fallback o refusal" sin cambios.

| Capability ID | Versión | Estado en `v0.51.0-rc1` | Test unitario | Test cross-JVM |
|---|---|---|---|---|
| `output.follow.v1` | v1 | **PUBLICADA** (carry-forward de `v0.49.0-rc1`) | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) |
| `events.follow.v1` | v1 | **PUBLICADA** (carry-forward de `v0.49.0-rc1`) | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) |
| `runtime.inspect.v1` | v1 | **EXPERIMENTAL** (carry-forward de `v0.50.0-rc1`) | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) | — |
| `runtime.cancel.v1` | v1 | **EXPERIMENTAL** (carry-forward de `v0.50.0-rc1`) | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) | — |
| `runtime.recover.v1` | v1 | **EXPERIMENTAL** (carry-forward de `v0.50.0-rc1`) | `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`) + `RuntimeRecoverDecisionTableFitnessTest` (12 casos, `:pipeline-runtime`) | — |
| `output.read.digested.v1` | v1 | **EXPERIMENTAL** (nueva en `v0.51.0-rc1`) | `OutputReadDigestedAdapterTest` (5 casos, `:pipeline-output-store`) | — |
| `output.pin.v1` | v1 | **EXPERIMENTAL** (nueva en `v0.51.0-rc1`) | `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`) | — |
| `output.refusal.retention.v1` | v1 | **EXPERIMENTAL** (nueva en `v0.51.0-rc1`) | `PruneAuthorisationAdapterTest` (5 casos, `:pipeline-output-store`), `OutputRefusalClosedTest` (4 casos, `:pipeline-output-store`), `RecoverRefusalPinExtensionTest` (3 casos, `:pipeline-runtime`) | — |

La transición "EXPERIMENTAL => PUBLICADA" para las tres capacidades
`CRIC-M3` ocurre tras la certificación de esta candidata por
`pipelinek-release-harness`. Hasta entonces, los consumidores que
adopten `v0.51.0-rc1` deben tratarlas como negociables pero todavía no
estables; los releases anteriores siguen bajo "ausencia => fallback o
refusal" sin cambios.

## Lo que el repositorio entrega

1. **Tag anotado inmutable** sobre el commit de build: `v0.51.0-rc1 →
   f6e55aa3599772edbb2733dc4ed4ad060a1aa223`.
2. **Prerelease en GitHub** con el asset re-descargado y verificado:
   `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.51.0-rc1`.
3. **Material inmutable** en `dist/candidates/v0.51.0-rc1/`:
   - `pipelinek-0.51.0.zip` (88.3 MB, SHA-256
     `4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`)
   - `pipelinek-0.51.0.sbom.json` (1.3 MB, SHA-256
     `50001f30191e00a30e6b0f1682a18bdf15fa458028bee2753b2c38d63441fff3`)
   - `candidate-handoff.json` (CandidateId =
     `sha256:4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`)
   - `distribution-manifest.json`
   - `SHA256SUMS`
4. **Recibo de la candidata** con la traza del gate, las capacidades
   publicadas y el verdict de determinismo:
   `docs/v2/07-uat/evidence/v0.51.0-rc1/release-receipt.md`.
5. **PK_CONTRACT_HANDOFF** a Fabric con la petición de actualizar
   `INTERFACE_CONTRACT.md` §6 + tabla "Capacidades publicadas
   (CRIC-M3)" (ver
   `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m3.md`).
6. **Recibo de la candidata anterior** como referencia histórica del
   train anterior: `docs/v2/07-uat/evidence/v0.50.0-rc1/release-receipt.md`
   (status `CANDIDATE_PUBLISHED` a la espera del veredicto del
   certifier; el tag `v0.50.0-rc1` está preservado).

## Reproducibilidad del build

- **ZIP reproducible byte a byte**: dos construcciones independientes
  (worktree A inicial + worktree B con `rm -rf pipeline-application/build
  .gradle && GRADLE_USER_HOME=/tmp/gradle-home-rc-verify-51` desde
  `f6e55aa3`) producen ambas el SHA-256 `4acf1a6c…`. La `candidate_id`
  que el harness consume es estable entre construcciones.
- **SBOM NO reproducible byte a byte** entre builds: tres causas
  upstream del plugin `org.cyclonedx.bom`:
  1. `metadata.timestamp` (hora de pared por build)
  2. `serialNumber: "urn:uuid:..."` (UUID fresco por build)
  3. Orden de `components[]` (HashMap interno del plugin)
  Registrado en el recibo, **no normalizado** por directiva del
  productor. El certifier re-deriva el SBOM a partir de los bytes
  certificados; el SHA del SBOM publicado es histórico y no necesita
  re-producirse byte a byte.
- **Handoff y manifest NO reproducibles** entre builds porque incluyen
  el SHA del SBOM (cascada de la no-reproducibilidad del SBOM).

## Cómo el harness verifica la candidata

```bash
# 1. Inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.51.0-rc1^{commit}     # debe imprimir f6e55aa3599772edbb2733dc4ed4ad060a1aa223

# 2. Validar la publicación remota.
gh release view v0.51.0-rc1 --json isDraft,targetCommitish,assets
# Confirmar: isDraft=false, targetCommitish==f6e55aa3, 5 assets
# (pipelinek-0.51.0.zip, .sbom.json, SHA256SUMS,
#  candidate-handoff.json, distribution-manifest.json)

# 3. Re-descargar el ZIP y verificar SHA-256 byte a byte.
rm -rf /tmp/rc1-verify && mkdir -p /tmp/rc1-verify
gh release download v0.51.0-rc1 --pattern 'pipelinek-0.51.0.zip' --dir /tmp/rc1-verify
sha256sum /tmp/rc1-verify/pipelinek-0.51.0.zip
# Debe imprimir 4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450

# 4. Confirmar que el binario reporta 0.51.0 (no -rc1, no 0.50.0).
unzip -p /tmp/rc1-verify/pipelinek-0.51.0.zip \
  'pipelinek-0.51.0/lib/pipeline-application-0.51.0.jar' \
  | strings | grep -E 'Implementation-Version|version=0' | head -1
# Debe imprimir Implementation-Version: 0.51.0  /  version=0.51.0

# 5. Reconstruir la imagen del harness desde este ZIP (NO reutilizar
#    una imagen previa identificada solo por nombre; el operador
#    prohíbe explícitamente ese patrón).
podman build -f container/Containerfile \
  --build-arg ZIP_SHA256=4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450 \
  --no-cache -t pipelinek-harness:dogfood-pipelinek-0.51.0-rc1 .

# 6. Ejecutar la batería de certificación.
python3 -m harness.cli receive-candidate \
  --zip /tmp/rc1-verify/pipelinek-0.51.0.zip \
  --sha256 4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450 \
  --version pipelinek-0.51.0-rc1
scripts/certify_battery.sh \
  pipelinek-harness:dogfood-pipelinek-0.51.0-rc1 \
  <candidate-short-id>
```

## Resultado esperado del certifier

- `decision: CERTIFIED`
- `candidate_id == sha256:4acf1a6c…`
- `artifact_sha256 == 4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`
- `source_commit: f6e55aa3599772edbb2733dc4ed4ad060a1aa223`
- `product_version: 0.51.0`

Tras la certificación `CERTIFIED`, las capacidades `CRIC-M3`
(`output.read.digested.v1`, `output.pin.v1`,
`output.refusal.retention.v1`) transicionan de `EXPERIMENTAL` a
`PUBLICADA` en `INTERFACE_CONTRACT.md` §6 y en la tabla "Capacidades
publicadas (CRIC-M3)" — `Fabric` debe reflejar este cambio en su propio
contrato
`Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`.

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Certificación (`CERTIFIED`)** y promoción a estable. La release
  model v2 §3 / §5: el repositorio entrega candidatas inmutables;
  `pipelinek-release-harness` decide cuándo promover, sin
  reconstrucción, reutilizando los mismos bytes del ZIP publicado. GA se
  haría como tag `v0.51.0` con assets renombrados a
  `pipelinek-0.51.0.{zip,sbom.json}` + `SHA256SUMS`, según el patrón
  de `docs/pipelinek-release-evolution/shared/02-release-model-v2.md`
  §6.

## Hand-off

- **Provenance decider (existente):** `CandidateTagProvenance` en
  `v2/pipeline-release/src/main/kotlin/.../release/CandidateTagProvenance.kt`.
  Evalúa `tagPeel=f6e55aa3, sourceCommit=f6e55aa3,
  publishedSha=4acf1a6c…, rebuildSha=4acf1a6c…` → `Clean`. Esta
  candidata es admisible bajo la misma ley que la rc1 de los trains
  0.50.0 y 0.49.0.
- **Regression test (existente):**
  `v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
  codifica los incidentes rc2 (proveniencia) y v0.40.0/v0.43.0 (version
  laundering) como fixtures rechazadas. Mantiene la invariante.
- **PK_CONTRACT_HANDOFF** paralelo: ver
  `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m3.md`.
  Es **un handoff de proveedor**, no una declaración de `PAIR_CERTIFIED`
  — la promoción del par de releases la decide el Integrador tras
  `PAIR_CERTIFIED` mutuo entre PK × Fabric.
- **Próximo bloque del roadmap:** depende de la decisión del
  Integrador. CRIC-M3 entrega los puertos `output.read.digested.v1`,
  `output.pin.v1`, `output.refusal.retention.v1` como EXPERIMENTAL; los
  siguientes hitos (CRIC-M4..M7) son trabajo de bloques 4-7 sobre
  ramas independientes.
