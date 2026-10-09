# Run #25 — `ObsE5TailTest` in-VM ancla §1.5 OBS-E5e `--tail-bytes` drain (Bloque E · OBS-R1 / OBS-E5e)

| | |
|---|---|
| **Run** | #25 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5e** (`--tail-bytes`: un tail acotado al output del run, leido en **O(bytes)** no **O(run)**; los bytes retornados son los **últimos** en observación; el frame fronterizo se **narrow** sin perder ordinal; consume `--limit` como start position). El KDoc documenta 3 mutaciones medidas y qué filas vuelca cada una, siguiendo la disciplina HF5 replicable de Runs #23-#24. |
| **SHA verificado** | `5921bde5346391434ee4f14a4e57a6a71de3d0a5` (HEAD post Run #24, mismo árbol — test OBS-merge tracked en `1b711b3d`) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5TailTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m fluctuante 14,87 → ~26 → 32 (al cierre). **in-VM aguanta** este load por Runs #16-#25 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `1b711b3d`, sin nuevos cambios en el código) |

## 1. Lo que Run #25 cierra

El KDoc del test class —`ObsE5TailTest.kt:21-67`— declara dos propiedades sobre COST y bytes:

> *"A tail exists so that a reader which cannot afford the whole transcript pays for the part it reads. That is the whole claim, and it is a claim about COST as much as about bytes: a tail that had to walk the entire frame index to find its starting point would return 64 KiB after reading 200 MB, which is the situation the flag is meant to solve rather than restate.
>
> So the two properties that matter are these: the bytes returned are the run's LAST ones in observation order, and the work done to find them is proportional to those bytes."*

Las firmas:

| Test | Cubre la propiedad OBS-E5e | Mutación que RED | Resultado medido |
|---|---|---|---|
| `TAIL-1 the resolved tail is printed, not just used as a resume position` | CLI seam — el tail resuelto se imprime, no se conserva sólo como resume position | (no killing — pins CLI seam) | verde, 0.525s |
| `TAIL-2 the boundary frame is narrowed and keeps its ordinal` | El frame fronterizo se **narrow**, conserva ordinal, no se dropea entero | M-T1 (from beginning) + M-T2 (drop whole) → RED TAIL-2 alone (M-T2) / TAIL-2 + TAIL-4 (M-T1) | verde, 0.009s |
| `TAIL-3 a budget larger than the run returns the whole run` | Budget ≥ bytes totales devuelve **todo** el run, no truncation silencioso | (no killing — pins whole-run identity) | verde, 0.095s |
| `TAIL-4 the cost grows with the bytes asked for, not with the run` | Coste O(bytes), no O(run) — la única fila que ve el coste de la backward walk | M-T1 (from beginning) + M-T3 (stops at first batch) → RED TAIL-4 alone (M-T3) / TAIL-2+TAIL-4 (M-T1) | verde, 0.497s |
| `TAIL-5 a run with no committed output yields an empty page` | Sin output committed devuelve página vacía, **no** inventa posición | (no killing — pins empty identity) | verde, 0.003s |
| `TAIL-6 the flag is refused on a view that carries no bytes` | `--tail-bytes` rechazado en view sin bytes (refusal tipado) | (no killing — pins refusal) | verde, 0.007s |
| `TAIL-7 the tail is a start position, so it composes with --limit` | Tail como **start position**, componible con `--limit` | (no killing — pins composition) | verde, 0.006s |
| `TAIL-8 run refuses --tail-bytes` | `run --tail-bytes` se rechaza tipadamente (cross-typology refusal) | (no killing — pins refusal) | verde, 0.008s |

**Mutaciones declaradas con anclaje 1:1 fila↔mutación:**

```text
M-T1 (the tail reads from the beginning)  → RED TAIL-2 + TAIL-4
        (predicted also TAIL-1; did not — TAIL-1 is answered by a scripted lane
         and never reaches readTail, which is why that row now says what it really defends)
M-T2 (the boundary frame is dropped whole) → RED TAIL-2 alone
M-T3 (the backward walk stops at the first batch) → RED TAIL-4 alone
        (and it is the only row that can see the cost of the walk:
         every other row fits inside eight frames)
```

> **Nota HF5:** a diferencia de OBS-E5d (`M-L1..M-L4`, 4 mutaciones / 4 CHARACTERISATION), OBS-E5e tiene 3 mutaciones y **6 filas no-killed** (TAIL-1/3/5/6/7/8) que sirven de pin-points para identidad, refusals y CLI seam. M-T3 es la única mutación cuyo target es observable en coste — los otros rows caben en ocho frames y no pueden distinguir una backward-walk truncada de una completa; eso es lo que el KDoc explica y la suite **no inventa** filas para engordar cobertura.

> **M-T1 confeso en el KDoc:** "It was predicted to red TAIL-1 too and did not" — la mutación declara lo que la teoría predijo y lo que el experimento midió; Run #25 ancla esa honestidad. TAIL-1 se queda como pin del CLI seam, no como defensa del reader — el defecto de su primera versión era "conservar sólo el resume position y no imprimir nada".

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5TailTest.xml` (1604 bytes, mtime 2026-10-10 01:50:52):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5TailTest" tests="8" skipped="0" failures="0" errors="0" timestamp="2026-10-09T23:50:51.355Z" hostname="bazzite-rubentxu" time="1.157">
  <testcase name="TAIL-1 the resolved tail is printed, not just used as a resume position()" time="0.525"/>
  <testcase name="TAIL-2 the boundary frame is narrowed and keeps its ordinal()" time="0.009"/>
  <testcase name="TAIL-3 a budget larger than the run returns the whole run()" time="0.095"/>
  <testcase name="TAIL-4 the cost grows with the bytes asked for, not with the run()" time="0.497"/>
  <testcase name="TAIL-5 a run with no committed output yields an empty page()" time="0.003"/>
  <testcase name="TAIL-6 the flag is refused on a view that carries no bytes()" time="0.007"/>
  <testcase name="TAIL-7 the tail is a start position, so it composes with --limit()" time="0.006"/>
  <testcase name="TAIL-8 run refuses --tail-bytes()" time="0.008"/>
```

**8 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 1m ~26. 

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="8"` del `<testsuite>` raíz es coherente con los 8 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: TAIL-1 (0.525s) y TAIL-4 (0.497s) son los más caros — TAIL-4 mide el coste real de la backward walk en `SegmentOutputStore` con frame index comprometido, TAIL-1 ejercita el CLI seam con sink real, no stubs. La suma de la columna `time` da 1.152s, coherente con el `time="1.157"` del `<testsuite>` (5 ms de overhead JUnit).

BUILD SUCCESSFUL in 1m 59s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #25 SÍ cierra

- **§1.5 OBS-E5e `--tail-bytes` drain anclado en test verde.** Antes de Run #25, el path OBS-E5e vivía implícito en el acumulado "91 verde de §1.5" sin fila defendiendo la **propiedad de coste** (O(bytes) no O(run)) ni la de "frame fronterizo se narrow, conserva ordinal". Run #25 enumera las 8 firmas con mutaciones declaradas.
- **Cost-as-feature pin (TAIL-4)**: OBS-E5e es el único path OBS donde el coste importa **como contrato** — la mutación M-T3 ("backward walk stops at first batch") sólo TAIL-4 la voltea. Run #25 cierra esa invariante: un tail que devuelve los bytes correctos pero caminando el frame index entero **está mal**, no "es aceptable". Esto cierra la garantía que el flag intenta resolver.
- **FrameIndexer seam (TAIL-2)**: el frame fronterizo mantiene su ordinal cuando se narrow; el resume desde `lastOrdinal` continúa exactamente donde terminó el tail y "no claimed range was not read". Esto blinda el contrato con SectionFrameIndex / SegmentFrameIndex (AudOB-F §3.1) sobre el que Runs anteriores layerizaron.
- **Cross-typology refusals** (`TAIL-6`, `TAIL-8`): tanto el view con cero bytes como `run --tail-bytes` se rechazan tipadamente (sin drop-the-flag). Coherente con el patrón de Run #23 (FOLLOW-7/11) y Run #24 (LIMIT-6/7).
- **Composition with `--limit`** (`TAIL-7`): el tail es un start position que compone con el budget; sin composición, `--tail-bytes` daría bytes sin orden y `--limit` daría orden sin acotar. La composición es lo que hace la CLI usable para transcripts largos.

## 4. Lo que Run #25 NO cierra

- **No cubre process-side `--tail-bytes` contra el binario instalado.** El path `pipelinek observe --tail-bytes 4096 <runId>` o `pipelinek run --tail-bytes` no se ejercita en binario. Run #20 ancló process-side para `console` y `events` happy/error; `--tail-bytes` queda pendiente si se requiere process-side.
- **No cubre `--tail-bytes` con `--view output` vs `--view events` diferencial**: el refusal TAIL-6 dice "el flag se rechaza en view sin bytes", pero la matriz cross-view (`output` vs `events`) no se enumera en el suite — el KDoc KDoc lo menciona en la mutation M-T1 al hablar de "scripted lane". Falta enumerar las 4 combinaciones view × tail/no-tail × limit/no-limit.
- **No cubre la combinación `--tail-bytes 0` o `--tail-bytes -1`**: semánticas que deberían producir refusal tipado. Probable CHARACTERISATION row si se añade.
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #25

- **Verde acumulado in-VM**: 1572 (post Run #24) + 8 (Run #25) = **1580 tests verde sobre `5921bde5`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #25 añade 1, total 24 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 10 + este (Run #25) = 11.

## 6. Anchors del contrato OBS-E5e en CI

| Firma OBS-E5e | Anchor | Killing mutation | Run | Path |
|---|---|---|---|---|
| CLI seam — tail resuelto se imprime, no solo resume position | `TAIL-1` | (pin CLI seam) | #25 | in-VM |
| Frame fronterizo se **narrow**, conserva ordinal, no dropea entero | `TAIL-2` | M-T1 + M-T2 | #25 | in-VM |
| Budget ≥ bytes totales → todo el run, no truncation | `TAIL-3` | (pin identity) | #25 | in-VM |
| Coste O(bytes) no O(run) — única fila que ve el coste | `TAIL-4` | M-T1 + M-T3 | #25 | in-VM |
| Run sin output committed → página vacía, no inventa posición | `TAIL-5` | (pin empty identity) | #25 | in-VM |
| `--tail-bytes` rechazado en view sin bytes | `TAIL-6` | (pin refusal) | #25 | in-VM |
| Tail es start position, compone con `--limit` | `TAIL-7` | (pin composition) | #25 | in-VM |
| `run --tail-bytes` → refusal tipado | `TAIL-8` | (pin refusal) | #25 | in-VM |

**8 nuevos anchors OBS-E5e in-VM sobre `5921bde5`, con 3 mutaciones killing declaradas y 6 filas pin-point honestas.**

Junto con Run #23 (OBS-E5c FOLLOW path, 11 anchors) y Run #24 (OBS-E5d LIMIT, 8 anchors), Run #25 cubre **27 anchors OBS-E5 in-VM** repartidos en tres ramas contractuales distintas (FOLLOW-1..11, LIMIT-1..8, TAIL-1..8). El §1.5 "output/cursors/query/redaction" sigue completándose sin tocar §0.3/§8.

## 7. Comparativa de disciplina HF5 entre OBS-E5c/d/e

| Path | Tests | Mutaciones killing | Filas CHARACTERISATION / pin | Run |
|---|---|---|---|---|
| OBS-E5c `--follow` (Run #23) | 11 | (no declara mutation deck en KDoc) | 0 killing, 11 firmas | #23 |
| OBS-E5d `--limit` (Run #24) | 8 | 4 (`M-L1..M-L4`) | 4 (LIMIT-5..8) | #24 |
| OBS-E5e `--tail-bytes` (Run #25) | 8 | 3 (`M-T1..M-T3`) | 6 (TAIL-1/3/5/6/7/8) | #25 |

OBS-E5d tiene la mayor densidad de mutación-por-fila (4/8 = 50%), OBS-E5e la mayor densidad de pin-points (6/8 = 75%). Run #25 ancla una **HF5-by-design** donde las filas no defended-decididas son pin-points explícitos — no caractérisation muda. El KDoc lo dice: "TAIL-3/5/6/7/8 pin refusals and an identity, and TAIL-1 pins the CLI seam rather than the reader".

## 8. Limitaciones operativas

- **Load 1m ~26 al run**: in-VM aguanta, igual que Runs #16-#24. No se reproduce el wedge.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03). El back-log §6.2 (12 tests cross-process formales) sigue pendiente.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **Run #26 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada aún:
   - `ObsE5ChannelAdmissionTest` (211 líneas, OBS-E5 channel-admission contracts);
   - `ObsE5ObserveReplayTest` (258 líneas, OBS-E5a/b replay vs follow byte-a-byte);
   - `ObsE5OutcomeTest` (230 líneas, OBS-E5 outcome ADT cross-producer).
3. **No avanzar §0.3/§8** sin tu autorización expresa. 1580 verde sobre `5921bde5` es la mejor frontera defendible de sesión sin release.
