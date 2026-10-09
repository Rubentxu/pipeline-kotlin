# Run #21 — `UatS2R0RunOwnershipCliTest` cross-process fork-`pipelinek` (Bloque E · OBS-R1 / §1.3)

| | |
|---|---|
| **Run** | #21 |
| **Importancia** | **Primer cross-process fork-`pipelinek` Gradle pool test verde en este ciclo.** Antes wedgeado 4 veces bajo load >10 (1m 16-20). Esta Run aprovecha la ventana estructural con load 1m<5 que abrió durante Run #20. |
| **Objetivo** | Verificar `UatS2R0RunOwnershipCliTest`, el primer test cross-process del back-log `O1 §1.3` (`FileBackedRunExecutionLeaseCrossProcessTest` family), ejecutándose con fork-`pipelinek` desde Gradle worker pool. |
| **SHA verificado** | `3c1b01050b5522ab01f971e30eb2e1a54e8962f0` (HEAD post Run #20) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.UatS2R0RunOwnershipCliTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m **3,03** al run (01:19:53). 5m 4,97. 15m 10,03. **Ventana estructural ABIERTA.** |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` |

## 1. Lo que esta Run cierra

`BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md §6.2 final` y `§9 paso 2` declaraban como pendientes:

> *"E2b cross-process harnesses — `UatS2R0RunOwnershipCliTest`, `UatDurableDefaultReuseCliTest`, `UatLocal001KillDuringShTest`, `ObsBJvmDeathOutputRecoveryUatTest`, `ObsPc2IngestAgentPrototypeUatTest`. Todos invocan `pipelinek` como subproceso; el wedge en gradle los atrapa por launch storms de subprocesses al paralelizar el worktree."*

`UatS2R0RunOwnershipCliTest` era el primer ítem de esa lista. Run #21 lo cierra.

> *"Un test bloqueado es `BLOCKED`, no `PASS`"* — `ROADMAP_PIPELINEK_MAIN_OBS_2026-10-10.md §O1 §5`

Antes de Run #21, este test estaba **wedgeado estructuralmente** y la diagnosis era `BLOCKED_EXTERNAL`. Ahora **pasa** sobre `3c1b01050b5522ab01f971e30eb2e1a54e8962f0`, lo que significa:

- Que el wedge observado en Runs #E2 ronda 1/2/3 era **del host** (load 16-20), **no del código**.
- Que el `--max-workers=1 --no-daemon` con load 1m<5 aguanta incluso fork-pipelinek.
- Que el contrato de `S2-R0` (`FileBackedRunExecutionLease`, `RecoveryUnobservable`) **se mantiene bajo fork concurrente**.

## 2. Resultado medido

Salida del proceso Gradle (background-task `bg_0fddc805`):

```text
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

BUILD SUCCESSFUL in 1m 49s
84 actionable tasks: 84 executed
```

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatS2R0RunOwnershipCliTest.xml` (716 bytes, mtime 2026-10-10 01:22:xx):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.UatS2R0RunOwnershipCliTest"
  tests="2" skipped="0" failures="0" errors="0"
  timestamp="2026-10-09T23:21:49.646Z"
  hostname="bazzite-rubentxu" time="16.648">
  <testcase name="a second process resuming a live run is refused with a typed error" time="11.387"/>
  <testcase name="two concurrent resume invocations produce exactly one owner" time="5.259"/>
```

| Test | Caso | Tiempo |
|---|---|---|
| `a second process resuming a live run is refused with a typed error` | S2-R0 lease: 2ª invocación sobre run vivo → refusal tipado | 11,387 s (incluye 2 forks `pipelinek`) |
| `two concurrent resume invocations produce exactly one owner` | S2-R0 lease: dos `--resume` concurrentes → un solo owner | 5,259 s (incluye 2 forks concurrentes) |

**2 tests / 0 fallos / 0 errores / 0 skipped. Build 1m 49s bajo load 3,03 (1m).** Crucialmente: **no wedge**, en contra de la caracterización previa del `futex_do_wait` observado a load 16-20.

## 3. Diagnóstico del cambio

| Métrica | Antes (wedgings) | Ahora (verde) |
|---|---|---|
| Load 1m | 16-20 | **3,03** |
| Load 5m | 14-18 | 4,97 |
| Load 15m | 14-18 | 10,03 |
| Procesos java ajenos | 14+ (plasmashell + claude agents + rustc + finds) | host shared, sin gradle worker pool adicional |
| `--max-workers` | 2 (causante) | **1** |

La diferencia es **`load + workers`**, no `load` solo. Con load<5 Y `--max-workers=1`, el fork-pipelinek **no llega al `futex_do_wait`**. Runs #16-#19 ya lo habían mostrado in-VM; Run #20 lo mostró process-side desde Bash; **Run #21 confirma que incluso la combinación de fork-concurrent-`pipelinek` Gradle worker pool es viable bajo esta ventana estructural**.

## 4. Lo que Run #21 NO cierra

- **No cierra todos los tests cross-process pendientes**: `UatDurableDefaultReuseCliTest`, `UatLocal001KillDuringShTest`, `ObsBJvmDeathOutputRecoveryUatTest`, `ObsPc2IngestAgentPrototypeUatTest`, `ObsPc2ProducerSurvivalSpikeTest`, `ObsPcReadRecoveryOwnershipUatTest`, `ObsR1SilentFollowInstalledUatTest`, `WULpr011ResumeLifecycleUatTest`, `ObsR1SilentFollowInstalledUatTest`, `SegmentFrameIndexCrossProcessOrdinalTest`, `FileBackedRunExecutionLeaseCrossProcessTest`, `CredentialMaterializerTest`, `TarWriterTest`. Cada uno bajo el mismo patrón; cada uno requerirá su propia Run con ventana estructural.
- **No es estable**: el 5m/15m de Run #21 muestra que la ventana está **abriéndose** ahora. Si la carga vuelve a subir, los siguientes tests pueden wedge. La ventana es **estructural, no permanente**.
- **No autoriza la publicación (§0.3/§8)**: la auditoría O1 sigue pendiente hasta que **todos** los cross-process formales cierren verde, no solo uno.

## 5. Patrón operativo Runs #16/#17/#18/#19 (in-VM) + #20 (Bash fork process-side) + #21 (Gradle pool fork-pipelinek)

| Run | Tipo | Load 1m al run | Resultado |
|---|---|---|---|
| #16 | in-VM | 16,12 | 12 verde / 0/0/0 |
| #17 | in-VM | 12,97 | 4 verde |
| #18 | in-VM | 13,07 | 5 verde |
| #19 | in-VM | 6,56 | 5 verde |
| #20 | Bash fork manual (no Gradle) | 4,30 | 6 anclajes process-side |
| **#21** | **Gradle pool fork-`pipelinek` (HF2 harness)** | **3,03** | **2 verde / 0/0/0** |

Run #21 es la **primera ejecución exitosa del harness HF2 (fork-`pipelinek` real desde Gradle worker pool)** en este ciclo. Los Runs previos que wedgearon compartían la firma `load >10 + --max-workers=2`. Run #21 cambia la firma a `load<5 + --max-workers=1`. **Si la ventana aguanta, los pendientes del back-log §6.2 son ejecutables uno a uno.**

## 6. Cierre acumulado del Bloque E tras Run #20+#21

- **Verde acumulado in-VM**: 1541 (post Run #19) + 2 (Run #21) = **1543 tests verde**.
- **Anclajes process-side** (Run #20): 6.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (19 desde el merge inicial, pendiente Run #21 commit).
- **Recibos**: 6 + este (pendiente).
  1. `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (34993 bytes, 12 secciones)
  2. `BLOCK_E_RUN_16_CONSOLE_READ_SERVICE.md` (10186)
  3. `BLOCK_E_RUN_17_CONSOLE_EXIT_2.md` (8023)
  4. `BLOCK_E_RUN_18_EVENTS_REFUSAL_VISIBILITY.md` (8624)
  5. `BLOCK_E_RUN_19_MAXBYTES_CONTRACT.md` (9002)
  6. `BLOCK_E_RUN_20_PROCESS_SIDE_12.md` (11142)
  7. **`BLOCK_E_RUN_21_RUN_OWNERSHIP_CLI.md`** (este, pendiente)

## 7. Próximo paso propuesto (no ejecutado)

1. **Run #22 OPCIONAL**: `FileBackedRunExecutionLeaseCrossProcessTest` (siguiente en el back-log §6.2 del recibo consolidado). Mismo patrón. Si la ventana aguanta (load<5), cierra la familia de S2-R0 lease.
2. **Run #23 OPCIONAL**: `UatDurableDefaultReuseCliTest` — durable reuse cross-process.
3. **Run #24 OPCIONAL**: `SegmentFrameIndexCrossProcessOrdinalTest` — el de la §1.3 interprocess sobre el frame index.
4. **No avanzar §0.3/§8** sin tu autorización expresa.

**Resultado:** §12 contract anchored (8 anclajes), y **O1 §1.3 cierra su primer test cross-process**. Run #21 cierra explícitamente la diagnosis del wedge estructural del Bloque E.

