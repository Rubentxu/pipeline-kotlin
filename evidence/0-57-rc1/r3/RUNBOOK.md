# R3 RUNBOOK — Plan de re-medición cuando load < 5

**Estado:** R3-0 corregido (2026-10-12T21:55Z) — pendiente de ejecutar bajo
load < 5. No se declara R3 cerrado hasta que las 4 UAT y los gates
auxiliares estén en VERDE bajo load < 5, en este worktree, en este SHA.

**Revisión candidata:** la SHA que se evalúe se obtiene en tiempo de
ejecución con `$(git rev-parse HEAD)`. Este runbook NO contiene una SHA
hardcodeada como referencia, porque cualquier SHA impresa queda
obsoleta en cuanto se commitea. La invariante es: el SHA evaluado, los
SHA-256 de los artefactos regenerados, y el contenido de `git-head.txt`
deben coincidir; si alguno difiere, la candidata no es trazable y se
rechaza.

## Prerrequisito de entorno

```
loadavg 1-min < 5
loadavg 5-min < 5
```

El umbral es el documentado en `BLOCK_E_RUN_21`. La medición actual
está en 1-min 11-17. **No correr este runbook mientras load 1-min > 5**
— los resultados no son autoritativos y deben repetirse. La medición de
load se hace con `awk '{print $1, $2}' /proc/loadavg` justo antes de
cada paso gradle; si supera 5 en cualquier punto, se aborta la corrida
y se documenta en el RECEIPT-R3 como `BLOCKED_ENVIRONMENT`.

## Reglas de captura de exit code (no negociable)

Todos los shell pipelines con gradle DEBEN preservar el exit code real.
`tee` siempre termina 0 y enmascara un fallo de gradle. Usar
`set -o pipefail` o leer `${PIPESTATUS[0]}` explícitamente:

```bash
set -o pipefail
./gradlew ... 2>&1 | tee /tmp/r3-step.log
rc=$?
# rc es ahora el exit code de gradle, no de tee
[ "$rc" -eq 0 ] || { echo "gradle failed: $rc"; exit "$rc"; }
```

O equivalente con `${PIPESTATUS[0]}`:

```bash
./gradlew ... 2>&1 | tee /tmp/r3-step.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || exit "$rc"
```

## Procedimiento

### 1. Baseline (working tree limpio)

```bash
set -euo pipefail
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin

git status -s                                                  # debe estar vacío
CANDIDATE_SHA="$(git rev-parse HEAD)"
echo "candidate=$CANDIDATE_SHA"
git log -1 --format='%H %s'                                    # confirma el subject
date -u +%Y-%m-%dT%H:%M:%SZ                                    # timestamp UTC
awk '{print "load_1min="$1" load_5min="$2}' /proc/loadavg      # debe ser < 5

# La rama DEBE ser pk-release-0-57-consolidation
CURRENT_BRANCH="$(git symbolic-ref --short HEAD)"
[ "$CURRENT_BRANCH" = "pk-release-0-57-consolidation" ] || {
  echo "FAIL: not on pk-release-0-57-consolidation (current: $CURRENT_BRANCH)"
  exit 2
}

# Refrescar el SHA objetivo del bundle
echo -n "$CANDIDATE_SHA" > evidence/0-57-rc1/git-head.txt
```

Si `git status -s` no está vacío, abortar: hay cambios sin commitear
que contaminan la candidata.

### 2. Las 4 UAT obligatorias (cada una DEBE pasar en VERDE)

La R3 NO se cierra si alguna de las 4 UAT termina en rojo. La
clasificación de fallos es para diagnóstico (producto/fixture/protocolo/
entorno), NO para reinterpretar rojo como verde. Si una UAT no pasa, se
repara y se vuelve a correr; no se omite ni se marca como "known fail".

Cada UAT se corre individualmente con su propio deadline y se
captura el XML, el exit code real, y el load durante la corrida.

#### 2.1 `WURp023ObservationModesUatTest` (ya migrada al testkit)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail
load_before="$(awk '{print $1}' /proc/loadavg)"

./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.cli.WURp023ObservationModesUatTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-wurp023.log
rc=${PIPESTATUS[0]}

load_after="$(awk '{print $1}' /proc/loadavg)"
echo "test=WURp023 rc=$rc load_before=$load_before load_after=$load_after sha=$CANDIDATE_SHA"
[ "$rc" -eq 0 ] || { echo "FAIL: WURp023 no pasó (rc=$rc). Clasificar y reparar antes de continuar."; exit "$rc"; }

# Verificar que el XML se generó
XML="v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.WURp023ObservationModesUatTest.xml"
[ -f "$XML" ] || { echo "FAIL: XML de WURp023 no encontrado en $XML"; exit 3; }
```

- **Qué verifica:** contrato de observación bajo R0.3: stdout line 1
  LaunchReceipt, line 2..N eventos JSON; replay del CLI events;
  cursor reconnect; contract verify con exit 0/1/2; unknown runId.
- **Migrada en:** `d443215e` (WURp023 → `CliContractTestKit` +
  `OwnedSubprocess`).
- **Fallo esperado:** el subcommand `events` puede haber cambiado
  contrato entre 0.57 y 0.56 (separación de stdout/stderr). Si
  falla, clasificar como **producto** (el subcommand cambió) o
  **protocolo** (necesita renegociar `--view events`) y emitir fix.

#### 2.2 `UatS2R0RunOwnershipCliTest` (raw `ProcessBuilder` es correcto)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail
load_before="$(awk '{print $1}' /proc/loadavg)"

./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatS2R0RunOwnershipCliTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-uat-s2-r0.log
rc=${PIPESTATUS[0]}

load_after="$(awk '{print $1}' /proc/loadavg)"
echo "test=UatS2R0 rc=$rc load_before=$load_before load_after=$load_after sha=$CANDIDATE_SHA"
[ "$rc" -eq 0 ] || { echo "FAIL: UatS2R0 no pasó (rc=$rc). Clasificar y reparar."; exit "$rc"; }
```

- **Qué verifica:** arbitraje de lease cross-process. Dos
  `pipelinek` reales contra un mismo `--db`; el perdedor debe
  recibir admission rejection tipado (exit 2), no constraint
  violation.
- **Patrón de harness:** `ProcessBuilder` raw, NO `OwnedSubprocess`.
  Esto es APROPIADO: el test FORK procesos reales para probar la
  arbitraje entre ellos. Migrar a `OwnedSubprocess` cambiaría
  la propiedad bajo test.
- **Fallo esperado:** `futex_do_wait` wedge bajo load alto con
  `--max-workers>=2`. La regla del bloque H1.1 es
  `forkEvery=1` y `maxParallelForks=1`, ya aplicada en
  `v2/pipeline-application/build.gradle.kts`. Si falla, clasificar
  como **entorno** (load > 5) y re-medir; NO como producto.

#### 2.3 `UatLocal010SmokeE2ESandboxTest` (FAIL en rc5)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail
load_before="$(awk '{print $1}' /proc/loadavg)"

./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatLocal010SmokeE2ESandboxTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-smoke-e2e.log
rc=${PIPESTATUS[0]}

load_after="$(awk '{print $1}' /proc/loadavg)"
echo "test=UatLocal010SmokeE2ESandbox rc=$rc load_before=$load_before load_after=$load_after sha=$CANDIDATE_SHA"
[ "$rc" -eq 0 ] || { echo "FAIL: smoke-e2e no pasó (rc=$rc). Clasificar y reparar."; exit "$rc"; }
```

- **Qué verifica:** sandbox profile de extremo a extremo, con
  `deleteDir` en workspace aislado.
- **Fallo en rc5:** colisiones de checkout y aislamiento de
  workspace. El commit `a8ee7992` (v0.56.0-rc6) añadió tests
  negativos del publisher pero NO arregló el sandbox.
- **Fallo esperado:** si reproduce, clasificar como **producto**
  (defecto de aislamiento real) o **fixture** (estado global
  compartido) y emitir fix. NO reinterpretar como PASS modificando
  el test.

#### 2.4 `UatLocal011WorkflowControlTest` (FAIL en rc5)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail
load_before="$(awk '{print $1}' /proc/loadavg)"

./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatLocal011WorkflowControlTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-workflow-control.log
rc=${PIPESTATUS[0]}

load_after="$(awk '{print $1}' /proc/loadavg)"
echo "test=UatLocal011WorkflowControl rc=$rc load_before=$load_before load_after=$load_after sha=$CANDIDATE_SHA"
[ "$rc" -eq 0 ] || { echo "FAIL: workflow-control no pasó (rc=$rc). Clasificar y reparar."; exit "$rc"; }
```

- **Qué verifica:** semántica de `deleteDir` especialmente en
  workspace ajeno.
- **Fallo en rc5:** `deleteDir` borra directorios fuera del
  workspace del test — defecto de seguridad.
- **Fallo esperado:** si reproduce, clasificar como **producto**
  (defecto de seguridad real, no se toca el test). Si NO
  reproduce a load < 5, clasificar como **entorno** (load > 5
  en rc5) y NO como PASS sin re-medir.

### 3. Gates auxiliares (todos deben pasar)

Estos gates NO son opcionales. Si alguno falla, R3 no se cierra.

#### 3.1 `detekt`

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail

./gradlew --no-daemon --max-workers=1 detekt 2>&1 | tee /tmp/r3-detekt.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "FAIL: detekt (rc=$rc)"; exit "$rc"; }
```

- `detekt.yml` no debe tener cambios estructurales; si los tiene,
  el commit debe justificarlos en el cuerpo.

#### 3.2 `apiCheck`

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail

./gradlew --no-daemon --max-workers=1 apiCheck 2>&1 | tee /tmp/r3-apiCheck.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "FAIL: apiCheck (rc=$rc)"; exit "$rc"; }
```

- Los 4 `apiDumps` (`pipeline-domain`, `pipeline-events`,
  `pipeline-output`, `pipeline-runtime-api`) deben estar
  regenerados a 0.57.0. Si apiCheck rompe, es un cambio de API
  inadvertido y debe investigarse.

#### 3.3 `releaseGate`

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail

./gradlew --no-daemon --max-workers=1 :pipeline-release:releaseGate 2>&1 \
  | tee /tmp/r3-releaseGate.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "FAIL: releaseGate (rc=$rc)"; exit "$rc"; }
```

- `releaseGate` ejecuta `DistributionMandatoryPluginsTest` (4
  official + 3 example, separados) y exige 10/10 verde contra
  el ZIP real. Su XML debe confirmar que el test se ejecutó (no
  fue omitido por `@EnabledIfEnvironmentVariable` ausente).
- Verificación post-corrida:
  ```bash
  XML="v2/pipeline-release/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.release.DistributionMandatoryPluginsTest.xml"
  [ -f "$XML" ] || { echo "FAIL: XML de DistributionMandatoryPluginsTest no encontrado"; exit 3; }
  grep -c "tests=" "$XML"   # debe ser 4 (los 4 tests)
  ```

### 4. Check integral (3 veces consecutivas, todas en VERDE)

Solo si las 4 UAT y los 3 gates auxiliares pasaron.

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail
declare -a RUN_RESULTS

for i in 1 2 3; do
  load_before="$(awk '{print $1}' /proc/loadavg)"
  start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  ./gradlew --no-daemon --max-workers=1 --rerun-tasks \
    --console=plain check 2>&1 | tee /tmp/r3-check-$i.log
  rc=${PIPESTATUS[0]}

  load_after="$(awk '{print $1}' /proc/loadavg)"
  RUN_RESULTS+=("run=$i start=$start rc=$rc load_before=$load_before load_after=$load_after")
  printf '%s\n' "${RUN_RESULTS[-1]}" | tee -a /tmp/r3-check-summary.txt

  [ "$rc" -eq 0 ] || { echo "FAIL: check run $i no pasó (rc=$rc)"; exit "$rc"; }
done

# Las 3 corridas deben estar en VERDE. Si alguna falla, R3 no se cierra.
echo "all 3 check runs GREEN"
```

Criterio: las 3 corridas terminan con exit 0, sin colgarse, con
`load_during < 5` durante la corrida (medido en `load_before` y
`load_after`).

### 5. Regeneración de artefactos (admisión primero, mover después)

Los artefactos en `evidence/0-57-rc1/` son los que la candidata
publicará. NO se sobreescriben hasta que la admisión del ZIP
haya pasado en un árbol limpio. El flujo correcto es:

```bash
set -euo pipefail
TMP_ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$TMP_ARTIFACTS"' EXIT

cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
set -o pipefail

# 5.1 — Generar en un directorio temporal
./gradlew --no-daemon --max-workers=1 :pipeline-application:distZip 2>&1 \
  | tee /tmp/r3-distZip.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "FAIL: distZip (rc=$rc)"; exit "$rc"; }

# 5.2 — Mover a TMP_ARTIFACTS para admisión
cp v2/pipeline-application/build/distributions/pipelinek-*.zip "$TMP_ARTIFACTS/"
cp v2/pipeline-application/build/distributions/pipelinek-*.zip.sha256 "$TMP_ARTIFACTS/" 2>/dev/null || true

# 5.3 — Generar SBOM y POMs en TMP_ARTIFACTS
./gradlew --no-daemon --max-workers=1 :pipeline-application:sbom 2>&1 \
  | tee /tmp/r3-sbom.log || true   # sbom puede no existir; no falla el gate

# 5.4 — Admisión: PIPELINEK_DIST_ZIP apunta al ZIP temporal, NO al del bundle
export PIPELINEK_DIST_ZIP="$TMP_ARTIFACTS/pipelinek-0.57.0.zip"
./gradlew --no-daemon --max-workers=1 :pipeline-release:releaseGate 2>&1 \
  | tee /tmp/r3-admission.log
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "FAIL: admisión (rc=$rc). NO mover artefactos al bundle."; exit "$rc"; }

# 5.5 — Solo después de admisión en verde, mover al bundle
mv "$TMP_ARTIFACTS/pipelinek-0.57.0.zip" \
   /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/evidence/0-57-rc1/pipelinek-0.57.0.zip

# Regenerar SBOM, JAR, POMs y el resto de artefactos del bundle
# (mismos `cp` desde v2/build/...; el bundle queda en sync con el ZIP admitido)
```

Reglas:
- `evidence/0-57-rc1/` solo se sobreescribe si la admisión pasó
  en el árbol limpio, no en el árbol con cambios pendientes.
- `git status -s` debe estar vacío ANTES de la admisión.
- Si la admisión falla, el ZIP temporal se borra en el `trap` y
  el bundle conserva la candidata anterior.

### 6. SHA-256 y bundle

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/evidence/0-57-rc1
set -euo pipefail

# git-head.txt = SHA evaluado (paso 1 ya lo puso)
NEW_HEAD="$(cat git-head.txt)"
[ "$(git -C .. rev-parse HEAD)" = "$NEW_HEAD" ] || {
  echo "FAIL: git-head.txt no coincide con HEAD"; exit 4; }

# Regenerar SHA256SUMS en orden alfabético, EXCLUYENDO el propio SHA256SUMS
# (auto-referencia es chicken-and-egg por construcción)
for f in $(ls | grep -v '^SHA256SUMS$' | sort); do
  if [ -f "$f" ]; then
    sha256sum "$f" | awk '{print $1, $2}'
  fi
done > SHA256SUMS.tmp
mv SHA256SUMS.tmp SHA256SUMS

# Self-verify: 8 de 9 deben coincidir. El noveno es SHA256SUMS por
# construcción; documentado en STATUS.md.
sha256sum -c SHA256SUMS 2>&1 | tee /tmp/r3-sha256-verify.log
```

### 7. Sincronización de docs

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
set -euo pipefail

# 7.1 — STATUS.md: actualiza fecha, SHA, nota sobre artefactos
NEW_HEAD="$(git rev-parse HEAD)"
sed -i "s/^\\*\\*HEAD actual:\\*\\* .*/\\*\\*HEAD actual:** \`$NEW_HEAD\`/" evidence/0-57-rc1/STATUS.md
sed -i "s/^\\*\\*Última actualización:\\*\\* .*/\\*\\*Última actualización:** $(date -u +%Y-%m-%dT%H:%M:%SZ)/" evidence/0-57-rc1/STATUS.md

# 7.2 — HANDOFF-TO-CERTIFIER.md: SHA y SHAs de artefactos
# (mismo patrón; ver el archivo para los anclajes)

# 7.3 — odd/tasks/pk-0-57-rc1.md: marca R3 cerrado si corresponde
# (solo después de los pasos 1-6 en VERDE)
```

### 8. Verificación ADR-0099 antes de R4

ADR-0099 exige que el tag de release sea alcanzable desde `main`
y que el commit etiquetado integre la candidata preservando la
historia. Antes de pedir R4, verificar:

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
git fetch origin --prune --tags

git status -s                                       # limpio
git rev-parse HEAD                                  # SHA candidato
git rev-parse origin/main                           # main

git merge-base --is-ancestor "$(git rev-parse HEAD)" origin/main
rc=$?
[ "$rc" -eq 0 ] || {
  echo "FAIL: SHA candidato no es ancestro de origin/main (rc=$rc)"
  echo "Esto es exactamente el bug que ADR-0099 prohíbe."
  exit "$rc"
}

# Verificar también que NO estamos a punto de etiquetar un merge commit
# con historia divergente. Si la integración se hizo por merge, la SHA
# candidata debe coincidir con la SHA del merge commit.
```

Si la candidata NO es ancestro de `origin/main`, NO pedir R4. La
integración debe preceder al tag, no al revés.

### 9. RECEIPT-R3

Solo después de los pasos 1-8 en VERDE. Estructura:

```markdown
# R3 RECEIPT — Re-medición de UAT y gate integral

**SHA evaluado:** <output de `git rev-parse HEAD`>
**Fecha:** <timestamp UTC>
**Entorno:** loadavg 1-min=X.XX 5-min=X.XX (debe ser < 5)
**Ciclo:** pk-release-0-57-consolidation

## Resultados por UAT

| UAT | rc | load | XML | SHA-256 | Clasificación |
|-----|----|----|-----|---------|---------------|
| WURp023 | ... | ... | path | ... | VERDE/REPARADO |
| UatS2R0 | ... | ... | path | ... | VERDE/REPARADO |
| smoke-e2e | ... | ... | path | ... | VERDE/REPARADO |
| workflow-control | ... | ... | path | ... | VERDE/REPARADO |

## Gates auxiliares

| Gate | rc | log | observaciones |
|------|----|----|---------------|
| detekt | ... | ... | ... |
| apiCheck | ... | ... | ... |
| releaseGate | ... | ... | XML verificado, 4 tests |

## Check integral (3 corridas)

| Run | start | rc | load_before | load_after |
|-----|-------|----|-------------|------------|
| 1 | ... | ... | ... | ... |
| 2 | ... | ... | ... | ... |
| 3 | ... | ... | ... | ... |

## Artefactos regenerados

- pipelinek-0.57.0.zip: SHA-256 = ...
- pipelinek-0.57.0.sbom.json: SHA-256 = ...
- (resto de los 9)

## Trazabilidad

- git-head.txt = SHA evaluado = HEAD actual
- SHA256SUMS self-verify 8/9 (SHA256SUMS por construcción no se verifica)
- STATUS.md actualizado a la fecha y SHA actuales
- HANDOFF-TO-CERTIFIER.md: SHA y SHAs sincronizados
- odd/tasks/pk-0-57-rc1.md: T4a, T4b, T9a, T9b, T9c marcados [x]

## ADR-0099 check

- SHA candidato ancestro de origin/main: SÍ/NO
- Si SÍ, R3 cerrado. Solicitar R4 al operador.
- Si NO, NO pedir R4. La integración precede al tag.
```

## Cierre de R3

R3 está cerrado cuando:
- 4 UAT en VERDE (clasificadas si fueron reparadas, pero en VERDE).
- 3 gates auxiliares (detekt, apiCheck, releaseGate) en VERDE.
- Check integral 3 veces consecutivas en VERDE bajo load < 5.
- Artefactos regenerados en árbol limpio, admisión pasada, bundle actualizado.
- SHA-256 del bundle y de `git-head.txt` coinciden con HEAD.
- STATUS.md, HANDOFF-TO-CERTIFIER, ODD task sincronizados.
- ADR-0099 check pasado (SHA candidato ancestro de origin/main).
- RECEIPT-R3 emitido con la matriz de resultados.

Solo entonces se puede pedir R4 al operador (autoridad externa).
**No se inicia R5 ni se declara `PRODUCT_GATE_PASS` sin el veredicto
del certifier independiente sobre la ZIP remota exacta y sus hashes.**

## Lo que NO se hace

- No se reinterpreta rojo como verde clasificando.
- No se omite ninguna UAT de las 4 obligatorias.
- No se mueve el ZIP al bundle antes de admisión en árbol limpio.
- No se etiqueta el SHA candidato si no es ancestro de origin/main.
- No se inicia R5 sin R4 publicado.
- No se declaran `PRODUCT_GATE_PASS`, `GA` ni `PAIR_CERTIFIED` sin
  los receipts independientes exigidos.
