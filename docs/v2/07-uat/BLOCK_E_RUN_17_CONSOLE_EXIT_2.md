# Run #17 — `MainConsoleCliArgumentStrictnessTest` in-VM ancla §12 exit-2 (Bloque E · OBS-R1 / CLI Spec §12)

| | |
|---|---|
| **Run** | #17 |
| **Objetivo** | Anclar el extremo process-side de §12 exit-code 2 (uso inválido) sobre `bd1e9e21` con la fila de test que el `CLI_SPEC_ARBITRATION.md` final pide. |
| **SHA verificado** | `bd1e9e21a640b41be6bc36841bfeaef13b384ac8` (HEAD de `integrate/main-obs` post Run #16) |
| **Argumento exacto** | `cd v2 && timeout 360 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainConsoleCliArgumentStrictnessTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 12,97 · 5m 18,77 · 15m 17,99 (medido a las 01:03:01, inmediatamente antes del run). |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre: `git status --short` vacío) |

## 1. Lo que el audit decía y este run cierra

`CLI_SPEC_ARBITRATION.md` (2026-10-09 20:10) §3 final:

> *"Los cuatro valores del contrato [0, 1, 2] se observan hoy sobre el binario instalado. Lo que falta es una fila de test que fije el `1` para `console` en CI, o seguirá siendo un comportamiento observado y no protegido."*

Tras Run #16, el caso `1` quedó anclado por `ConsoleReadServiceTest.kt:191-207`. Faltaba el **`2` para uso inválido**. La fila concreta vive en `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCliArgumentStrictnessTest.kt`:

```kotlin
@Test fun `CONSOLE-MAXBYTES un valor no numerico se rechaza en vez de volverse el default`(...)
@Test fun `CONSOLE-UNKNOWN una opcion desconocida se rechaza antes de tocar el disco`(...)
@Test fun `CONSOLE-EXTRA un tercer posicional se rechaza en vez de descartarse`(...)
@Test fun `CONSOLE-MISSING-ARG la invocacion incompleta sigue pidiendo uso y sale con 2`(...)
```

Los cuatro assertean `result.exitCode == 2` y verifican que el mensaje aparezca en stderr, no en stdout. Esto es **el ancla process-side de §12 exit-2** sobre el código del merge `a3b05601` y `c012d8cd` (MissingOptionValue).

## 2. Resultado medido

Salida del proceso Gradle (coincide con la observación del background-task `bg_132e3cc9`):

```text
> Task :pipeline-application:installDist
> Task :pipeline-application:koverFindJar
> Task :pipeline-application:runtimeClasspathCapture
> Task :pipeline-application:processTestResources
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

BUILD SUCCESSFUL in 1m 35s
84 actionable tasks: 84 executed
```

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainConsoleCliArgumentStrictnessTest.xml` (1174 bytes, mtime `2026-10-10 01:04:xx`):

```xml
<testsuite name="AUD-04 — el verbo console ejecuta el comando que se le escribió, o lo rechaza"
  tests="4" skipped="0" failures="0" errors="0"
  hostname="bazzite-rubentxu" time="0.531">
```

| Test | Caso §12 | Tiempo |
|---|---|---|
| `CONSOLE-MAXBYTES un valor no numerico se rechaza en vez de volverse el default` | 2 (parser refusal) | 0,483 s |
| `CONSOLE-UNKNOWN una opcion desconocida se rechaza antes de tocar el disco` | 2 (parser refusal) | 0,002 s |
| `CONSOLE-EXTRA un tercer posicional se rechaza en vez de descartarse` | 2 (parser refusal) | 0,039 s |
| `CONSOLE-MISSING-ARG la invocacion incompleta sigue pidiendo uso y sale con 2` | 2 (parser refusal) | 0,002 s |

**4 tests / 0 fallos / 0 errores / 0 skipped. Build 1m 35s bajo load 12,97-18,77.** El run no wedgea en 1m 35s a pesar de que el load 5m/15m seguía alto.

## 3. Cierre material del contrato §12 a tres puntas

| §12 valor | Comando | Caso | Test anchor | Run | Estado |
|---|---|---|---|---|---|
| **0** | `pipelinek events`, `pipelinek console` | comando corrió y reportó | `MainEventsCliRefusalVisibilityTest` (in-VM "case 0") + tests de read normal en `ConsoleReadServiceTest` | Run #1..#16 | cubierto (in-VM) |
| **1** | `pipelinek console` | plano rechazó la lectura (stream desconocido) | `ConsoleReadServiceTest.kt:191-207` *"an unknown operation is refused as unknown, not served as empty"* | Run #16 (XML 2026-10-09T22:57:01Z) | **anclado en test verde** |
| **2** | `pipelinek console`, `pipelinek events` | comando NO se ejecutó: error de uso | `MainConsoleCliArgumentStrictnessTest` (4 tests) | Run #17 (XML 2026-10-09T23:04:49Z) | **anclado en test verde** |
| **otro** | (cualquiera) | excepción sin manejar = defecto | no anclado formalmente; depende de la ruta de `Main.kt:141` no atrapada | — | no anclado en test |

**§12 está fijado en test verde para los valores 1 y 2 (los que tienen ruta negativa dedicada).** El valor 0 está cubierto por los tests de read-normal. El valor "otro" sigue sin ancla; depende de un crash event que la harness no produciría artificialmente.

## 4. Lo que Run #17 NO prueba

- **No prueba process-side `Main.kt:141` con un binario reconstruido sobre `bd1e9e21`**: el `installDist` ejecutado en este Run sí rebuildea sobre el SHA, pero no se ha hecho un test ejecutado que invoque el binario instalado contra un op desconocido y assertee `exit==1` por la línea de comandos. La semántica process-side queda fiada a la lógica de `Main.kt` y a la observación manual del `CLI_SPEC_ARBITRATION.md`.
- **No prueba cross-process con fork-`pipelinek` para los exit codes 0 y 1**: `MainEventsCliRefusalVisibilityTest` está marcado como "case 0" pero **`distribución imagen` ≠ `installed-distribution harness (HF2)`** per su propio KDoc. Eso es AAT, no UAT. Pendiente de ambiente no saturado.
- **No amplía Run #16**: Run #16 cubrió el lado read-side de `1`; Run #17 cubre el lado process-side de `2`. Son pares complementarios, no duplicados.

## 5. Cierre acumulado del Bloque E tras Run #16+17

- **Verde acumulado**: 1515 (Run #1..#15) + 12 (Run #16) + 4 (Run #17) = **1531 tests verde sobre `bd1e9e21`**.
- **Skipped**: 12 (sin cambios: 10 `ViolationFixture` + 2 release).
- **Fallos / errores**: 0 / 0.
- **Hosts**: `bazzite-rubentxu` único.
- **SHAs en `integrate/main-obs`** (15 desde el merge inicial): `a3b05601`, `066049d1`, `14c75ab9`, `a61b4872`, `8d17024e`, `1e8c04dc`, `965bc67c`, `9e95d083`, `de7ee383`, `9644a1ea`, `fb3a1e88`, `9b103394`, `0499a0d7`, `c924af8c`, **`bd1e9e21`** (este Run).
- **Recibos en `docs/v2/07-uat/`**:
  1. `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones, en `c924af8c`)
  2. `BLOCK_E_RUN_16_CONSOLE_READ_SERVICE.md` (10186 bytes, en `bd1e9e21`)
  3. **`BLOCK_E_RUN_17_CONSOLE_EXIT_2.md`** (este doc, pendiente commit)

## 6. Limitaciones operativas

- **Load host 5m/15m en 18+ durante el run, 1m cayó a 12,97.** El wedge no se reprodujo. Pero la experiencia mantiene: `--max-workers=2 + fork-`pipelinek`` siguen siendo la firma de riesgo; este test no fork-ea, por eso aguantó.
- **`installDist` no se ha ejecutado como paso de release** — se ejecutó como parte de la task chain de `:pipeline-application:test`, sin tag ni SHA pinned a Prerelease.

## 7. Próximo paso propuesto (no ejecutado)

1. **Run #18** (opcional): `MainConsoleCliArgumentContractTest` (5 tests) + `MainConsoleCliRequestStrictnessTest` (10 tests) + `MainEventsCliRefusalVisibilityTest` (5 tests) — todos in-VM, todos §12 en la zona de parser. Cierre completo del contrato §12 en la frontera de argumentos. Requiere load<15 sostenido.
2. **Probar el lado process-side con binario instalado sobre `bd1e9e21`**: `v2/pipeline-application/build/install/pipelinek/bin/pipelinek console --control-dir <X> <run> <op-bogus>` → `exit==1`, `console-refused:` por stderr. Esto sí fork-ea; cae en wedge-zone. Bloqueado por host load.
3. **Sin push ni tag sobre `bd1e9e21`** hasta autorizacion del propietario: §0.3/§8 sigue `BLOCKED_EXTERNAL`.

**Resultado:** `INTEGRATION_VERIFIED_LOCAL` para §12 anchored. `BLOCKED_EXTERNAL` para el binary cross-process. `BLOCKED_EXTERNAL` para la publicación. Todo lo de arriba es evidencia real contra `bd1e9e21` y XML fresco.
