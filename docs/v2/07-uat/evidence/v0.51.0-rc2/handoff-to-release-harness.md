# Handoff a `pipelinek-release-harness` — v0.51.0-rc2

**Fecha:** 2026-10-11
**Candidata:** v0.51.0-rc2 (train `0.51.0`, sequence 2; reemplaza a `v0.51.0-rc1` huérfana)
**Tag:** `v0.51.0-rc2` (anotado, peel = `566624259e03732ffb332718a92f76020e1c0a07`)
**Estado:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable
la hace el harness, no el repositorio de producto).

## Lo que el repositorio entrega

1. **Tag anotado inmutable** sobre el commit de build: `v0.51.0-rc2 → 56662425`.
2. **Material inmutable** en `dist/candidates/v0.51.0-rc2/`:
   - `pipelinek-0.51.0.zip` (88.3 MB, SHA-256 `b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72`)
   - `pipelinek-0.51.0.sbom.json` (1.3 MB, SHA-256 `f9fba314654f6c314919483a8bbef63b14e5dbb5d7b75308899f908be3de10dd`)
   - `candidate-handoff.json` (CandidateId = `sha256:b5b72636…`)
   - `distribution-manifest.json`
   - `SHA256SUMS`
3. **Recibo de la candidata** con la traza del gate y del gap documentado:
   `docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md`.
4. **Recibo de la candidata anterior** como precedente:
   `docs/v2/07-uat/evidence/v0.51.0-rc1/` (status huérfano, tag
   `v0.51.0-rc1` preservado sin rewrite).
5. **Consumer externo** que verifica el SDK publicado:
   `examples/example-external-consumer/`, 50/50 tests verdes, JAR SHA
   `c6deada2…` reproducible.

## Resumen del gate

| Módulo | Tests | Failures | Skipped | Tiempo |
|---|---:|---:|---:|---:|
| `:pipeline-application:test` | 2832 | 0 | 123 | ~26 min |
| `:pipeline-release:test` | 104 | 0 | 0 | ~1 min |
| `:example-external-consumer:test` (T8) | 50 | 0 | 0 | ~1 min |

Comandos reproducidos por el agente productor:

```bash
cd v2
./gradlew :pipeline-application:test --console=plain
./gradlew :pipeline-release:test --console=plain
cd ../examples/example-external-consumer
./gradlew -PsdkRepo=$(realpath ../../v2/build/sdk-repo) -PsdkVersion=0.51.0 test
```

## Reproducibilidad del build

- **ZIP reproducible byte a byte**: SHA `b5b72636…` se mantiene entre
  dos invocaciones de `./gradlew :pipeline-application:distZip` con
  `--rerun-tasks --no-build-cache`. La `candidate_id` que el harness
  consume es estable entre construcciones.
- **SBOM NO reproducible byte a byte** entre builds: tres causas
  upstream del plugin `org.cyclonedx.bom:1.8.2`:
  1. `metadata.timestamp` (hora de pared por build)
  2. `serialNumber: "urn:uuid:..."` (UUID fresco por build)
  3. Orden de `components[]` (HashMap interno del plugin)

  Política heredada del recibo `v0.48.0-rc3`: el certifier re-deriva
  el SBOM desde los bytes certificados; el SHA publicado es histórico
  y no necesita reproducirse byte a byte.
- **Handoff y manifest NO reproducibles** entre builds porque incluyen
  el SHA del SBOM (cascada de la no-reproducibilidad del SBOM).

## Gap documentado en el recibo

R1-E solicitó probar "runtime ports" contra el SDK publicado. El
consumer externo sólo cubrió los puertos de LECTURA
(`OutputReadPort`, `OutputFrameIndex`, `EventRecordReadPort`). Los
puertos de PUBLICACIÓN/SUSCRIPCIÓN (`RuntimeRecoverPort`,
`RuntimeControlPort`, `RuntimeIntrospectionPort`) viven en
`pipeline-runtime`, que está fuera de `publishedContractModules`.

Por decisión del operador (selección "Ship rc2, document gap" antes
de T9), esta candidata rc2 se publica con el gap documentado. El
certifier decidirá si requiere `v0.51.0-rc3` que extienda el SDK.

## Cómo el harness verifica la candidata

```bash
# 1. Inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.51.0-rc2^{commit}
# Debe imprimir 566624259e03732ffb332718a92f76020e1c0a07

# 2. Validar la publicación remota.
gh release view v0.51.0-rc2 --json assets
# Confirmar 5 assets: pipelinek-0.51.0.zip, .sbom.json, SHA256SUMS,
# candidate-handoff.json, distribution-manifest.json

# 3. Re-descargar el ZIP y verificar SHA-256 byte a byte.
rm -rf /tmp/rc2-verify && mkdir -p /tmp/rc2-verify
gh release download v0.51.0-rc2 --pattern 'pipelinek-0.51.0.zip' --dir /tmp/rc2-verify
sha256sum /tmp/rc2-verify/pipelinek-0.51.0.zip
# Debe imprimir b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72

# 4. Confirmar que el binario reporta 0.51.0 (no -rc2, no -rc1).
unzip -p /tmp/rc2-verify/pipelinek-0.51.0.zip \
  'pipelinek-0.51.0/lib/pipeline-application-0.51.0.jar' \
  | strings | grep -E 'Implementation-Version' | head -1
# Debe imprimir Implementation-Version: 0.51.0

# 5. Reconstruir la imagen del harness desde este ZIP (NO reutilizar
#    imágenes previas; identificarlas solo por nombre es el patrón que
#    el operador prohíbe explícitamente).
podman build -f container/Containerfile \
  --build-arg ZIP_SHA256=b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72 \
  --no-cache -t pipelinek-harness:dogfood-pipelinek-0.51.0-rc2 .

# 6. Ejecutar la batería de certificación.
python3 -m harness.cli receive-candidate \
  --zip /tmp/rc2-verify/pipelinek-0.51.0.zip \
  --sha256 b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72 \
  --version pipelinek-0.51.0-rc2
scripts/certify_battery.sh \
  pipelinek-harness:dogfood-pipelinek-0.51.0-rc2 \
  <candidate-short-id>
```

## Resultado esperado del certifier

- `decision: CERTIFIED`
- `candidate_id == sha256:b5b72636…`
- `artifact_sha256 == b5b72636…`
- `source_commit: 566624259e03732ffb332718a92f76020e1c0a07`

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Certificación (`CERTIFIED`)** y promoción a estable. La release
  model v2 §3/§5: el repositorio entrega candidatas inmutables;
  `pipelinek-release-harness` decide cuándo promover, sin
  reconstrucción, reutilizando los mismos bytes del ZIP publicado. GA
  se haría como tag `v0.51.0` con assets renombrados a
  `pipelinek-0.51.0.{zip,sbom.json}` + `SHA256SUMS`, según el patrón
  de `docs/pipelinek-release-evolution/shared/02-release-model-v2.md` §6.
- **Push a origin** del tag y de la rama `main` con los 55 commits
  ahead. Pendiente de autorización explícita del operador; el agente
  productor no asume esa decisión.

## Hand-off

- **Provenance decider (nuevo, fortalecido):**
  - `SourceProvenance.evaluate(...)` corre sobre la working tree del
    productor y devuelve `Clean` cuando la candidata se construye
    sobre el commit declarado sin cambios sin commitear. Verificado
    durante la emisión de esta candidata: el `build` inicial fue
    rehusado con un mensaje que nombraba el archivo uncommitted (el
    fix de la heurística del probe del gate) y se admitió sólo tras
    el commit `5666242` del fix.
  - `BundledPluginCompatibility.evaluate(...)` (nuevo en R1-C)
    extiende la admisión con la verificación de que cada plugin
    dentro del ZIP admite la versión del host. Verificado:
    `compatible: 0.51.0 admitted by 4 bundled plugin(s) (…http 0.51.0
    api=[0.47.0, 0.52.0), …junit 0.1.0 api=[0.47.0, 0.52.0), …)`.
- **Próximo bloque del roadmap:** decisión del certifier sobre el
  gap de `pipeline-runtime` ports publish/subscribe. Si requiere
  v0.51.0-rc3, se extiende `publishedContractModules` en v2, se
  regenera el SDK, se re-ejecuta T8 con la superficie extendida, y
  se publica rc3.
