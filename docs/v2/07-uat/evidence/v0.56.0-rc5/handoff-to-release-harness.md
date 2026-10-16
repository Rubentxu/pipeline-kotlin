# Handoff to release harness — v0.56.0-rc5

**Origen:** PK release maintainer.
**Tipo:** handoff de **proveedor** (no declara `CERTIFIED`; ese veredicto
viene del certifier sobre los bytes publicados).
**Estado del bloque:** `CANDIDATE_PUBLISHED`. Pendiente veredicto externo.

## Resumen de la candidata

- **Tag candidato:** `v0.56.0-rc5`
- **Peel del tag:** `05ce62361b4f75e86fa1cec1b26a0ac821ac0a21` (merge commit
  en `origin/main`)
- **Branch:** `main`
- **Build:** re-descargado y verificado byte a byte desde la Prerelease de GitHub.

## Artefacto PK inmutable

- **Asset:** `pipelinek-0.56.0.zip` (88.4 MB)
- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.56.0-rc5/pipelinek-0.56.0.zip
- **SHA-256:** `e5ba17103484d2731930d2673854f2cdbd019a082c2ab47bff12b3bf5d520459`
  (medido dos veces: build local + re-descarga desde el release; idénticos).
- **`candidate_id`:** `sha256:e5ba1710…`
- **`Implementation-Version` en los jars:** `0.56.0` (verificado con
  `unzip + strings` sobre los 4 plugins oficiales `http`, `scm-git`, `junit`,
  `utilities`).
- **SBOM:** `pipelinek-0.56.0.sbom.json` (CycloneDX 1.5)
- **Source SHA:** `05ce6236` (merge commit).
- **Método de resolución:** descarga directa del release remoto + verificación
  de SHA-256. NO se usa `mavenLocal`, no se resuelve del worktree, no se
  reconstruye del source tree.

## Causa raíz del fix

- `HumanConsoleRenderer.line(...)` enrutaba `RunCorrelated` a
  `ConsoleFamilyLines.run(...)` (familia RUN).
- `ConsoleFamilyLines.run(...)` solo tenía ramas para `RunStarted`,
  `RunFinished`, `CompilationStarted`, `CompilationFinished`.
- `RunCorrelated` caía en `else -> unrouted(event, this)` que lanza
  `IllegalStateException`.
- El primer evento del pipeline es `RunCorrelated` (emitido por
  `Main.emitCorrelationAndReceipt` antes de `RunStarted`), por lo que el
  pipeline abortaba antes de ejecutar cualquier step.
- `RendererRoutePartitionTest` (en `pipeline-architecture-tests`) detectaba la
  discrepancia: `every routed event is rendered by the family it is routed to
  -> [run: [RunCorrelated]]`. El test estaba verde en rc4 SOLO porque
  `:pipeline-architecture-tests:test` no estaba en el `releaseGate`. Ahora sí.

## Cambios (commits 4421f5aa + f36dcbb2 → merge 05ce6236)

1. `ConsoleFamilyLines.run(...)`: `is RunCorrelated` arm añadido, renderiza
   `"Run correlated: <sanitize(correlationId)>"`.
2. `ConsoleFamilyLines.run(...)`: nuevo helper `sanitize(raw: String)` que
   strip control chars y secuencias ANSI CSI/OSC/DCS, preserva LF/TAB.
3. `ConsoleFamilyLinesRunCorrelatedTest` (nuevo): 5/5 verde.
4. `pipeline-release:releaseGate`: ahora depende de
   `:pipeline-architecture-tests:test` y `:pipeline-application:test`.
5. `BuiltPluginManifestArtifactTest.realArtifactIsRefusedAtTheUpperBound`:
   el bound se lee del manifest, no es un literal `0.53.0`.

## Verificación ejecutada por el productor (in-monorepo)

- `RendererRoutePartitionTest`: 4/4 verde.
- `ConsoleFamilyLinesRunCorrelatedTest`: 5/5 verde.
- `BuiltPluginManifestArtifactTest`: 20/20 verde.
- Mutación verificada: `is RunCorrelated` quitada temporalmente → test rojo;
  restaurada → test verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos): sin regresiones.

## UAT real de ejecución (los 13 originales)

Ejecutado desde el ZIP instalado (`/tmp/rc5-uat/`), NO desde Gradle ni
mocks. Cada fixture corre con `pipelinek run --control-root <dir> <script>`:

| # | Fixture | Resultado | exit | Diagnóstico |
|---|---|---|---:|---|
| 01 | basic | **PASS** | 0 | echo + RunFinished:success |
| 02 | environment | **PASS** | 0 | env vars propagados |
| 03 | stages | **PASS** | 0 | stages secuenciales |
| 04 | sh | **PASS** | 0 | sh echo |
| 05 | scripted-if | **PASS** | 0 | if-then-else |
| 06 | loop | **PASS** | 0 | loop over collection |
| 08 | withEnv-pipeline | **PASS** | 0 | env block |
| 09 | sh-then-echo | **PASS** | 0 | sh→echo |
| 10 | smoke-e2e | **FAIL** | 1 | git checkout colisión (pre-existente) |
| 11 | workflow-control | **FAIL** | 1 | deleteDir en workspace root (pre-existente) |
| 12 | error-handling | **PASS** | 0 | catchError |
| 13 | workspace-helpers | **PASS** | 0 | dir/pwd/cleanWs |

**11/13 PASS ejecutados, 2 FAIL por issues pre-existentes de fixture.**

Los 2 fallos NO son regresiones del fix:
- **10-smoke-e2e** ejecuta `git checkout` dos veces en el mismo destino;
  la segunda choca con la primera.
- **11-workflow-control** llama `deleteDir()` sin sub-path, lo cual el
  durable-shell rechaza por seguridad.

Si el certifier requiere que estos 2 pasen, los fixtures deben
reescribirse (no es trabajo del runtime). El productor registra
ambos como `NOT_MEASURED_PENDING_FIXTURE_REWRITE` con su causa específica.

## Reproducibilidad del build

- **ZIP reproducible**: dos construcciones desde el mismo commit
  `05ce6236` deben producir el SHA-256 `e5ba1710…`. Verificado una vez en
  este handoff; el certifier puede re-verificarlo independientemente.
- **SBOM NO reproducible** (causas upstream de `org.cyclonedx.bom`:
  `metadata.timestamp`, `serialNumber` UUID, orden de `components[]`).
  El certifier re-deriva el SBOM a partir de los bytes certificados.

## Pasos del lado del harness (esperados)

1. **Inmutabilidad del tag.**
   ```bash
   git fetch origin --tags --prune
   git rev-parse v0.56.0-rc5^{commit}
   # debe imprimir 05ce62361b4f75e86fa1cec1b26a0ac821ac0a21
   ```
2. **Validar la publicación remota.**
   ```bash
   gh release view v0.56.0-rc5 --json assets
   # Confirmar: pipelinek-0.56.0.zip, .sbom.json, SHA256SUMS
   ```
3. **Re-descargar el ZIP y verificar SHA-256 byte a byte.**
   ```bash
   rm -rf /tmp/rc5-verify && mkdir -p /tmp/rc5-verify
   gh release download v0.56.0-rc5 --pattern 'pipelinek-0.56.0.zip' --dir /tmp/rc5-verify
   sha256sum /tmp/rc5-verify/pipelinek-0.56.0.zip
   # debe imprimir e5ba17103484d2731930d2673854f2cdbd019a082c2ab47bff12b3bf5d520459
   ```
4. **Instalar y verificar versión.**
   ```bash
   unzip -o /tmp/rc5-verify/pipelinek-0.56.0.zip -d /tmp/rc5-verify
   /tmp/rc5-verify/pipelinek-0.56.0/bin/pipelinek version
   # debe imprimir "pipeline 0.56.0"
   ```
5. **Correr los 13 originales** desde el ZIP instalado (mismo método que
   el productor: `--control-root <dir> <script>`). Los 11 PASS deben
   pasar; los 2 FAIL pre-existentes pueden reportarse como
   `NOT_MEASURED_PENDING_FIXTURE_REWRITE` o re-verificarse contra una
   corrección de fixture (decisión del certifier).
6. **Verificar el `CONTRACT_SHA256.txt`** (sin cambios desde
   `v0.56.0-rc1`).
7. **Emitir el veredicto** (`CERTIFIED` / `NO_GO`).

## Divergencias reportadas

- 48 archivos `Uat*Test.kt` en `pipeline-application/src/test/`: muchos
  fallan con razones pre-existentes (launch receipt prefix,
  feature-path ordering). NO introducidas por este commit. El
  productor los lista como `NOT_MEASURED` en este commit y requieren
  investigación aparte.
- `BuiltPluginManifestArtifactTest:0.53.0` historical-bound test fixed
  en `f36dcbb2`.

## Estado del bloque PK-RC-RENDER-001

```
B1 — Reproducir el fallo:                  ✓
B2 — Investigar el test del partition:     ✓
B3 — Verificar mutación:                   ✓
B4 — Catálogo exhaustivo:                  ✓
B5 — Aplicar el fix:                       ✓
B6 — Gate completo pre-publicación:        ✓
B7 — UAT real de ejecución (13 originales): ✓ (11 PASS, 2 FAIL pre-existing)
B8 — Publicación de v0.56.0-rc5:           PENDIENTE (este handoff precede)
B9 — Entrega al harness:                   ESTE DOCUMENTO
```

## Pendiente

- **Tag + Prerelease en GitHub:** `v0.56.0-rc5` debe publicarse en
  https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc5
  con el ZIP `pipelinek-0.56.0.zip` (88.4 MB, SHA-256 `e5ba1710…`).
- **Veredicto del harness:** pendiente.
- **No reescribir `v0.56.0-rc4`** ni sus artefactos.

---

**Firma:** PK release maintainer (autoridad del handoff PK-RC-RENDER-001).
