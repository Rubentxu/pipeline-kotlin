# R3 RUNBOOK — Plan de re-medición cuando load < 5

**Estado:** DRAFT — pendiente de ejecutar bajo load < 5.
**Generado:** 2026-10-12T21:40Z
**HEAD de referencia:** `5f0162d8` (post ODD task update)

## Prerrequisito de entorno

```
loadavg 1-min < 5
loadavg 5-min < 5
```

El umbral es el documentado en `BLOCK_E_RUN_21`. La medición
actual está en 1-min 12-17. **No correr este runbook mientras load
1-min > 5** — los resultados no son autoritativos y deben repetirse.

## Procedimiento

### 1. Baseline

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
git status -s                          # working tree limpio
git rev-parse HEAD                     # confirma SHA
date -u +%Y-%m-%dT%H:%M:%SZ            # timestamp
cat /proc/loadavg                      # confirma load < 5
```

Si la rama no está en `pk-release-0-57-consolidation@5f0162d8`,
hacer `git checkout pk-release-0-57-consolidation` y
`git pull --ff-only`.

### 2. Las 4 UAT pendientes — una por una

Cada UAT se corre individualmente. El shell captura el XML,
el exit code, los streams, y el load durante la corrida.

#### 2.1 `WURp023ObservationModesUatTest` (ya migrada)

```bash
cd v2
./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.cli.WURp023ObservationModesUatTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-wurp023.log
```

- **Qué verifica:** contrato de observación bajo R0.3: stdout line 1
  LaunchReceipt, line 2..N eventos JSON; replay del CLI events;
  cursor reconnect; contract verify con exit 0/1/2; unknown runId.
- **Migrada en:** `d443215e` (WURp023 → `CliContractTestKit` +
  `OwnedSubprocess`).
- **Fallo esperado:** el subcommand `events` puede haber cambiado
  contrato entre 0.57 y 0.56 (separación de stdout/stderr). Si
  falla, clasificar como **producto** (el subcommand cambió) o
  **protocolo** (necesita renegociar `--view events`).

#### 2.2 `UatS2R0RunOwnershipCliTest`

```bash
cd v2
./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatS2R0RunOwnershipCliTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-uat-s2-r0.log
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
  `forkEvery=1` y `maxParallelForks=1`, que ya está aplicado en
  `v2/pipeline-application/build.gradle.kts`. Si falla, clasificar
  como **entorno** (load > 5) y NO como producto.

#### 2.3 `UatLocal010SmokeE2ESandboxTest` (FAIL en rc5)

```bash
cd v2
./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatLocal010SmokeE2ESandboxTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-smoke-e2e.log
```

- **Qué verifica:** sandbox profile de extremo a extremo, con
  `deleteDir` en workspace aislado.
- **Fallo en rc5:** colisiones de checkout y aislamiento de
  workspace. El commit `a8ee7992` (v0.56.0-rc6) añadió tests
  negativos del publisher pero NO arregló el sandbox.
- **Fallo esperado:** si reproduce, clasificar como **producto**
  (defecto de aislamiento real) o **fixture** (estado global
  compartido). NO reinterpretar como PASS modificando el test.

#### 2.4 `UatLocal011WorkflowControlTest` (FAIL en rc5)

```bash
cd v2
./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.UatLocal011WorkflowControlTest" \
  --rerun-tasks 2>&1 | tee /tmp/r3-workflow-control.log
```

- **Qué verifica:** semántica de `deleteDir` especialmente en
  workspace ajeno.
- **Fallo en rc5:** `deleteDir` borra directorios fuera del
  workspace del test — defecto de seguridad.
- **Fallo esperado:** si reproduce, clasificar como **producto**
  (defecto de seguridad real, no se toca el test). Si NO
  reproduce a load < 5, clasificar como **entorno** (load > 5
  en rc5).

### 3. Check integral (3 veces consecutivas)

Solo si las 4 UAT individuales pasaron.

```bash
cd v2
for i in 1 2 3; do
  load_before=$(awk '{print $1}' /proc/loadavg)
  start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  ./gradlew --no-daemon --max-workers=1 --rerun-tasks \
    --console=plain check 2>&1 | tee /tmp/r3-check-$i.log
  rc=$?
  load_after=$(awk '{print $1}' /proc/loadavg)
  echo "run=$i start=$start rc=$rc load_before=$load_before load_after=$load_after" \
    | tee -a /tmp/r3-check-summary.txt
done
```

Criterio: las 3 corridas terminan con exit 0, sin colgarse,
con `load_during < 5` durante la corrida.

### 4. Regeneración de artefactos

Solo si el check integral pasó.

```bash
cd v2
./gradlew --no-daemon --max-workers=1 :pipeline-application:distZip

# Mover el ZIP nuevo al bundle
mv v2/pipeline-application/build/distributions/pipelinek-*.zip \
   /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/evidence/0-57-rc1/pipelinek-0.57.0.zip

# Regenerar SBOM
cd v2
./gradlew --no-daemon --max-workers=1 :pipeline-application:sbom
# (o el task que produce el CycloneDX SBOM)
```

Después regenerar `SHA256SUMS` y actualizar `STATUS.md` con el
nuevo SHA del ZIP. Re-correr el gate H1.4 (10/10 verde contra
el ZIP nuevo) y emitir `evidence/0-57-rc1/check-green-receipt.md`.

### 5. Clasificación de fallos

Para CADA fallo observado, escribir en
`evidence/0-57-rc1/r3/RECEIPT-R3.md`:

```
| Test | Fallo | Clasificación | Acción |
|------|-------|---------------|--------|
| WURp023 | <descripción> | producto/fixture/protocolo/entorno | <qué se hace> |
| UatS2R0 | ... | ... | ... |
| smoke-e2e | ... | ... | ... |
| workflow-control | ... | ... | ... |
```

Reglas de clasificación:
- **producto:** el código bajo test produce el resultado incorrecto.
  Acción: abrir issue / fix, no reinterpretar el test.
- **fixture:** el test asume un estado global que está sucio. Acción:
  arreglar el fixture, no el código bajo test.
- **protocolo:** el contrato entre emisor y consumidor cambió. Acción:
  documentar el cambio en un ADR; renegociar si es breaking.
- **entorno:** load > 5, OOM, daemon stale, etc. Acción: repetir
  la corrida a load < 5; NO marcar como PASS.

**Ningún fallo se reinterpreta como PASS.** Si un test rojo sigue
rojo, queda rojo.

### 6. Cierre de R3

R3 está cerrado cuando:
- 4 UAT individuales clasificadas (no todas necesariamente en
  VERDE — algunas pueden ser producto/fixture con issue abierto).
- Check integral 3 veces en VERDE bajo load < 5.
- Artefactos regenerados con SHAs actualizados a HEAD.
- RECEIPT-R3.md emitido con la matriz de clasificación.

Solo entonces se puede pedir la integración a `main` (R4 —
autoridad operador) y empezar a coordinar el certifier (R5).
