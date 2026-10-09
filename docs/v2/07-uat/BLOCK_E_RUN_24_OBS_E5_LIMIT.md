# Run #24 — `ObsE5LimitTest` in-VM ancla §1.5 OBS-E5d `--limit` budget (Bloque E · OBS-R1 / OBS-E5d)

| | |
|---|---|
| **Run** | #24 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5d** (`--limit`: un budget sobre los registros **seleccionados**, no escaneados, no clamped, no advisory, **no negociable con `run`**). El KDoc del test documenta explícitamente 4 mutaciones medidas y qué filas vuelcan cada una — esto es HF5 puro, no caracterización muda. |
| **SHA verificado** | `4953fdc6e30bba067d468c627c78dcb5d3bee3ac` (HEAD post Run #23, mismo árbol — test OBS-merge tracked en `9fdf932e`) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5LimitTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 14,87 (al inicio Run #23), 1m ~32 (al cierre, fluctuating). **in-VM aguanta** este load por Runs #16-#24 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `9fdf932e`, sin nuevos cambios en el código) |

## 1. Lo que Run #24 cierra

El KDoc del test class —`ObsE5LimitTest.kt:29-62`— declara:

> *"A limit is not a filter and must not behave like one. It counts what the reader SHOWED, so `--stage build --limit 5` shows five records matching `--stage build` whether the run committed five or ten thousand — a budget spent on rejected records would make the number a second, invisible filter, and the same five lines would appear for queries with wildly different selectivity.
>
> And a follower that has spent its budget has not finished: it did not see a terminal fact and nobody stopped it. Reporting either of the two existing reasons would tell a caller the run ended, or that we gave up, when we were told exactly how much to show."*

Las firmas:

| Test | Cubre la propiedad OBS-E5d | Mutación que RED | Resultado medido |
|---|---|---|---|
| `LIMIT-1 the event lane stops after N emitted records` | El event-lane corta tras `N` registros **emitidos**, no tras `N` escaneados | M-L4 (presentation ignores budget) — RED | verde, 0.002s |
| `LIMIT-2 the budget counts selected records, not scanned ones` | El contador opera sobre `selected`, no sobre `scanned` | M-L1 (counts scanned) — RED **LIMIT-2 alone** | verde, 0.004s |
| `LIMIT-3 a follower that spends its budget reports ReachedRecordBudget` | Outcome `ReachedRecordBudget` ≠ `Finished` (ADT no colapsa) | M-L2 (looks like finished) — RED | verde, 1.13s |
| `LIMIT-4 the output lane stops at the budget instead of paging the whole plane` | El output-lane termina en el budget (no paging del plano entero) | M-L3 (advisory) — RED **LIMIT-4 alone** | verde, 0.072s |
| `LIMIT-5 no budget is the identity` *CHARACTERISATION* | Sin `--limit`, todo igual que antes | (no killing) | verde, 0.010s |
| `LIMIT-6 a limit that is not a count is refused, never clamped` *CHARACTERISATION* | Refusal tipado (no clamp silencioso a 0) | (no killing) | verde, 0.007s |
| `LIMIT-7 run refuses --limit rather than silently truncating` *CHARACTERISATION* | `run --limit` se rechaza tipadamente; no es drop-the-flag | (no killing) | verde, 0.005s |
| `LIMIT-8 the budget is pure, so two readers cannot disagree about how much they showed` *CHARACTERISATION* | `RecordBudget` es value class puro — dos readers ven el mismo conteo | (no killing) | verde, 0.001s |

**Mutaciones declaradas con anclaje 1:1 fila↔mutación:**

```text
M-L1 (the budget counts scanned records)  → RED LIMIT-2
M-L2 (a spent budget looks like finished)  → RED LIMIT-3
M-L3 (the budget is advisory)              → RED LIMIT-4
M-L4 (the presentation ignores the budget) → RED LIMIT-1
```

> **Nota HF5 (mutaciones explícitas vs lugar-común):** "M-L3 had left LIMIT-1 with NO killing mutation: it was a characterisation row until this one existed." — la M-L4 fue **escrita porque** M-L3 no mataba LIMIT-1 y la fila se quedaba como caractérisation muda. Run #24 ancla el contrato no porque "los tests pasen en compilación", sino porque **cada fila killing-mutation tiene un nombre y viceversa**.

Las 4 filas CHARACTERISATION (`LIMIT-5`, `LIMIT-6`, `LIMIT-7`, `LIMIT-8`) declaran honestamente que "ninguna mutación las mata" y siguen en verde porque **fijan pin-points**: la identidad (sin budget, todo igual), dos refusals (no-clamp, no-drop-the-flag) y la pureza del `RecordBudget`. Esa fila no es defectuosa por ser caractérisation — **decae a no-regression cuando la mutación cierra**, y la transición queda documentada en el assertion message.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5LimitTest.xml` (1630 bytes, mtime 2026-10-10 01:43:22):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5LimitTest" tests="8" skipped="0" failures="0" errors="0" timestamp="2026-10-09T23:43:20.720Z" hostname="bazzite-rubentxu" time="1.243">
  <testcase name="LIMIT-1 the event lane stops after N emitted records()" time="0.002"/>
  <testcase name="LIMIT-2 the budget counts selected records, not scanned ones()" time="0.004"/>
  <testcase name="LIMIT-3 a follower that spends its budget reports ReachedRecordBudget()" time="1.13"/>
  <testcase name="LIMIT-4 the output lane stops at the budget instead of paging the whole plane()" time="0.072"/>
  <testcase name="LIMIT-5 no budget is the identity()" time="0.01"/>
  <testcase name="LIMIT-6 a limit that is not a count is refused()" time="0.007"/>
  <testcase name="LIMIT-7 run refuses --limit rather than silently truncating()" time="0.005"/>
  <testcase name="LIMIT-8 the budget is pure, so two readers cannot disagree about how much they showed()" time="0.001"/>
```

**8 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load fluctuante (1m 14 → 32) — el patrón in-VM aguanta.

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="8"` del `<testsuite>` raíz es coherente con los 8 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: las `time` por test (0.002 a 1.13) son todas sub-segundo excepto LIMIT-3 (1.13s — el más caro porque recorre 3 outcomes para verificar el ADT), coherente con un build in-VM real.

BUILD SUCCESSFUL in 1m 56s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché. La `:pipeline-application:processTestResources` y `:pipeline-application:testClasses` aparecen "executed" en el log, junto con el `--rerun-tasks`.

## 3. Lo que Run #24 SÍ cierra

- **§1.5 OBS-E5d `--limit` budget anclado en test verde.** Antes de Run #24, la cobertura de OBS-E5d vivía implícita en el acumulado "91 verde de §1.5" sin fila visible defendiendo la distinción "selected vs scanned" ni la diferencia `ReachedRecordBudget` ≠ `Finished`. Run #24 enumera las 8 firmas con mutaciones declaradas.
- **HF5 (mutación explícita) demostrable** sobre OBS. Las 4 mutaciones M-L1..M-L4 están en el KDoc, con qué fila vuelca cada una, y la dependencia observada M-L3→LIMIT-4-alone se cross-validó experimentalmente ("It was predicted to also red LIMIT-1 and did not"). Run #24 ancla una práctica de HF5 replicable: un test OBS-E5 dice exactamente qué mutations puede romper y qué filas mueren por cada una.
- **ADT `ObserveOutcome` con constructor para budget** (`ReachedRecordBudget` ≠ `ReachedRunFinish` ≠ `StoppedByConsumer`): la fila LIMIT-3 fija que el sistema de tipos no colapsa los tres outcomes por descuido. Esto blinda la frontera con la `§1.5 OBS-E5c` Run #23 (FOLLOW path).
- **Refusal tipado cross-typology** (`LIMIT-6`, `LIMIT-7`): el CLI rechaza `--limit <not-an-int>` (no clamp a 0) y `run --limit` (no drop-the-flag), ambos tipados por `ObservationParseResult.Refused` o equivalente en `MainRunCli`. No cae al "por defecto" silencioso.
- **`RecordBudget` pureza (LIMIT-8)**: el budget es una value class — dos readers ven el mismo conteo, no hay dos `RecordBudget` que digan cosas distintas. Esto cierra una de las propiedades del ADR-OBS-002 (no-duplicate visible records).

## 4. Lo que Run #24 NO cierra

- **No cubre process-side `--limit` contra el binario instalado.** El path `pipelinek observe --limit 5 --stage build` o `pipelinek run --limit 5` no se ejercita en binario. Run #20 ancló process-side para `console` y `events` happy/error; `--limit` queda pendiente si se requiere process-side.
- **No cubre combinaciones `--limit` × filtro**: el contrato OBS-E5d dice "el budget cuenta selected", no "selected respeta exclusive vs inclusive". No es trabajo Run #24; podría ser Run #25 con `ObsE5ChannelAdmissionTest` o un nuevo test in-VM de la combinación.
- **No cubre la combinación `--limit 0` o `--limit -1`**: semánticas que deberían producir refusal tipado. Probable CHARACTERISATION row si se añade.
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #24

- **Verde acumulado in-VM**: 1564 (post Run #23) + 8 (Run #24) = **1572 tests verde sobre `4953fdc6`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #24 añade 1, total 23 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 9 + este (Run #24) = 10.

## 6. Anchors del contrato OBS-E5d en CI

| Firma OBS-E5d | Anchor | Killing mutation | Run | Path |
|---|---|---|---|---|
| Event-lane corta tras `N` **emitidos**, no `N` escaneados | `LIMIT-1` | M-L4 | #24 | in-VM |
| Budget cuenta `selected`, no `scanned` | `LIMIT-2` | M-L1 | #24 | in-VM |
| Outcome ADT: `ReachedRecordBudget` ≠ `Finished` | `LIMIT-3` | M-L2 | #24 | in-VM |
| Output-lane para en budget (no paging del plano entero) | `LIMIT-4` | M-L3 | #24 | in-VM |
| Sin budget = identidad | `LIMIT-5` | (CHARACTERISATION) | #24 | in-VM |
| `--limit <not-int>` → refusal (no clamp a 0) | `LIMIT-6` | (CHARACTERISATION) | #24 | in-VM |
| `run --limit` → refusal tipado | `LIMIT-7` | (CHARACTERISATION) | #24 | in-VM |
| `RecordBudget` puro: dos readers ven mismo conteo | `LIMIT-8` | (CHARACTERISATION) | #24 | in-VM |

**8 nuevos anchors OBS-E5d in-VM sobre `4953fdc6`, con 4 mutaciones killing declaradas y 4 filas caractérisation honestas.**

Junto con Run #23 (OBS-E5c FOLLOW path, 11 anchors), Run #24 cubre **19 anchors OBS-E5 in-VM** repartidos entre dos ramas contractuales distintas (FOLLOW-1..11, LIMIT-1..8). El §1.5 "output/cursors/query/redaction" sigue completándose sin tocar §0.3/§8.

## 7. Limitaciones operativas

- **Load fluctuante durante el run**: el host pasó de 1m 14,87 al cierre del Run #23 a 1m ~32 al cierre del Run #24. in-VM aguanta; cross-process Gradle pool sigue bloqueado.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03). El back-log §6.2 (12 tests cross-process formales) sigue pendiente.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 8. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **Run #25 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada aún:
   - `ObsE5ChannelAdmissionTest` (211 líneas, OBS-E5 channel-admission contracts);
   - `ObsE5ObserveReplayTest` (258 líneas, OBS-E5a/b replay vs follow byte-a-byte);
   - `ObsE5OutcomeTest` (230 líneas, OBS-E5 outcome ADT cross-producer);
   - `ObsE5TailTest` (285 líneas, OBS-E5 drain semantics).
3. **No avanzar §0.3/§8** sin tu autorización expresa. 1572 verde sobre `4953fdc6` es la mejor frontera defendible de sesión sin release.
