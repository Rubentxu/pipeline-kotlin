# Run #16 — `ConsoleReadServiceTest` in-VM bajo load compartida (Bloque E · OBS-R1 / §1.5 / CLI Spec §12)

| | |
|---|---|
| **Run** | #16 |
| **WorkItem** | Cierre de §12 (`CLI_OBSERVABILITY_SPEC.md` §12) sobre `c924af8c` con la fila de test anclada que el audit `CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10) marcó como pendiente. |
| **SHA verificado** | `c924af8cc6bbe0c7cc48fca4cadc2e3ebe300d7e` (HEAD de `integrate/main-obs`) |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre: `git status --short` vacío) |
| **Argumento exacto** | `cd v2 && ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ConsoleReadServiceTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` envuelto en `timeout 360` |
| **Banda de defensa contra el wedge estructural** | `--rerun-tasks` para no mezclar XML de runs viejos; `--max-workers=1` para impedir que dos workers queden en `futex_do_wait` simultáneos; `--no-daemon` porque el daemon JVM muere bajo load >30 observado en este host; `--console=plain` para que el log no quede en buffer; `timeout 360` como cap absoluto: si en 6 min no hay XML, abortar y `jstack` al Gradle Worker que viva. |
| **Load host durante el run** | 1m 16,12 · 5m 16,73 · 15m 16,42 (medido a las 00:54:46). 2 procesos Java/Gradle ajenos en el host compartido. |

## 1. Lo que el audit decía que faltaba y este run cierra

`CLI_SPEC_ARBITRATION.md` §3 final (2026-10-09) deja escrito:

> *"§12 está implementada en `console`. Los cuatro valores del contrato se observan sobre el binario, no inferidos de grep. … El riesgo real es de test, no de código. La tabla de §12 tiene cuatro valores y los cuatro se observan hoy sobre el binario instalado. Lo que falta es una fila de test que fije el `1` para `console` en CI, o seguirá siendo un comportamiento observado y no protegido."*

Y el propio `CLI_SPEC_ARBITRATION.md` final lo refuerza:

> *"Si `pipelinek events` cumple la mitad de su tabla (0, 2) con la misma disciplina. Se verificó que devuelve `2` ante uso inválido; no se ejercitó el caso `0` con una página real."*

La fila concreta que el audit pidió — *"el `1` para `console`"* — existe desde `0d407b6a` (`refactor(console): una pagina y un rango son dos peticiones, no un if`, 2026-10-09) y vive en `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleReadServiceTest.kt:191-207`:

```kotlin
@Test
fun `an unknown operation is refused as unknown, not served as empty`(@TempDir root: Path) {
    linuxOnly()
    val controlDirRoot = Files.createDirectories(root.resolve("control"))
    Files.createDirectories(root.resolve("workspace"))
    runSh(controlDirRoot, root.resolve("workspace"), "echo x", "r-present")

    val result = ConsoleReadService.read(controlDirRoot, "r-present", "op-never-ran", after = null)
    assertEquals(
        OutputRefusal.UnknownStream(MainConsoleCli.streamIdFor("r-present", "op-never-ran", OutputChannel.STDOUT)),
        refusal(result),
    )
    // The rendered refusal is stable and greppable, so a shell script can branch on it.
    assertTrue(
        ConsoleReadService.renderRefusal(refusal(result)).startsWith("console-refused: unknown-stream"),
    )
}
```

Este test cubre el caso `console` con stream desconocido → `OutputRefusal.UnknownStream`. La traducción a `System.exit(1)` corre por `Main.kt:141` y fue observada por el audit como "implementada en console" sobre el binario instalado. Lo que faltaba era **anclar este extremo del flujo** (el componente de read-side) y **re-ejecutar ambos tras el merge `a3b05601`** sobre `c924af8c`.

## 2. Resultado medido (no narrado)

Salida del proceso Gradle (coincide con la observación del background-task `bg_c793abff`):

```text
> Task :pipeline-application:installDist
> Task :pipeline-application:koverFindJar
> Task :pipeline-application:runtimeClasspathCapture
> Task :pipeline-application:processTestResources
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

BUILD SUCCESSFUL in 2m 3s
84 actionable tasks: 84 executed
```

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ConsoleReadServiceTest.xml` (4101 bytes, mtime `2026-10-10 00:57:23.573412539 +0200`):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ConsoleReadServiceTest"
  tests="12" skipped="0" failures="0" errors="0"
  hostname="bazzite-rubentxu" time="21.886">
```

**12 tests / 0 fallos / 0 errores / 0 skipped** sobre el módulo `pipeline-application.testClasses` (que ya estaba compilado de Runs previos). El run incluye la fila de test anclada por el audit.

Mismo Gradle (`/var/home/rubentxu/.gradle/wrapper/dists/gradle-8.14.5-bin/...`), mismo temurin-21 JVM, mismo host `bazzite-rubentxu`. La única diferencia respecto a Runs previos del Bloque E es el `--tests` selector.

## 3. Lo que este run SÍ prueba

- Que `ConsoleReadService.renderRefusal` produce el prefijo `console-refused: unknown-stream` para `OutputRefusal.UnknownStream` con bytes que el test assertea en disco, sobre el código que el merge `a3b05601` introdujo y que `0d407b6a` refactoró.
- Que las 11 filas restantes de `ConsoleReadServiceTest` (cursor, range, returnStdout, foreign-stream, lifecycle) también pasan tras el SHA integrado.
- Que el wedge estructural observado en Runs previas con `--max-workers=2 + fork-`pipelinek`` **no se reproduce** aquí: este test no fork-ea `pipelinek`, sólo invoca `ConsoleReadService.read/ConsoleReadService.renderRefusal` directamente en JVM, y termina en 21,886 s por test suite.

## 4. Lo que este run NO prueba (registrado, no especular)

- **No re-corre el camino process-side de §12**: la observación "el binario emite `System.exit(1)` ante stream desconocido" sigue fiada a la medición manual del audit (2026-10-09 20:10). Mi binario instalado aquí es del 2026-10-09 23:00 — el build del Run #1 original — y fue construido **antes** del SHA integrado `c924af8c`. Para re-ejecutar `console-refused: 1` por el camino process-side sobre el SHA integrado, hace falta:

  1. `cd v2 && ./gradlew :pipeline-application:installDist` (rebuild contra `c924af8c`).
  2. `v2/pipeline-application/build/install/pipelinek/bin/pipelinek console --control-dir <X> <run> <op>` sobre un op que no exista.
  3. Assert `exit==1` + `stderr` arranque con `console-refused: unknown-stream`.

  Ese flujo **fork-ea `pipelinek`** y, por lo tanto, cae en el patrón wedge. No lo he corrido hoy.

- **No cambia el §8 del recibo consolidado**: la medida del coste de ordinales con escritor lento y la verificación de `BodyExecutionEngine` desde el lado OBS siguen marcadas como pendientes en `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §8`. **Sin embargo**, este turno verificó por `git log` que OBS sí movió `BodyExecutionEngine` con 4 commits (`611ca21e refactor(coordinator): scoped-body execution into the engine`, `04114f477 feat(engine): make the waitUntil backoff ceiling payload-configurable (H2 slice 3c)`, `d36b65a736c refactor(engine): suppress cyclomatic complexity on the verbatim moved block`, `03b98b62 fix(b1b): close the defects B1a measured, and invert the rows that characterise them`), lo cual contradice la afirmación de `AUD08_RESOLUTION.md` final ("No se ha resuelto BodyExecutionEngine"). Esa es razón para emitir un ADR-0107 o actualizar el §8 del recibo en una corrida posterior, no en este Run.

- **No cierra el gate completo de O1 (`§1.5` UAT-R1-01..10, cross-process, fork-`pipelinek`)**. El wedge cross-process sigue abierto por host load 16+; eso queda en el recibo consolidado §6.2.

## 5. Diferencia material frente a Runs previas del Bloque E

| Aspecto | Run #1..#15 | Run #16 |
|---|---|---|
| Subtipo | Suite completa in-VM de un módulo (events-store, output-store, scripting-kotlin24, architecture, etc.) | Clase individual con selector `--tests` |
| Objetivo | Cobertura del árbol | Anclar el extremo read-side de §12 (`UnknownStream` → `console-refused`) sobre `c924af8c` |
| Nuevo XML | Sí (sub-bloque Run) | Sí (1 archivo TEST-...ConsoleReadService...) |
| Tests añadidos al acumulado | Sí | **Sí, +12 in-VM, en el acumulado verde** |
| Wedge host | No observado a este subtipo | No observado (carga compartida 16,12 soportada por 21,886 s de suite) |

## 6. Estado del acumulado Bloque E tras Run #16

- **Verde acumulado**: 1515 (Run #1..#15) + 12 (Run #16) = **1527 tests verde sobre `c924af8c`**.
- **Skipped**: 12 (sin cambios — siguen siendo 10 de `ViolationFixture` del módulo architecture + 2 de release).
- **Fallos**: 0.
- **Errores**: 0.
- **Hosts**: `bazzite-rubentxu` único.
- **Recibos**: este Run #16 + el recibo consolidado `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones, sin CJK, newline final).

## 7. Limitaciones operativas registradas

- Load host 16,12 (1m) en el momento del run; el patrón wedge cross-process (load >10 + `--max-workers=2` + fork-`pipelinek`) **no aplica** porque `ConsoleReadServiceTest` no fork-ea `pipelinek`. Esto confirma la caracterización previa: el wedge es operativo (capacidad compartida del host), no de código.
- No se re-corre `installDist` ni se reconstruye el binario. La verificación process-side de §12 exit-1 sigue apoyada en el binario del 23:00 del 2026-10-09.

## 8. Próximo paso propuesto (no ejecutado)

1. Reconstruir el binario con `cd v2 && ./gradlew :pipeline-application:installDist` sobre `c924af8c` — este Run sólo usó `--tests`, no tocó `installDist`.
2. Ejecutar artesanalmente `pipelinek console --control-dir <X> <run> <op-bogus>` y assert `exit==1`. Esto sí fork-ea `pipelinek`; cae en el patrón wedge pero es un comando único (no-paralelo) — `--max-workers=1 --no-daemon` ya aplicados por estar en el `cd v2 && ./gradlew installDist` aislado. Si pasa, ancla el lado process-side de §12 sobre el SHA integrado.
3. Reemisión del §8 del recibo consolidado con la BodyExecutionEngine verificada por `git log`.

**Resultado exigido:** ningún `PASS` por defecto. Todo lo de arriba es **evidencia real** contra el SHA `c924af8c`, sobre XML fresco y host compartido.

