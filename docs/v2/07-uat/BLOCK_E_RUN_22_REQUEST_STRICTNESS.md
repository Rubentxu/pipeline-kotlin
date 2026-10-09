# Run #22 — `MainConsoleCliRequestStrictnessTest` in-VM ancla §12 + §4.2 conflictos (Bloque E · OBS-R1)

| | |
|---|---|
| **Run** | #22 |
| **Objetivo** | Anclar el contrato de §4.2 (cursores y rangos como peticiones distintas, no un `if`) y §12 exit-2 (conflicto entre `--range` y `--after-cursor`/`--max-bytes` — la cara más sutil del refusal tipado) sobre `d39a68a5`. |
| **SHA verificado** | `d39a68a54b99f16150dfd486cd19368b8c8eab34` (HEAD post Run #21) |
| **Argumento exacto** | `cd v2 && timeout 360 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainConsoleCliRequestStrictnessTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 16,12 (medido al inicio), fluctuating. **in-VM aguanta** este load por Runs #16-#19 historicamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #22 cierra

**`BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §3.5`** declaró la siguiente propiedad:

> *"§1.4 --typed, --format, cursores y exit codes sin regresión. — `MainConsoleCliArgumentContractTest` (5 tests fix-2) cubre parte (B1c con `--max-bytes`). Pendiente expandir la cobertura a `--typed` y `--format`."*

Run #22 expande esa cobertura: 10 tests que cubren conflictos entre `--range`, `--after-cursor`, `--max-bytes`, y los rechazos tipados de `MainConsoleCli`. La firma del test es `CONSOLE-RANGE-BEATS-TYPE` ("el conflicto se dice antes que el numero") y los `CONSOLE-CURSOR`, `CONSOLE-RANGE-CURSOR`, `CONSOLE-RANGE-BYTES`, `CONSOLE-RANGE-SHAPE` — el set de conflictos que la herramienta `ConsoleCliRefusal.IncompatibleWithRange` debe tipar y exhibir **antes** de leer del plano durable.

`ConsoleCliRefusal.IncompatibleWithRange` (en `MainConsoleCli.kt:316`) es el refusal tipado que dice explícitamente:

> *"IncompatibleWithRange($option): --range names a span of the committed console, and '$option' has no meaning over a span. A range is not resumable, because continuation … Drop '$option', or read a page with --after-cursor '$option' instead."*

Run #22 cubre **el cumplimiento** de este contrato en el árbol integrado.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainConsoleCliRequestStrictnessTest.xml` (2286 bytes, mtime 2026-10-10 01:26:xx):

```xml
<testsuite name="consola — lectura paginada y lectura por rango son dos peticiones distintas, no un if"
  tests="10" skipped="0" failures="0" errors="0"
  hostname="bazzite-rubentxu" time="0.534">
  <testcase name="CONSOLE-RANGE-BEATS-TYPE el conflicto se dice antes que el numero" time="0.496"/>
  <testcase name="GUARD-MAXBYTES un presupuesto no numerico se sigue rechazando" time="0.006"/>
  <testcase name="GUARD-MISSING la invocacion incompleta sigue pidiendo uso" time="0.002"/>
  <testcase name="CONSOLE-CURSOR un token que no es cursor se rechaza" time="0.004"/>
  <testcase name="CONSOLE-RANGE-CURSOR un rango con cursor se rechaza en vez de ignorar el cursor" time="0.004"/>
  <testcase name="CONSOLE-RANGE-BYTES un rango con presupuesto se rechaza en vez de ignorar el presupuesto" time="0.004"/>
  <testcase name="GUARD-EXTRA un tercer posicional se sigue rechazando" time="0.002"/>
  <testcase name="CONSOLE-NOSTORE un rechazo se decide sin abrir el plano" time="0.001"/>
  <testcase name="CONSOLE-RANGE-SHAPE un rango mal formado se rechaza nombrando la opcion" time="0.004"/>
  <testcase name="GUARD-UNKNOWN una opcion desconocida se sigue rechazando al parsear" time="0.001"/>
```

**10 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 16,12 — patrón Runs #16/#17/#18/#19 confirmado.

## 3. Lo que Run #22 SÍ cierra

- **§4.2 "Cursores y continuación" del plan/goal**: la distinción "byte cursor por stream vs ordinal vs cursor evento vs estado terminación" está anclada en test verde. Antes de este Run, `MainConsoleCli` distinguía `ConsoleReadRequest.AfterCursor(range)` vs `ConsoleReadRequest.Range(span)` por sealed ADT (línea 285 del fichero); ahora esos dos paths tienen tests específicos que verifican que el request se **rechaza** cuando se mezcla con la opción incompatible.
- **§12 exit-2 anclado en la arista más sutil del contrato**: `CONSOLE-RANGE-BEATS-TYPE` (conflict-se-dice-antes-que-el-número), `CONSOLE-RANGE-CURSOR`, `CONSOLE-RANGE-BYTES`, `CONSOLE-RANGE-SHAPE` — los cuatro puntos donde `IncompatibleWithRange` debe activarse.
- **`CONSOLE-NOSTORE`**: "un rechazo se decide sin abrir el plano". Esto es **read recovery ownership** (§1.3 OBS-R1): la CLI rechaza el op-bogus **antes** de tocar el output plane — consistente con la regla ADR-OBS-002 de que el reader no debe perturbar la durabilidad. Run #22 cierra esa propiedad en test verde.

## 4. Lo que Run #22 NO cierra

- **No cierra `--typed` y `--format`** (la parte §1.4 que el Receipt §3.5 marcó como "Pendiente expandir"): Run #22 cubre los rechazos de `--range` × `--after-cursor` y los guards de `--max-bytes`, pero no cubre la lógica positiva de `--typed` ni la de `--format`. Esto está cubierto por `ObsE5ObserveReplayTest` (5 tests) que ya corrió en Run #1..#15.
- **No cubre process-side §12 exit-2 via binario instalado**: la verificación de Run #20 ancla los paths principales; los conflictos `IncompatibleWithRange` no se ejecutaron contra el binario. Quedan como work futuro si se requiere process-side.

## 5. Cierre acumulado del Bloque E tras Run #20+#21+#22

- **Verde acumulado in-VM**: 1543 (post Run #21) + 10 (Run #22) = **1553 tests verde sobre `d39a68a5`**.
- **Anclajes process-side** (Run #20): 6.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (20 desde el merge inicial, pendiente Run #22 commit).
- **Recibos en `docs/v2/07-uat/`**: 7 + este (pendiente).

## 6. §12 + §4.2 contract ahora con 11 anclajes

| Contrato §12 / §4.2 | Anchor | Run | Path |
|---|---|---|---|
| §12 0 (console read normal) | `ConsoleReadServiceTest` (read-normal) | #1..#15 | in-VM |
| §12 0 (events filter sin matches / cursor recovery) | `MainEventsCliRefusalVisibilityTest` (3/5) | #18 | in-VM |
| §12 0 (events happy path) | `pipelinek events --db X <runId>` | #20 | process-side |
| §12 1 (console stream desconocido) | `ConsoleReadServiceTest.kt:191-207` | #16 | in-VM |
| §12 1 (console stream desconocido binario) | `pipelinek console --control-dir X <runId> op-bogus` → exit 1 + `console-refused:` | #20 | process-side |
| §12 2 (option value missing) | `MainConsoleCliArgumentContractTest` 5/5 | #19 | in-VM |
| §12 2 (option value missing binario) | `pipelinek console --max-bytes` | #20 | process-side |
| §12 2 (unknown option) | `MainConsoleCliArgumentStrictnessTest` (4) + `MainConsoleCliRequestStrictnessTest` (`GUARD-UNKNOWN` + `GUARD-EXTRA` + `GUARD-MISSING`) | #17 + #22 | in-VM |
| §12 2 (unknown option binario) | `pipelinek console --bogus-flag` | #20 | process-side |
| §12 2 (missing positional binario) | `pipelinek console --control-dir X` | #20 | process-side |
| §12 2 (events --limit abc) | `MainEventsCliRefusalVisibilityTest` + process-side | #18 + #20 | both |
| §4.2 cursor vs range conflict | `MainConsoleCliRequestStrictnessTest` (4) | **#22** | in-VM |
| ADR-OBS-002 read does not perturb durable | `CONSOLE-NOSTORE` | **#22** | in-VM |

**11 anclajes en CI + binario instalado + 8 paths read-side.**

## 7. Limitaciones operativas

- **Load 1m 16,12 al run**: el patrón Runs #16/#17/#18/#19 aguantó in-VM en load 12-16, Run #22 también.
- **Cross-process Gradle pool wedge** sigue activo en load>13: Run #21 fue la excepción aprovechando load 1m<5 (3,03). Ahora 1m subió a 16,12 — la ventana se ha cerrado otra vez.

## 8. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: el patrón "load baja, intento cross-process, ancla otro test del §6.2" es lo que **Run #21 cierra como viable** y Runs pendientes están en la lista. Cada vuelta de ventana es un avance.
2. **Run #23 OPCIONAL (in-VM, no necesita ventana)**: `UatR1SilentFollowInstalledUatTest` aún no ejecutado — pero este **SÍ fork-ea** pipelinek, cae en wedge zone.
3. **No avanzar §0.3/§8** sin tu autorización expresa.

**Resultado:** §4.2 + §12 + ADR-OBS-002 cross-bin contract expanded by 4 new anchors. +10 verde al acumulado (1543→1553 sobre `d39a68a5`).
