# Run #18 — `MainEventsCliRefusalVisibilityTest` in-VM ancla §12 events-side (Bloque E · OBS-R1 / CLI Spec §12)

| | |
|---|---|
| **Run** | #18 |
| **Objetivo** | Anclar el lado `events` de §12 (valores 0 y 2: "comando corrió y reportó" + "uso inválido") sobre `a34c40c0` con la fila de test que el `CLI_SPEC_ARBITRATION.md` final menciona explícitamente. |
| **SHA verificado** | `a34c40c0658f76e4bc9f00fe429e96dec8e3dd58` (HEAD post Run #17) |
| **Argumento exacto** | `cd v2 && timeout 360 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainEventsCliRefusalVisibilityTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 13,07 · 5m 15,62 · 15m 16,79 (medido a 01:07:51 inmediatamente antes del run; 1 proceso java/gradle ajeno en el host compartido) |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre: `git status --short` vacío) |

## 1. Lo que el audit decía y este run cierra

`CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10) §3 final, sobre el lado `events`:

> *"Si `pipelinek events` cumple la mitad de su tabla (0, 2) con la misma disciplina. Se verificó que devuelve 2 ante uso inválido; no se ejercitó el caso `0` con una página real."*

El propio KDoc del test (líneas 19-22 del fichero):

> *"S5.4 / R2 / D-M5 — la events CLI debe SHOW the refusal, y su continuación debe pasar por encima de ella. Esta es la distribución imagen, no la harness de distribución instalada (HF2)…"*

La fila concreta existe en `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainEventsCliRefusalVisibilityTest.kt`:

```kotlin
@Test fun `una fila ilegible en mitad de la historia sale en la salida y no desaparece`(...)
@Test fun `un limite que no es un entero positivo se rechaza en vez de volverse el valor por defecto`(...)
@Test fun `un filtro que no casa devuelve la historia vacia y no se equivoca de final`(...)
@Test fun `un cursor de otro run se rechaza en vez de saltarse filas`(...)
@Test fun `el cursor pasa por una fila ilegible que es la ultima en vez de atascarse antes`(...)
```

Cinco filas que cubren (a) "una ilegible a mitad de historia se imprime como rechazo y se sigue", (b) "un `--limit` no entero se rechaza (valida §12 exit-2)", (c) "un filter sin matches devuelve 0 con página vacía", (d) "cursor cross-run se rechaza", y (e) "cursor pasa por encima de la última fila ilegible en vez de quedarse atascado". El test (a) es el ancla del `events` exit-0 vs refusal — el exactamente al que el audit se refería con "no se ejercitó el caso `0` con una página real".

## 2. Resultado medido

Salida del proceso Gradle (coincide con la observación del background-task `bg_ae798e09`):

```text
> Task :pipeline-application:installDist
> Task :pipeline-application:koverFindJar
> Task :pipeline-application:runtimeClasspathCapture
> Task :pipeline-application:processTestResources
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

BUILD SUCCESSFUL in 1m 31s
84 actionable tasks: 84 executed
```

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainEventsCliRefusalVisibilityTest.xml` (1351 bytes, mtime 2026-10-10 01:09:xx):

```xml
<testsuite name="S5.4 — la CLI de eventos ve el rechazo y su cursor lo pasa"
  tests="5" skipped="0" failures="0" errors="0"
  hostname="bazzite-rubentxu" time="1.064">
```

| Test | Caso §12 / S5.4 | Tiempo |
|---|---|---|
| `una fila ilegible en mitad de la historia sale en la salida y no desaparece` | 0 / S5.4 (read normal con fila ilegible) | 0,992 s |
| `un limite que no es un entero positivo se rechaza en vez de volverse el valor por defecto` | 2 / parser refusal | 0,008 s |
| `un filtro que no casa devuelve la historia vacia y no se equivoca de final` | 0 / filter sin matches | 0,016 s |
| `un cursor de otro run se rechaza en vez de saltarse filas` | rejection / S5.4 (cursor cross-run) | 0,007 s |
| `el cursor pasa por una fila ilegible que es la ultima en vez de atascarse antes` | 0 / cursor recovery | 0,035 s |

**5 tests / 0 fallos / 0 errores / 0 skipped. Build 1m 31s bajo load 13,07-16,79.** El run no wedgea a pesar de que el load 5m/15m sigue alto. Patrón operativo Runs #16/#17/#18 confirma: in-VM en este host carga compartida aguanta con `--max-workers=1 --no-daemon`.

## 3. Cierre material del contrato §12 a cuatro puntas

| §12 valor | Comando | Caso | Anchor | Run |
|---|---|---|---|---|
| **0** | `events`, `console` | comando corrió y reportó | `MainEventsCliRefusalVisibilityTest` (filtro sin matches, fila ilegible a mitad, cursor cross-run recovery) | **Run #18** (anclado, 3 de 5 tests) + read-normal de `ConsoleReadServiceTest` |
| **1** | `console` | plano rechazó la lectura (stream desconocido) | `ConsoleReadServiceTest.kt:191-207` "an unknown operation is refused as unknown, not served as empty" | **Run #16** |
| **2** | `events`, `console` | comando NO se ejecutó: error de uso | `MainConsoleCliArgumentStrictnessTest` (4 tests) **+** `MainEventsCliRefusalVisibilityTest` (`un limite que no es un entero positivo...`) | **Run #17 + Run #18** |
| otro | (cualquiera) | excepción sin manejar = defecto | (no anclado — depende de crash no reproducido artificialmente) | — |

**§12 está anclado en test verde para los valores 0, 1, 2 (todos los que tienen salida programática verificable).** El valor "otro" sigue sin ancla formal — depende de un crash event no producido artificialmente por el harness.

## 4. Lo que Run #18 NO prueba

- **No prueba el lado process-side de `Main.kt:141` emitiendo `System.exit(1)` o `2`**: igual que Run #16/#17, este Run es in-VM; no fork-ea `pipelinek`. La traducción a exit code real sigue apoyada en la observación manual del audit `CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10) sobre el binario instalado.
- **No prueba cross-process con binario instalado sobre `a34c40c0`**: queda por ejecutar artesanalmente `pipelinek events --db <X> --limit abc <run>` y asserar `exit==2` por línea de comandos. Eso fork-ea; cae en wedge zone.
- **No amplía Run #17 (4 tests) ni Run #16 (12 tests)**: Run #18 cubre la otra mitad del comando (events) que Run #16/17 cubrían parcialmente desde el lado console.

## 5. Cierre acumulado del Bloque E tras Run #16+17+18

- **Verde acumulado**: 1515 (Run #1..#15) + 12 (Run #16) + 4 (Run #17) + 5 (Run #18) = **1536 tests verde sobre `a34c40c0`**.
- **Skipped**: 12 (sin cambios: 10 `ViolationFixture` + 2 release).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (17 desde el merge inicial): los 14 previos + `bd1e9e21` Run #16 + `a34c40c0` Run #17 + (próximo) Run #18.
- **Recibos en `docs/v2/07-uat/`**:
  1. `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones, en `c924af8c`)
  2. `BLOCK_E_RUN_16_CONSOLE_READ_SERVICE.md` (10186 bytes, en `bd1e9e21`)
  3. `BLOCK_E_RUN_17_CONSOLE_EXIT_2.md` (8023 bytes, en `a34c40c0`)
  4. **`BLOCK_E_RUN_18_EVENTS_REFUSAL_VISIBILITY.md`** (este doc, pendiente commit)

## 6. Limitaciones operativas

- **Load host 5m/15m sigue 16+**, pero Run #18 aguantó en 1m 31s — la diferencia entre fork-`pipelinek` (wedge) y pure in-VM (no wedge) se confirma **tres Runs consecutivas** #16/#17/#18. El patrón de wedge es estructural, no degradable con `--max-workers` aislado.
- **`installDist` se ejecutó como parte de la task chain** — pero ningún artefacto se etiqueta ni se tagea.

## 7. Próximo paso propuesto (no ejecutado)

1. **Run #19** (opcional): `MainConsoleCliArgumentContractTest` (5 tests, cláusulas B1c con `--max-bytes` + `--typed` rechazado) sobre `a34c40c0`. Ancla el lado `-max-bytes` que el Receipt `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §2.2` cita como cierre histórico del fix `c012d8cd` (refusal tipado `MissingOptionValue`). Mismo bounds, 6-min cap.
2. **Probar process-side con binario instalado sobre `a34c40c0`**: `pipelinek events --db <X> --limit abc <run>` → `exit==2`; `pipelinek events --db <X> --typed absent-bogus-flag <run>` → `exit==2`; `pipelinek console --control-dir <X> <run> <op-bogus>` → `exit==1`. Esto fork-ea; cae en wedge-zone. Bloqueado por host load.
3. **Sin push ni tag sobre `a34c40c0`** hasta autorización del propietario: §0.3/§8 sigue `BLOCKED_EXTERNAL`.

**Resultado:** §12 contract anclado a cuatro puntas (events 0+2, console 1+2) sobre `a34c40c0`. `BLOCKED_EXTERNAL` para cross-process fork-`pipelinek`. `BLOCKED_EXTERNAL` para la publicación. Todo lo de arriba es evidencia real contra SHA `a34c40c0` y XML fresco.
