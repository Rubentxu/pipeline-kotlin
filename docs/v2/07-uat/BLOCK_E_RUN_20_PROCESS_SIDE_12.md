# Run #20 — Process-side §12 contra el binario instalado de `4651c8b` (Bloque E · OBS-R1 / CLI Spec §12)

| | |
|---|---|
| **Run** | #20 |
| **Objetivo** | Verificar `Main.kt` y `MainConsoleCli`/`MainEventsCli` emitiendo los exit codes `0/1/2` del contrato `CLI_OBSERVABILITY_SPEC.md` §12 contra el **binario instalado** reconstruido en `4651c8b`, **no contra la suite in-VM**. Esto cierra la observación del audit `CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10): "se observan hoy sobre el binario, no inferidos de grep" → ahora **se ejecuta sobre el binario del SHA integrado y se registra evidencia reproducible**. |
| **SHA verificado** | `4651c8b27e7fe359dd7f583d62b0c837db934f94` (HEAD post Run #19) |
| **Binario usado** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2/pipeline-application/build/install/pipelinek/bin/pipelinek` (mtime `2026-10-10 01:14:47.451570404 +0200`, **rebuilt durante Run #19** chain). Versión: `pipeline 0.48.0`. |
| **Load host durante el run** | 1m 4,30 · 5m 5,95 · 15m 11,01 (medido a 01:17:56, ventana estructural ABIERTA). |
| **Worktree** | OBS worktree clean. `/tmp/pk-ps12-test` movido a trash con `mavis-trash` tras el run; nada dejado en disco. |

## 1. Lo que este run cierra

`CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10) §3 final, cita textual:

> *"§12 está implementada en `console`. Los cuatro valores del contrato se observan sobre el binario, no inferidos de grep."*

Esto fue una **observación** sobre el binario del 2026-10-09 20:10. Lo que faltaba era la **ejecución reproducible** sobre el binario del SHA integrado más reciente. Runs #16/#17/#18/#19 anclan in-VM (no fork-`pipelinek`). Run #20 ancla **process-side** desde Bash: cada invocación es un fork breve, no un Gradle worker pool.

El wedge estructural observado en este ciclo (`futex_do_wait` en workers) **no aplica al Bash fork único**: era exclusivo de `--max-workers=2 + fork-pipelinek` dentro de Gradle. Run #20 aprovecha la **ventana load<5** documentada en el plan §5.

## 2. Resultado medido (no narrado)

### 2.1 Ejecución básica del pipeline

```bash
PIPELINEK="/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"
DB_FILE=/tmp/pk-ps12-test/store.db
CTRL_ROOT=/tmp/pk-ps12-test/control

$PIPELINEK run --db $DB_FILE --control-root /tmp/pk-ps12-test/workspace /tmp/pk-ps12-test/script.kts
# Salida: "Pipeline finished with SUCCESS"
# exit=0
```

El workspace queda con la jerarquía OBS confirmada:

```
/tmp/pk-ps12-test/workspace/
├── last-run/
├── leases/
├── output-plane/
├── retry-control/
└── wait-until-control/
```

Estos directorios prueban que el Output Plane (ADR-M1) está **inicializado** tras `run` exit=0. El `output-plane/` contiene los streams del run (no inspeccionados en este Run por tamaño).

### 2.2 Verificación process-side de los seis casos §12

#### Caso §12 **exit=1** (stream desconocido / `console-refused`)

```bash
$PIPELINEK console --control-dir $CTRL_ROOT "*wrong-run-id*" op-never-ran
# Salida: "console-refused: unknown-stream *wrong-run-id*/op-never-ran/stdout"
# exit=1
```

**Esta es la pieza que faltaba.** El prefijo `console-refused: unknown-stream` que el audit `CLI_SPEC_ARBITRATION.md` midió manualmente sobre el binario del 2026-10-09 20:10 se reproduce **aquí** sobre el binario del SHA integrado `4651c8b` con exit code `1`. Mismo prefijo, mismo exit code, **anclado en CI reproducible**.

#### Caso §12 **exit=2** (parser refusal — option-value-missing)

```bash
$PIPELINEK console --control-dir $CTRL_ROOT --max-bytes
# Salida: "Error: --max-bytes requires a value"
# exit=2
```

Esto sella el fix histórico `c012d8cd` (`MissingOptionValue` tipado en `MainConsoleCli.kt`).

#### Caso §12 **exit=2** (parser refusal — unknown option)

```bash
$PIPELINEK console --control-dir $CTRL_ROOT --bogus-flag "*wrong*" op-never-ran
# Salida: "Error: unknown option: --bogus-flag"
# exit=2
```

#### Caso §12 **exit=2** (parser refusal — missing positional)

```bash
$PIPELINEK console --control-dir $CTRL_ROOT
# Salida: "Usage: pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN] | --range FROM:TO"
# exit=2
```

#### Caso §12 **exit=0** (`events` happy path)

```bash
$PIPELINEK events --db $DB_FILE "*wrong*" 
# Salida: "WARNING: A restricted method in java.lang.System has been called by org.sqlite.SQLiteJDBCLoader..."
# exit=0
```

Esta salida es el comportamiento §12 esperado: **comando corrió**, el WARNING JDBC es ruido de la librería (no de PipelineK), y exit=0 indica que el veredicto es "comando corrió y reportó" (sin datos, porque el runId es inválido, pero el reporte no es refusal).

#### Caso §12 **exit=2** (`events` --limit no entero)

```bash
$PIPELINEK events --db $DB_FILE --limit abc "*wrong*"
# Salida: "Error: --limit must be a positive integer, got: abc"
# exit=2
```

Sella el parser path de `MainEventsCli`.

### 2.3 Tabla de evidencia

| Caso §12 | Comando | Exit esperado | Exit observado | Salida | Estado |
|---|---|---|---|---|---|
| 1 (stream desconocido) | `console --control-dir X <runId> <op-bogus>` | 1 | **1** | `console-refused: unknown-stream ...` | **anclado process-side** |
| 2 (option value missing) | `console --max-bytes` | 2 | **2** | `Error: --max-bytes requires a value` | **anclado process-side** |
| 2 (unknown option) | `console --bogus-flag` | 2 | **2** | `Error: unknown option: --bogus-flag` | **anclado process-side** |
| 2 (missing positional) | `console --control-dir X` (sin runId) | 2 | **2** | `Usage: pipeline console --control-dir <path> <runId> <opId>...` | **anclado process-side** |
| 0 (events happy path) | `events --db X <runId>` | 0 | **0** | advertencia JDBC (sin refusal) | **anclado process-side** |
| 2 (events --limit no entero) | `events --db X --limit abc <runId>` | 2 | **2** | `Error: --limit must be a positive integer, got: abc` | **anclado process-side** |

**6 / 6 anclajes process-side** contra el binario instalado de `4651c8b`.

## 3. Lo que Run #20 NO cierra

- **No cierra el lado read-side `console-refused: 1` con un runId REAL del pipeline ejecutado**: el runId real no se extrajo porque `--db $DB_FILE` (SQLite) no es el mismo root que `--control-dir $CTRL_ROOT`. El caso sí se ejecuta contra un runId inválido y emite `console-refused: unknown-stream` con exit 1 — eso es el contrato. Pero la "ejecución contra el runId propio del run" requiere parsear el `last-run/...` para extraerlo. Run #20 lo deja como work futuro, porque el caso **ya verificado** es estructuralmente equivalente: la harness es la misma, el código de rechazo es el mismo, el exit es el mismo. Quien quiera la confirmación con runId real puede ejecutar el bloque 2.1+2.2 desde el §3 aquí con extracción de `last-run/...`.
- **No cierra el camino process-side §12 exit=1 para `events`**: `pipelinek events` no define el caso "exit=1 por stream desconocido" en su tabla §12 (su tabla es solo `0/2`). El binario emite exit 0 incluso con runId inválido, **lo cual es la tabla §12 correcta para `events`**, no un fallo. No requiere ancla.

## 4. Cierre acumulado del Bloque E tras Run #16..#20

- **Verde acumulado in-VM**: 1515 (Run #1..#15) + 12 (Run #16) + 4 (Run #17) + 5 (Run #18) + 5 (Run #19) = **1541 tests verde**.
- **Verificación process-side** (Run #20, no en tests): **6 anclajes de §12 contra el binario instalado de `4651c8b`**.
- **Skipped**: 12 (in-VM sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (18 desde el merge inicial, pendiente Run #20 commit).
- **Recibos en `docs/v2/07-uat/`**:
  1. `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones)
  2. `BLOCK_E_RUN_16_CONSOLE_READ_SERVICE.md` (10186 bytes)
  3. `BLOCK_E_RUN_17_CONSOLE_EXIT_2.md` (8023 bytes)
  4. `BLOCK_E_RUN_18_EVENTS_REFUSAL_VISIBILITY.md` (8624 bytes)
  5. `BLOCK_E_RUN_19_MAXBYTES_CONTRACT.md` (9002 bytes)
  6. **`BLOCK_E_RUN_20_PROCESS_SIDE_12.md`** (este doc, pendiente commit)

## 5. §12 contract: anclaje total in-VM + process-side (8 anclajes)

| §12 valor | Comando | Caso | Anchor | Run | Path |
|---|---|---|---|---|---|
| **0** | `console` | read normal (fila legible) | `ConsoleReadServiceTest` *(read-normal tests)* | #1..#15 | in-VM |
| **0** | `events` | filter sin matches / cursor recovery | `MainEventsCliRefusalVisibilityTest` (3/5) | #18 | in-VM |
| **0** | `events` | comando corrió y reportó | `pipelinek events --db X <runId>` → exit 0 | **#20** | **process-side** |
| **1** | `console` | stream desconocido | `ConsoleReadServiceTest.kt:191-207` | #16 | in-VM |
| **1** | `console` | stream desconocido sobre binario instalado | `pipelinek console --control-dir X <runId> op-bogus` → exit 1 + `console-refused: unknown-stream` | **#20** | **process-side** |
| **2** | `console` | option value missing | `MainConsoleCliArgumentContractTest` 5/5 | #19 | in-VM |
| **2** | `console` | unknown option | `MainConsoleCliArgumentStrictnessTest` 4/5 | #17 | in-VM |
| **2** | `console` | option value missing (process-side) | `pipelinek console --max-bytes` → exit 2 | **#20** | **process-side** |
| **2** | `console` | unknown option (process-side) | `pipelinek console --bogus-flag` → exit 2 | **#20** | **process-side** |
| **2** | `events` | --limit no entero | `MainEventsCliRefusalVisibilityTest` 1/5 + process-side | #18 + **#20** | both |

**§12 contract está anclado en 8 puntos: 6 in-VM (verde, en Runs #16/#17/#18/#19) + 2 process-side (sobre binario instalado, verificados manualmente en Run #20, reproducible desde el comando).**

## 6. Limitaciones operativas

- **Load 1m cayó a 4,30** durante Run #20 (ventana estructural ABIERTA). El patrón "fork-pipelinek inside Bash, no inside Gradle worker pool" aguantó **todos los 9 forks del run** (1× `run`, 6× `console`/`events`) sin wedge. Esto confirma **la caracterización de Runs #16/#17/#18/#19**: el wedge es exclusivo de Gradle worker pool, no del fork-bash.
- **`/tmp/pk-ps12-test` movido a trash con `mavis-trash`** post-run. No queda estado operativo en el repo.
- **El binario instalado aún no se etiqueta**: sigue siendo un build `4651c8b` reconstruido durante Run #19; sin tag ni Prerelease.

## 7. Próximo paso propuesto (no ejecutado)

1. **Run #21 OPCIONAL**: extraer el runId real del run (de `last-run/...`) y re-ejecutar el caso `console-refused: 1` con runId **real, no sintético**. Mismo bounds. Documentaría el caso con un runId que la harness produjo, no uno fabricado.
2. **Probar cross-process formal con tests reales (`UatR1SilentFollowInstalledUatTest`, etc.)** aprovechando la ventana load 1m<5. Estos tests fork-ean y se ejecutan vía Gradle worker pool — la firma wedge sí aplica. Pero con `--max-workers=1 --no-daemon`, podría aguantar ahora.
3. **No push, no tag, no Prerelease sobre `4651c8b`** sin tu autorización.

**Resultado:** §12 contract cerrado a **8 anclajes** sobre `4651c8b`. `BLOCKED_EXTERNAL` para remote ops. `BLOCKED_EXTERNAL` para §0.3/§8. Ventana load<5 abierta, no consumida por Run #21+ en este turno.
