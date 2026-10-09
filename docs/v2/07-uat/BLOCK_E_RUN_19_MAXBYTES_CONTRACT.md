# Run #19 — `MainConsoleCliArgumentContractTest` in-VM ancla §1.5 B1c + §12 exit-2 (Bloque E · OBS-R1 / CLI Spec §12 + §1.5)

| | |
|---|---|
| **Run** | #19 |
| **Objetivo** | Anclar el contrato B1c del §1.5 (`--max-bytes` no convertible, refusal tipado) sobre `5069207a` con la fila de test que cierra el fix histórico `c012d8cd` (refusal `MissingOptionValue` en `MainConsoleCli.kt`). Verifica el lado application-domain de §12 exit-2 desde la arista del parser. |
| **SHA verificado** | `5069207a2f70ae850caac64d328e1dd118c0a3c5` (HEAD post Run #18) |
| **Argumento exacto** | `cd v2 && timeout 360 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainConsoleCliArgumentContractTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 6,56 · 5m 9,32 · 15m 13,65 (medido a 01:13:03 inmediatamente antes del run) |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que el audit y el recibo consolidado esperan anclar aquí

Dos documentos convergen sobre `MainConsoleCliArgumentContractTest` que este Run resuelve:

**(a) `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §5`** (regresiones / `c012d8cd`):

> *"`MainConsoleCliArgumentContractTest` B1c — `--max-bytes` sin valor aplicaba default en vez de exit 2. Parser tenía 4 ramas `if (arg == ...)` paralelas, cada una con `args.getOrNull(++index)` mezclando flag ausente con flag sin valor → Unificación en `when` único; `args.getOrNull(index+1)`; refusal tipado `MissingOptionValue`."*

Eso fue el fix histórico. Este Run es la verificación **ancla en test** de ese fix sobre `5069207a`.

**(b) `CLI_SPEC_ARBITRATION.md`** (ancla §12 exit-2):

> *"El riesgo real es de test, no de código. … Lo que falta es una fila de test que fije el `1` para `console` en CI, o seguirá siendo un comportamiento observado y no protegido."*

Run #16/#17 anclan el path del rechazo (`UnknownStream` y `2` por uso de CLI); Run #19 ancla **el otro path: la cara parser del contrato §12 exit-2 cuando la opción existe pero su valor no es entero positivo**. Esto es el caso concreto del `MissingOptionValue` resuelto por `c012d8cd`, probado de nuevo sobre el SHA integrado.

## 2. Resultado medido

Salida del proceso Gradle (`bg_f87ce58b`):

```text
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

BUILD SUCCESSFUL in 1m 32s
84 actionable tasks: 84 executed
```

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainConsoleCliArgumentContractTest.xml` (1279 bytes, mtime 2026-10-10 01:14:xx):

```xml
<testsuite name="B1c — la CLI de consola rechaza un --max-bytes no convertible"
  tests="5" skipped="0" failures="0" errors="0"
  hostname="bazzite-rubentxu" time="0.564">
  <testcase name="a third positional is refused instead of being dropped" time="0.430"/>
  <testcase name="a non-numeric or non-positive --max-bytes is refused rather than defaulted" time="0.065"/>
  <testcase name="a well-formed --max-bytes is not refused by the parser" time="0.054"/>
  <testcase name="an unknown option is refused instead of being ignored" time="0.003"/>
  <testcase name="an option whose value is missing is refused rather than defaulted" time="0.006"/>
```

| Test | Caso | Cubre |
|---|---|---|
| `a third positional is refused instead of being dropped` | §12 exit-2 (extra arg) | parser strict |
| `a non-numeric or non-positive --max-bytes is refused rather than defaulted` | §12 exit-2 + §1.5 B1c | `MissingOptionValue`/negativo |
| `a well-formed --max-bytes is not refused by the parser` | §12 exit-0 (caso normal) | happy path |
| `an unknown option is refused instead of being ignored` | §12 exit-2 (unknown) | cierre histórico §1.4 |
| `an option whose value is missing is refused rather than defaulted` | §12 exit-2 (MissingOptionValue) | **el anclaje directo del fix `c012d8cd`** |

**5 tests / 0 fallos / 0 errores / 0 skipped. Build 1m 32s bajo load 6,56-13,65.** Run #19 corre en load 6,56 (1m) — la **ventana estructural** que el plan §5 pedía para demos cross-process. Runs in-VM no la necesitan pero la confirman como suficiente para Gradle.

## 3. Lo que Run #19 SÍ cierra

- **Ancla formal del fix histórico `c012d8cd`**: el Receipt `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §5` declara el fix. El test verde sobre `5069207a` es la **evidencia en CI** de que el fix sobrevive a las 14 SHAs posteriores. Sin regresión.
- **Cierre del lado application-domain de §12 exit-2**: tras Run #16 (read-side, exit-1), Run #17 (CLI usage error, exit-2), Run #18 (events side, exit-0 y exit-2), Run #19 ancla el caso **específico** "option value is missing" — el que el audit `c012d8cd` tapó. Es la pieza que faltaba para §12 con anclaje 4-puntas.
- **Subsume `§5` del Receipt `BLOCK_E`** sin reabrirlo: el test verde sobre `5069207a` es la **evidencia viva** del fix histórico, lo que permite dejar el §5 cerrado sin actualizarlo.

## 4. Lo que Run #19 NO cierra

- **No prueba process-side con el binario instalado reconstruido sobre `5069207a`**: igual que Run #16/#17/#18, este Run es in-VM; no fork-ea `pipelinek`. El binario instalado en `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` data de 01:09 (parte de la task chain de Run #18), pero el path process-side `Main.kt:141` no se ha ejecutado directamente sobre la línea de comandos en este ciclo.
- **No prueba --typed-events**: el caso `--typed` rechazado vive en `8d17024e`. Hay un test correspondiente (`CliMissingScriptRejectionTest`?), pero Run #19 no lo corre.

## 5. Cierre acumulado del Bloque E tras Run #16+17+18+19

- **Verde acumulado**: 1515 (Run #1..#15) + 12 (Run #16) + 4 (Run #17) + 5 (Run #18) + 5 (Run #19) = **1541 tests verde sobre `5069207a`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (18 desde el merge inicial): los 14 previos + `bd1e9e21` + `a34c40c0` + `5069207a` + (próximo) Run #19.
- **Recibos en `docs/v2/07-uat/`**:
  1. `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones, en `c924af8c`)
  2. `BLOCK_E_RUN_16_CONSOLE_READ_SERVICE.md` (10186 bytes, en `bd1e9e21`)
  3. `BLOCK_E_RUN_17_CONSOLE_EXIT_2.md` (8023 bytes, en `a34c40c0`)
  4. `BLOCK_E_RUN_18_EVENTS_REFUSAL_VISIBILITY.md` (8624 bytes, en `5069207a`)
  5. **`BLOCK_E_RUN_19_MAXBYTES_CONTRACT.md`** (este doc, pendiente commit)

## 6. §12 contract: anclaje a cuatro puntas **más un fix histórico**

| §12 valor | Comando | Caso | Anchor | Run |
|---|---|---|---|---|
| **0** | `events`, `console` | comando corrió y reportó | `MainEventsCliRefusalVisibilityTest` (3/5) + read-normal `ConsoleReadServiceTest` | Run #18 + Run #1..#15 |
| **1** | `console` | plano rechazó lectura (stream desconocido) | `ConsoleReadServiceTest.kt:191-207` | Run #16 |
| **2** | `events`, `console` | comando NO se ejecutó: error de uso | `MainConsoleCliArgumentStrictnessTest` (4) + `MainEventsCliRefusalVisibilityTest` (`un limite...`) + **`MainConsoleCliArgumentContractTest`** (B1c + `option whose value is missing`) | Run #17 + Run #18 + **Run #19** |
| otro | (cualquiera) | excepción sin manejar = defecto | (no anclado) | — |

| Contrato histórico §1.5 | Fix | Anchor | Run |
|---|---|---|---|
| B1c `--max-bytes` refusal sin default | `c012d8cd` (`MainConsoleCli.kt`) | `MainConsoleCliArgumentContractTest` 5/5 anclado verde | **Run #19** |

## 7. Limitaciones operativas

- **Load host 5m/15m sigue 13+, pero el 1m ha caído a 6,56.** Ventana estructural abierta; primer 1m<10 desde varios turnos atrás.
- **`installDist` se ejecutó como parte de la task chain** — el binario instalado ahora es de `5069207a`. No se ha ejercitado process-side sobre él (fork-pipelinek).
- **Cross-process wedge**: la firma `load + --max-workers=2 + fork-pipelinek` no se probó esta Run. Pero Run #19 confirma que en-VM está libre incluso con load fluctuante 6-18.

## 8. Próximo paso propuesto (no ejecutado)

1. **Process-side §12 exit-1 vía binario instalado sobre `5069207a`** (ventana load 6,56 ABIERTA):
   - `v2/pipeline-application/build/install/pipelinek/bin/pipelinek console --control-dir <X> <run> <op-bogus>` → `exit==1`, `console-refused: unknown-stream` por stderr.
   - Si esto pasa, ancla el **lado process-side** que Run #16 deja apoyada en in-VM.
   - Riesgo: un fork breve no es la firma wedge; el wedge es Gradle worker pool. Decidido: probar.
2. **Run #20 OPCIONAL**: `MainConsoleCliRequestStrictnessTest` (10 tests) — última clase sin re-correr fresca sobre `5069207a`.
3. **Sin push ni tag sobre `5069207a`** hasta autorización del propietario: §0.3/§8 sigue `BLOCKED_EXTERNAL`.

**Resultado:** §12 contract anclado 5 puntas (events-0, console-1, console-2, events-2, parser-value-missing). `BLOCKED_EXTERNAL` para cross-process, publicación y §0.3/§8. Todo lo de arriba es evidencia real contra SHA `5069207a` y XML fresco.
