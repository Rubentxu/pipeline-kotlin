# Run #27 — `ObsE5OutcomeTest` in-VM ancla §1.5 OBS-E5f `ObservedOutcome` unificado (Bloque E · OBS-R1 / OBS-E5f)

| | |
|---|---|
| **Run** | #27 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5f** (`--outcome`: UN vocabulario sobre tres productores que lo escribían con tres ortografías diferentes). El KDoc documenta 3 mutaciones medidas y qué filas vuelca cada una, y expone exactamente qué ortografía producía qué productor ("measured, not assumed") — la **dimensión existía antes de OBS-E5f, pero ningún caller podía alcanzarla**. |
| **SHA verificado** | `1e8c50cc6b4a7d807a564f7216adc9366f87baf8` (HEAD post Run #26, mismo árbol — test OBS-merge tracked en `6254d6c8`) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5OutcomeTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~27,96 (al inicio), ~32 (fluctuante). **in-VM aguanta** este load por Runs #16-#27 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `6254d6c8`, sin nuevos cambios en el código) |

## 1. Lo que Run #27 cierra

El KDoc del test class —`ObsE5OutcomeTest.kt:22-72`— declara el invariante:

> *"The dimension existed and compiled and no caller could reach it, the same shape `--channel` was in before OBS-E5a. Looking at WHY turned up the reason it had survived: the producers disagree. Measured, not assumed:*
>
> ```text
> RunFinished          "failure"      from RunOutcome.Failure
> StageFinished        "failed"       from StageOutcomeWire.FAILED
> StepFailed           no token       its TYPE is the claim
> StageSkipped         no token       its TYPE is the claim
> StageMarkedUnstable  no token       its TYPE is the claim
> ```
>
> *A filter over those strings answers 'which spelling did this producer use'. `--outcome FAILURE` would return every failed step and no failed run. `ObservedOutcome` collapses the spelling and names the set the producers already agree on semantically."*

Las firmas:

| Test | Cubre la propiedad OBS-E5f | Mutación que RED | Resultado medido |
|---|---|---|---|
| `OUTCOME-1 a failed run and a failed step are the same outcome` | El outcome Failure colapsa la ortografía: run y step llegan al mismo `ObservedOutcome.Failure` | M-OC1 (vocabulary reopens) → RED **OUTCOME-1 + OUTCOME-4** | verde, 0.026s |
| `OUTCOME-2 an unnamed spelling is carried and matches no filter` | Spelling no nombrada por este build → `ObservedOutcome.Other`, NO drop ni wildcard | M-OC2 (unknown dropped) → RED OUTCOME-2 alone | verde, 0.012s |
| `OUTCOME-3 --outcome reaches the query and selects` | El flag `--outcome` llega a `compileQuery` y filtra los records | (no killing — pin wiring) | verde, 0.023s |
| `OUTCOME-4 an unknown name is refused, never a wildcard` | Nombre desconocido = refusal tipado, NO silenciosamente igual a `Success` | M-OC1 + M-OC3 → RED OUTCOME-4 alone (M-OC3) / OUTCOME-1+OUTCOME-4 (M-OC1) | verde, 0.021s |
| `OUTCOME-5 run refuses --outcome` | `run --outcome` se rechaza tipadamente (cross-typology refusal) | (no killing — pin refusal) | verde, 0.633s |
| `OUTCOME-6 the filter decides what is printed and nothing else` | El filtro **NO** cambia el exit code del run (sólo decide qué se imprime; "un run con `--outcome failure` sale 0 igual") | (no killing — pin identity) | verde, 0.029s |

**Mutaciones declaradas con anclaje 1:1 fila↔mutación:**

```text
M-OC1 (the vocabulary reopens)  → RED OUTCOME-1 + OUTCOME-4
        Map `failed` back to its own spelling instead of to
        [ObservedOutcome.Failure]. With the spellings apart again,
        a failed stage is an unknown token and `--outcome failed`
        is a name nobody accepts.
M-OC2 (an unknown spelling is dropped)  → RED OUTCOME-2 alone
        Answer `null` instead of [ObservedOutcome.Other].
M-OC3 (an unknown name is a wildcard)  → RED OUTCOME-4 alone
        Accept it as `Success` instead of refusing.
```

> **Nota HF5:** M-OC1 vuelca **dos filas** (OUTCOME-1 + OUTCOME-4) con una sola mutación — collapse ortográfico abierto descompensa tanto el "un outcome" como el "refused, never wildcard". M-OC2 es fila-específica (`OUTCOME-2` alone), y M-OC3 también fila-específica (`OUTCOME-4` alone). Los dos pin-points (OUTCOME-5, OUTCOME-6) defienden: cross-typology refusal e identidad de filtro-sin-efecto. **OUTCOME-3** es pin de wiring (sin killing mutation porque su failure-mode sería un comportamiento roto a nivel de integration, no un cambio de una línea de `ObservedOutcome`).

> **M-OC1 confeso en el KDoc:** "Map `failed` back to its own spelling" — la mutación es un revert a la ortografía divergente original. Run #27 ancla que `ObservedOutcome` colapsa ortografía como decisión arquitectónica tipada, no como coincidencia feliz.

> **Tu run es `ObservedOutcome`:** este es el ADT que Run #23 nombró via `outcome` en `EVENT_LANE_VIEWS`/`record.outcome`, y Run #24 expandió via `RecordBudget`. Run #27 ancla la **semántica unificada** del ADT, no su exposición CLI. Sin OBS-E5f, dos callers mirando el mismo run podrían ver el mismo fallo con etiquetas distintas: `failure` vs `failed` vs `StepFailed` (sin token, el TYPE es el claim).

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5OutcomeTest.xml` (1281 bytes, mtime 2026-10-10 02:02:54):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5OutcomeTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:02:53.087Z" hostname="bazzite-rubentxu" time="0.754">
  <testcase name="OUTCOME-1 a failed run and a failed step are the same outcome()" time="0.026"/>
  <testcase name="OUTCOME-2 an unnamed spelling is carried and matches no filter()" time="0.012"/>
  <testcase name="OUTCOME-3 --outcome reaches the query and selects()" time="0.023"/>
  <testcase name="OUTCOME-4 an unknown name is refused, never a wildcard()" time="0.021"/>
  <testcase name="OUTCOME-5 run refuses --outcome()" time="0.633"/>
  <testcase name="OUTCOME-6 the filter decides what is printed and nothing else()" time="0.029"/>
```

**6 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 1m ~28 — el patrón in-VM aguanta.

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="6"` del `<testsuite>` raíz es coherente con los 6 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: OUTCOME-5 (0.633s) es el más caro porque ejercita el refusal cross-typology contra el CLI parser real (no stub), verificando que `run --outcome` se rechaza con tipado y mensaje; el resto es sub-50ms. La suma de la columna `time` da 0.744s, coherente con `time="0.754"` del suite (10 ms overhead JUnit).

BUILD SUCCESSFUL in 1m 58s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #27 SÍ cierra

- **§1.5 OBS-E5f `ObservedOutcome` ADT anclado en test verde.** Antes de Run #27, el path OBS-E5f vivía implícito en el acumulado "91 verde de §1.5" sin fila defendiendo la **colapsación de ortografía** ("RunFinished 'failure' / StageFinished 'failed' / StepFailed no token" — todos al mismo `ObservedOutcome.Failure`). Run #27 enumera las 6 firmas con mutaciones declaradas y el KDoc confeso "measured, not assumed" sobre la ortografía original divergente.
- **Producers-agree semantics pin (OUTCOME-1)**: el outcome "fallo" colapsa run + stage + step al mismo `ObservedOutcome.Failure`. Sin esto, dos callers mirando el mismo run verían el mismo fallo con etiquetas distintas — Run #27 protege la **identidad semántica** que `ObservedOutcome` introduce.
- **`ObservedOutcome.Other` como sink tipado (OUTCOME-2)**: una ortografía no nombrada por este build se lleva en `Other`, no se dropea ni se trata como `Failure`. M-OC2 verifica que `null` no es respuesta.
- **Wiring pin (OUTCOME-3)**: `--outcome` atraviesa `compileQuery` y filtra records. No killing mutation porque su failure es de integration, no de model.
- **Refusal-by-name (OUTCOME-4)**: nombre desconocido → refusal tipado, **no** silenciosamente tratado como `Success` (el wildcard). M-OC3 verifica que el rechazo es por nombre explícito, no por coincidencia.
- **Cross-typology refusal (OUTCOME-5)**: `run --outcome` se rechaza tipadamente. Coherente con Runs #23 (FOLLOW-11), #24 (LIMIT-7), #25 (TAIL-8), #26 (OBSERVE-9).
- **Filtro-no-cambia-exit-code (OUTCOME-6)**: el filtro decide qué se imprime y nada más. **Un run con `--outcome failure` sigue saliendo 0** — el filtro no ejecuta lógica de control de flujo. Coherente con el SEMANTIC CONSTITUTION §3 (pure builders no effects) y §10 (DSL describe, no executes).

## 4. Lo que Run #27 NO cierra

- **No cubre process-side `pipelinek observe --outcome failure` contra el binario instalado.** Run #20 ancló process-side para `console` y `events` happy/error; `--outcome` queda pendiente si se requiere process-side.
- **No cubre la matriz `ObservedOutcome` × view × lane completa**. Las 6 firmas cubren las aristas de refusal y ortografía colapsada, pero la combinación de `--outcome failure --view events --tail-bytes 4096` o similar no está enumerada en el suite.
- **No cubre el integration con `compileQuery` real contra `SegmentOutputStore` o `EventJournal`**. OUTCOME-3 usa una `ObservationQuery` en memoria; el wiring real contra el output plane queda como Run #28 con `ObsE5ChannelAdmissionTest` que probablemente lo toca.
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #27

- **Verde acumulado in-VM**: 1589 (post Run #26) + 6 (Run #27) = **1595 tests verde sobre `1e8c50cc`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #27 añade 1, total 26 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 12 + este (Run #27) = 13.

## 6. Anchors del contrato OBS-E5f en CI

| Firma OBS-E5f | Anchor | Killing mutation | Run | Path |
|---|---|---|---|---|
| Run failed + Step failed = mismo `ObservedOutcome.Failure` (colapsación ortográfica) | `OUTCOME-1` | M-OC1 (con 4) | #27 | in-VM |
| Spelling no nombrada → `ObservedOutcome.Other`, no drop ni wildcard | `OUTCOME-2` | M-OC2 | #27 | in-VM |
| `--outcome` reaches `compileQuery` y selecciona records | `OUTCOME-3` | (pin wiring) | #27 | in-VM |
| Nombre desconocido → refusal tipado, no `Success` wildcard | `OUTCOME-4` | M-OC1 (con 1) + M-OC3 alone | #27 | in-VM |
| `run --outcome` → refusal tipado cross-typology | `OUTCOME-5` | (pin refusal) | #27 | in-VM |
| Filtro decide qué se imprime, NO cambia exit code | `OUTCOME-6` | (pin identity) | #27 | in-VM |

**6 nuevos anchors OBS-E5f in-VM sobre `1e8c50cc`, con 3 mutaciones killing declaradas y 3 filas pin-point honestas**.

Run #27 cierra el conjunto OBS-E5f y completa el **set OBS-E de Runs #23-#27** (b/c/d/e/f), con 5 OBS-E receipts consecutivos sobre OBS-merge tracked code. El §1.5 del Bloque E está ahora cubierto por anclajes dedicados en cada sub-rama OBS-E5.

## 7. Comparativa de disciplina HF5 entre OBS-E5b/c/d/e/f

| Path | Tests | Mutaciones killing | Filas pin-point / CHARACTERISATION | Run |
|---|---|---|---|---|
| OBS-E5b `observe` refusal matrix (Run #26) | 9 | 3 (`M-O1..M-O3`) | 3 + 3 refusals colapsadas con M-O1 | #26 |
| OBS-E5c `--follow` (Run #23) | 11 | (ADT by construction, no deck) | 0 killing, 11 firmas | #23 |
| OBS-E5d `--limit` (Run #24) | 8 | 4 (`M-L1..M-L4`) | 4 (LIMIT-5..8) | #24 |
| OBS-E5e `--tail-bytes` (Run #25) | 8 | 3 (`M-T1..M-T3`) | 6 (TAIL-1/3/5/6/7/8) | #25 |
| OBS-E5f `--outcome` (Run #27) | 6 | 3 (`M-OC1..M-OC3`) | 3 (OUTCOME-3/5/6) + cross-row collapse M-OC1 | #27 |

**Tendencias OBS-E5:**
- **OBS-E5b y OBS-E5f comparten** una mutación `cross-row collapse` (M-O1 vuelca 3 filas; M-OC1 vuelca 2). Son las dos invariantes de **no-distinguishability-by-collapse** del set OBS-E (tres ausencias / ortografía colapsada).
- **OBS-E5c no tiene deck** porque la discriminated-union por construcción tipa el outcome (FOLLOW-3 StoppedByConsumer ≠ Finished es por ADT). HF5-by-design.
- **OBS-E5d y OBS-E5e tienen decks** densos porque **cost-as-feature** (TAIL-4) y **refusal matrix** son fail-modes no tipados constructivamente.

Run #23..#27 cubren 42 anchors OBS-E5 in-VM sobre `1e8c50cc`, **5 sub-ramas OBS-E exhaustivamente ancladas con HF5-by-design**.

## 8. Limitaciones operativas

- **Load 1m ~28 al run**: in-VM aguanta, igual que Runs #16-#26. No se reproduce el wedge.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03). El back-log §6.2 (12 tests cross-process formales) sigue pendiente.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **Run #28 OPCIONAL** (in-VM, no requiere ventana): candidato OBS-merge sin ancla dedicada aún:
   - `ObsE5ChannelAdmissionTest` (211 líneas, OBS-E5 channel-admission contracts) — la **única clase OBS-E5 sin ancla** tras Runs #23-#27.
3. **§0.3/§8**: **sigue BLOCKED_EXTERNAL** sin tu autorización expresa. 1595 verde sobre `1e8c50cc` es la mejor frontera defendible de sesión sin release.
