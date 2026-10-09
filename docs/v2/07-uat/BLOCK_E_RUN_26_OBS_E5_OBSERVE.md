# Run #26 — `ObsE5ObserveReplayTest` in-VM ancla §1.5 OBS-E5b `observe` refusal matrix (Bloque E · OBS-R1 / OBS-E5b)

| | |
|---|---|
| **Run** | #26 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5b** (`observe`: UN solo verbo sobre ambos durable authorities, **rechazando** lo que no puede prometer antes de fingir una respuesta vacía). El KDoc documenta 3 mutaciones medidas con la sorpresa explícita de que M-O3 vuelca DOS filas, no una — fail-closed confeso, no over-narrow claim. |
| **SHA verificado** | `015ebf907bb20823d76344d3e89e01244a246358` (HEAD post Run #25, mismo árbol — test OBS-merge tracked en `c1276b28`) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5ObserveReplayTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~29,69 (al inicio), ~32 (fluctuante). **in-VM aguanta** este load por Runs #16-#26 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `c1276b28`, sin nuevos cambios en el código) |

## 1. Lo que Run #26 cierra

El KDoc del test class —`ObsE5ObserveReplayTest.kt:22-67`— declara el invariante:

> *"The rows that matter here are the refusals, not the rendering. A reader that answers 'there is nothing to see' for a lane it never had a store for is indistinguishable, to whoever reads its output, from a run that genuinely produced nothing — and that is how a hole becomes a clean finish. So each of the three absences gets its own row and its own named outcome."*

Las firmas:

| Test | Cubre la propiedad OBS-E5b | Mutación que RED | Resultado medido |
|---|---|---|---|
| `OBSERVE-1 the event lane renders through the same encoder run uses` | El encoder del event-lane es el **mismo** que usa `run` (consistencia de output) | (no killing — pins encoder reuse) | verde, 0.032s |
| `OBSERVE-2 the console lane renders committed bytes` | El console-lane pinta los bytes **committed**, en frame order | (no killing — pins committed-bytes) | verde, 0.001s |
| `OBSERVE-3 channel reaches the console lane through the shared query` | El parámetro `--channel` llega al console-lane a través de la query compartida (no bypass) | M-O2 (console ignores query) → RED OBSERVE-3 | verde, 0.017s |
| `OBSERVE-4 no event store is refused, not answered with an empty list` | Sin event store = **refusal tipado**, no empty list | M-O1 (missing lane answers "nothing") → RED **OBSERVE-4, 5, 6** (3 colapsadas) | verde, 0.001s |
| `OBSERVE-5 no output plane is refused, not answered with empty text` | Sin output plane = refusal tipado, no empty text | M-O1 → RED OBSERVE-4, 5, 6 | verde, 0.002s |
| `OBSERVE-6 a store that says I cannot is refused, not answered with empty text` | Store con "no puedo" = refusal tipado (no empty text fabricado) | M-O1 → RED OBSERVE-4, 5, 6 | verde, 0.55s |
| `OBSERVE-7 the full view is refused at admission for this verb too` | `--view full` se rechaza en admission para `observe` también | M-O3 (run gains console) → RED **OBSERVE-7 AND 8** (predicted sólo 8 — caught) | verde, 0.001s |
| `OBSERVE-8 console is deliverable here and still refused for run` | `console` view es deliverable para `observe`, sigue refused para `run` (separation of concerns) | M-O3 → RED OBSERVE-7 AND 8 | verde, 0.007s |
| `OBSERVE-9 an execution option is refused by name, not ignored` | `--resume` y similares refused by name (no drop-the-flag) | (no killing — pins refusal) | verde, 0.003s |

**Mutaciones declaradas con anclaje 1:1 fila↔mutación:**

```text
M-O1 (a missing lane answers "nothing")  → RED OBSERVE-4 + OBSERVE-5 + OBSERVE-6
        All three collapse to the same clean-looking empty output — that is
        the whole failure: one wrong line makes three different absences
        indistinguishable.
M-O2 (the console lane ignores the query) → RED OBSERVE-3
M-O3 (`run` gains console)  → RED OBSERVE-7 + OBSERVE-8 (not OBSERVE-8 alone)
        The first draft of the last mutation said "only OBSERVE-8" and was
        wrong, for the second time in this branch: `entries.toSet()` also
        makes `full` deliverable, and `full` is the one view whose refusal
        has nothing to do with which lane a reader can see. Measuring it
        caught the over-narrow claim.
```

> **Nota HF5:** tres mutaciones, **siete filas killing-cubiertas** (3+1+2). Las 2 filas restantes (OBSERVE-1, OBSERVE-2, OBSERVE-9 — pin points) defienden identidad de encoder, "bytes committed" identidad, y refusal-by-name. **KDoc expone explícitamente** que el primer draft de M-O3 dijo "sólo OBSERVE-8" y midió mal — medir es lo que cazó el over-narrow claim. Run #26 ancla esta honestidad del regression-test deck.

> **M-O1 es mutación crítica:** una sola línea (reemplazar refusal por empty page) hace **tres ausencias distintas** indistinguibles. OBS-E5b rechaza explícitamente esta elección con tres filas: `no event store`, `no output plane`, `store says I cannot`. Sin esas tres filas, un lector downstream no podría distinguir "lane inexistente" de "lane vacía legítima" — **un hole disguised as a clean finish** (per KDoc).

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5ObserveReplayTest.xml` (1914 bytes, mtime 2026-10-10 01:56:49):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5ObserveReplayTest" tests="9" skipped="0" failures="0" errors="0" timestamp="2026-10-09T23:56:48.750Z" hostname="bazzite-rubentxu" time="0.623">
  <testcase name="OBSERVE-1 the event lane renders through the same encoder run uses()" time="0.032"/>
  <testcase name="OBSERVE-2 the console lane renders committed bytes()" time="0.001"/>
  <testcase name="OBSERVE-3 channel reaches the console lane through the shared query()" time="0.017"/>
  <testcase name="OBSERVE-4 no event store is refused, not answered with an empty list()" time="0.001"/>
  <testcase name="OBSERVE-5 no output plane is refused, not answered with empty text()" time="0.002"/>
  <testcase name="OBSERVE-6 a store that says I cannot is refused, not answered with empty text()" time="0.55"/>
  <testcase name="OBSERVE-7 the full view is refused at admission for this verb too()" time="0.001"/>
  <testcase name="OBSERVE-8 console is deliverable here and still refused for run()" time="0.007"/>
  <testcase name="OBSERVE-9 an execution option is refused by name, not ignored()" time="0.003"/>
```

**9 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 1m ~30 — el patrón in-VM aguanta.

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="9"` del `<testsuite>` raíz es coherente con los 9 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: OBSERVE-6 (0.55s) es el más caro porque ejercita el "store says I cannot" round-trip con un stub que se queja; el resto es sub-50ms. La suma de la columna `time` da 0.612s, coherente con `time="0.623"` del suite (11 ms de overhead JUnit).

BUILD SUCCESSFUL in 1m 41s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #26 SÍ cierra

- **§1.5 OBS-E5b `observe` refusal matrix anclado en test verde.** Antes de Run #26, la cobertura OBS-E5b vivía implícita en el acumulado "91 verde de §1.5" sin fila defendiendo la **distinción "no event store" / "no output plane" / "store says I cannot"** ni la separation of concerns entre `observe` (deliverable console) y `run` (refused console). Run #26 enumera las 9 firmas con mutaciones declaradas y el KDoc confeso "for the second time in this branch".
- **Triple-ausencia pin (OBSERVE-4/5/6)**: una sola mutación (M-O1) colapsa tres ausencias distintas. OBS-E5b las mantiene separadas con refusal tipado cada una. Esta es la invariante que protege "a hole becoming a clean finish" — Run #26 cierra que tres ausencias NO se vuelven indistinguibles en output.
- **Encoder reuse (OBSERVE-1)**: el event-lane usa el **mismo encoder que `run`** — consistencia de output entre quien ejecuta y quien observa. Sin esto, dos pipelines distintos presentarían eventos del mismo run en formatos distintos.
- **Shared query plumbing (OBSERVE-3)**: `--channel` atraviesa la query compartida sin bypasses. M-O2 verifica que un drop del filtro `parsed.compiled.accepts(it)` rompe la fila — hay plumbing que se hace por construcción tipada.
- **Cross-verb separation (OBSERVE-7/8)**: `observe --view full` se rechaza; `observe --view console` se acepta; `run --view console` se rechaza. Las tres filas son necesarias porque M-O3 ("run gains console") rompe **dos** a la vez, no una, y eso fue el over-narrow claim del primer draft.
- **Refusal-by-name (OBSERVE-9)**: `--resume` y similares se rechazan por nombre, no se drop-the-flag. Coherente con Run #24 (LIMIT-7) y Run #25 (TAIL-8).

## 4. Lo que Run #26 NO cierra

- **No cubre process-side `pipelinek observe …` contra el binario instalado.** Run #20 ancló process-side para `console` y `events` happy/error; `observe` queda pendiente si se requiere process-side.
- **No cubre el refusals cross-verb en binario**: OBSERVE-7/8 son in-VM. La matriz "`observe` accepts console, `run` refuses console" contra el binario podría exhibir comportamiento distinto (ej. `--console` flag mutual exclusion). Probable extension Run #27 con process-side probe.
- **No cubre el scenario "store says I cannot" via real SegmentOutputStore** (OBSERVE-6 usa un stub). Probable extension in-VM con `SegmentOutputStore` real con permisos 000 sobre el directorio.
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #26

- **Verde acumulado in-VM**: 1580 (post Run #25) + 9 (Run #26) = **1589 tests verde sobre `015ebf90`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #26 añade 1, total 25 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 11 + este (Run #26) = 12.

## 6. Anchors del contrato OBS-E5b en CI

| Firma OBS-E5b | Anchor | Killing mutation | Run | Path |
|---|---|---|---|---|
| Event lane encoder = `run` encoder | `OBSERVE-1` | (pin encoder reuse) | #26 | in-VM |
| Console lane pinta bytes **committed**, en frame order | `OBSERVE-2` | (pin committed-bytes) | #26 | in-VM |
| `--channel` llega via shared query (no bypass) | `OBSERVE-3` | M-O2 | #26 | in-VM |
| Sin event store = refusal tipado, no empty list | `OBSERVE-4` | M-O1 (3→1 colapso) | #26 | in-VM |
| Sin output plane = refusal tipado, no empty text | `OBSERVE-5` | M-O1 | #26 | in-VM |
| Store "no puedo" = refusal tipado, no empty text | `OBSERVE-6` | M-O1 | #26 | in-VM |
| `--view full` rechazado en admission para `observe` también | `OBSERVE-7` | M-O3 (cross with 8) | #26 | in-VM |
| `console` deliverable para `observe`, refused para `run` | `OBSERVE-8` | M-O3 | #26 | in-VM |
| `--resume` etc. refused by name (no drop-the-flag) | `OBSERVE-9` | (pin refusal) | #26 | in-VM |

**9 nuevos anchors OBS-E5b in-VM sobre `015ebf90`, con 3 mutaciones killing declaradas y 3 filas pin-point honestas** (más 3 filas de refusals tipados que comparten M-O1).

## 7. Comparativa de disciplina HF5 entre OBS-E5b/c/d/e

| Path | Tests | Mutaciones killing | Filas pin-point / CHARACTERISATION | Run |
|---|---|---|---|---|
| OBS-E5b `observe` refusal matrix (Run #26) | 9 | 3 (`M-O1..M-O3`) | 3 (OBSERVE-1/2/9) + 3 refusals compartidas con M-O1 | #26 |
| OBS-E5c `--follow` (Run #23) | 11 | (no declara mutation deck en KDoc) | 0 killing, 11 firmas | #23 |
| OBS-E5d `--limit` (Run #24) | 8 | 4 (`M-L1..M-L4`) | 4 (LIMIT-5..8) | #24 |
| OBS-E5e `--tail-bytes` (Run #25) | 8 | 3 (`M-T1..M-T3`) | 6 (TAIL-1/3/5/6/7/8) | #25 |

OBS-E5b es único: la mutación M-O1 vuelca **tres filas a la vez** (un cambio = tres ausencias indistinguibles). El deck de mutaciones aquí **se refuerza mutuamente** (cada fila no puede ser defendida por otra; la única mutación que las rompe a tres es una sola). Run #26 ancla esta propiedad cross-row colapsable como **el failure-mode que M-O1 protege** — Run #25 TAIL-3 protege whole-run identity, Run #26 OBSERVE-4..6 protege triple-ausencia.

> **Por qué OBS-E5c no tiene mutation deck:** Run #23 cubrió el FOLLOW path como caracterización tipada del ADT — los outcomes `StoppedByConsumer` vs `KeepReading` vs `Finished` están tipados por construcción, y un mutation-style deck añadiría boilerplate sin revelar ningún bug oculto. Los Runs #24-#26 sí tienen deck porque las invariantes de coste (Run #25 TAIL-4) y refusal matrix (Run #26 OBSERVE-4..6) **sí** se rompen con una línea de cambio. La HF5-by-design discrimina cuándo aplica deck y cuándo no.

## 8. Limitaciones operativas

- **Load 1m ~30 al run**: in-VM aguanta, igual que Runs #16-#25. No se reproduce el wedge.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03). El back-log §6.2 (12 tests cross-process formales) sigue pendiente.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **Run #27 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada aún:
   - `ObsE5ChannelAdmissionTest` (211 líneas, OBS-E5 channel-admission contracts);
   - `ObsE5OutcomeTest` (230 líneas, OBS-E5 outcome ADT cross-producer).
3. **No avanzar §0.3/§8** sin tu autorización expresa. 1589 verde sobre `015ebf90` es la mejor frontera defendible de sesión sin release.
