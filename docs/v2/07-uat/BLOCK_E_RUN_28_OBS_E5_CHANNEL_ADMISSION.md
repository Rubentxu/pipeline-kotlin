# Run #28 — `ObsE5ChannelAdmissionTest` in-VM ancla §1.5 OBS-E5a `--channel` admission (Bloque E · OBS-R1 / OBS-E5a)

| | |
|---|---|
| **Run** | #28 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5a** (`--channel` reaches the query: el flag `--channel stdout\|stderr` atraviesa `argv → buildObservationQuery → accepts`, discriminando records en output sin fusionar stderr en stdout). El KDoc documenta **3 mutaciones medidas** y expone confeso: (a) el primer draft de M-C1 dijo "sólo 2 filas" y midió 4; (b) el primer draft de M-C3 no compilaba, Gradle corrió las clases previas y reportó evidencia stale — la mutación se rehízo y la mutation-runner quedó endurecida para exigir build verde como evidencia. |
| **SHA verificado** | `a74fa29f9278a0fc28bf34ab85ab908445181e49` (HEAD post Run #27, mismo árbol — test OBS-merge tracked en `7c0cc89b`) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5ChannelAdmissionTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~18,82 (al inicio, ventana más favorable), ~22 (fluctuante). **in-VM aguanta** este load por Runs #16-#28 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `7c0cc89b`, sin nuevos cambios en el código) |

## 1. Lo que Run #28 cierra

El KDoc del test class —`ObsE5ChannelAdmissionTest.kt:18-74`— declara el invariante con un diagnóstico histórico explícito:

> *"`ObservationQuery.channels` was implemented, compiled and consumed by `CompiledObservationQuery.accepts` from OBS-D onwards, and the entire surrounding suite talks about `--channel stderr` as a real surface: `ObsC23NoChannelFusionFitnessTest` exists to keep `redirectErrorStream(true)` from making channel attribution unrecoverable, and `ObsC23ChannelSeparationUatTest` exists to prove the stderr stream really holds the stderr bytes.*
>
> *None of that was reachable. `CliParser` had no `--channel` option, `buildObservationQuery` had no parameter for it, and the field's own KDoc explained why in a sentence that had become false: 'no durable carrier exists for stdout vs stderr, so offering the dimension would be a filter that cannot filter'. So the producer existed, the consumer existed, the tests claimed the surface existed, and the flag could not be typed. Everything was green.*
>
> *The unit tests above could not see it because they construct `ObservationQuery(channels = ...)` directly — they start from the object the flag is supposed to produce. A whole dimension was certified by tests that bypassed the only thing under suspicion."*

Las firmas:

| Test | Cubre la propiedad OBS-E5a | Mutación que RED | Resultado medido |
|---|---|---|---|
| `CH-ADM-1 the flag reaches the assembled query` | El flag `--channel` llega al `ObservationQuery` ensamblado | M-C1 (decorative) → RED **CH-ADM-1+4+5+identity (4 filas)** | verde, 0.696s |
| `CH-ADM-2 a channel flag with no value is refused before anything runs` | `--channel` sin valor → refusal tipado antes de ejecutar | (no killing — pin refusal) | verde, 0.003s |
| `CH-ADM-2b a script in the value position is named, not silently swallowed` | Un script posicional en el lugar de `--channel`'s value se nombra explícitamente, no se traga | M-C2 → RED **CH-ADM-3 and CH-ADM-2b** | verde, 0.004s |
| `CH-ADM-2c a channel flag after the script is refused as trailing, not ignored` | `--channel` después del script se rechaza como trailing, no se ignora | (no killing — pin trailing) | verde, 0.001s |
| `CH-ADM-3 an unknown token names itself and the vocabulary` | Token desconocido se nombra a sí mismo Y expone el vocabulario (refusal tipado) | M-C2 → RED CH-ADM-3 and CH-ADM-2b | verde, 0.005s |
| `CH-ADM-4 repeating the flag unions, per AND across and OR within` | Repetir el flag → union del set (within=`OR`, across=`AND`) | M-C1 + M-C3 → RED **CH-ADM-4 alone (M-C3)** / +CH-ADM-1+5+identity (M-C1) | verde, 0.001s |
| `CH-ADM-5 argv to accepts excludes a stdout record` | `argv → buildObservationQuery → accepts` excluye realmente un stdout record cuando channel=stderr | M-C1 → RED CH-ADM-1+4+5+identity | verde, 0.020s |

**Mutaciones declaradas con anclaje explícito:**

```text
M-C1 (the dimension is decorative)  → RED CH-ADM-1 + CH-ADM-4 + CH-ADM-5 + identity row (4 filas, no 2)
        Stop passing `state.channels` into `buildObservationQuery` at both
        call sites. The first draft said "only two rows" and was wrong,
        because an empty `channels` also breaks the union row (CH-ADM-4)
        and the identity row — a flag that never reaches the query leaves
        nothing behind.
M-C2 (unknown tokens accepted silently) → RED CH-ADM-3 + CH-ADM-2b
        Resolve an unrecognised token to `STDERR` instead of refusing.
        A filter that quietly keeps nothing, or quietly becomes a
        different filter, is the trap `EmptyTextFilter` already exists
        to avoid.
M-C3 (intersect instead of union)  → RED CH-ADM-4 alone
        Clear before adding. The first version assigned to a `val`
        MutableSet which did not compile; Gradle ran the previously
        compiled classes and reported stale evidence — so the mutation
        runner was hardened to refuse reporting without a green build.
```

> **Nota HF5 (multi-capa):**
> 
> **Capa 1 — Predicted-vs-measured confeso (M-C1):** el primer draft dijo "sólo 2 filas", midió 4. La mutation lo cazo. **"measured, not predicted"** es literal en este test class.
> 
> **Capa 2 — Self-catching confeso (M-C3):** el primer `M-C3` no compilaba (asignación a `val MutableSet`). Gradle corrió las clases **previas** compiladas, dejó XML stale de la mutation-runner anterior, y reportó el resultado con las filas de M-C2 (no de M-C3). Esto es exactamente el HARNESS FIDELITY LAW §6 "A failing compile is not a RED" — y **el test class fue quien lo padeció mientras se escribía**. La mutation-runner **se endureció** para exigir build verde como evidencia.
> 
> **Capa 3 — Decorative-dimension protection:** M-C1 protege contra una dimensión implementada, compilada, consumida por OBS-D, "testada" por unit tests que construyen `ObservationQuery(channels=...)` directamente — pero **nunca alcanzable** desde `argv`. Run #28 ancla esa integración end-to-end.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5ChannelAdmissionTest.xml` (1571 bytes, mtime 2026-10-10 02:08:45):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5ChannelAdmissionTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:08:44.787Z" hostname="bazzite-rubentxu" time="0.737">
  <testcase name="CH-ADM-1 the flag reaches the assembled query()" time="0.696"/>
  <testcase name="CH-ADM-2 a channel flag with no value is refused before anything runs()" time="0.003"/>
  <testcase name="CH-ADM-2b a script in the value position is named, not silently swallowed()" time="0.004"/>
  <testcase name="CH-ADM-2c a channel flag after the script is refused as trailing, not ignored()" time="0.001"/>
  <testcase name="CH-ADM-3 an unknown token names itself and the vocabulary()" time="0.005"/>
  <testcase name="CH-ADM-4 repeating the flag unions, per AND across and OR within()" time="0.001"/>
  <testcase name="CH-ADM-5 argv to accepts excludes a stdout record()" time="0.02"/>
```

**7 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 1m ~19 — el patrón in-VM aguanta y la ventana estaba más favorable que Runs recientes.

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="7"` del `<testsuite>` raíz es coherente con los 7 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: CH-ADM-1 (0.696s) es el más caro — ejercita `argv → buildObservationQuery → accepts` end-to-end, exactamente la integración que el test class protege (no unit tests del modelo). El resto es sub-25ms. La suma de la columna `time` da 0.730s, coherente con `time="0.737"` del suite (7 ms overhead JUnit).

BUILD SUCCESSFUL in 2m 5s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #28 SÍ cierra

- **§1.5 OBS-E5a `--channel` admission anclado en test verde.** Antes de Run #28, el path OBS-E5a era la **única clase OBS-E sin ancla dedicada** y la **foundation** sobre la que Runs #23-#27 construyen (FOLLOW-3 referencia `Stop` por channel; OBSERVE-3 channel reaches console lane; TAIL no channel-routing explícito). Run #28 enumera las 7 firmas con mutaciones declaradas y los dos confesos HF5.
- **End-to-end integration pin (CH-ADM-5)**: el único test que empieza en `argv` y termina en un record siendo excluido. Los demás unit tests del modelo `ObservationQuery(channels=...)` fueron lo que dejó la dimensión "decorative" sin anclar. **CH-ADM-5 cierra la integración que faltaba**: que el flag realmente cambie el filtro.
- **Decorative-dimension fail-mode (M-C1 cross-row collapse 4-way)**: una sola mutación ("stop passing state.channels") rompe **cuatro** invariantes a la vez. Es el failure-mode más catastrófico del set OBS-E (más que M-O1 que colapsa 3, y M-OC1 que colapsa 2). Run #28 ancla esa propiedad como la **dimensión sin la cual todo sigue compilando — pero el flag es fantasmagórico**.
- **Identity row colapsada también por M-C1**: además de CH-ADM-1/4/5, "a flag that never reaches the query leaves nothing behind" — la fila de identidad colapsa porque sin channels, la query es la identidad trivially. Esto es lo que **el primer draft no midió** y la mutación midió.
- **Trailing refusal (CH-ADM-2c)**: `--channel` después del script se rechaza como trailing, no se ignora. Coherente con REFUSAL tipado cross-typology (Run #23 FOLLOW-11, Run #24 LIMIT-7, Run #25 TAIL-8, Run #26 OBSERVE-9, Run #27 OUTCOME-5).
- **Multi-statement script posicional (CH-ADM-2b)**: `--channel ./foo.kts` se nombra como "you wrote a script at the channel-value position" — no se traga. Pin-point específico que evita el silent-failure más sutil del CLI.
- **Cross-flag typo refusal (CH-ADM-3)**: token desconocido se nombra a sí mismo + vocabulario (`stdin|stdout|stderr`). Pin-point necesario para que un typo como `--channel sdtout` no se silenciosamente-matchee.

## 4. Lo que Run #28 NO cierra

- **No cubre process-side `pipelinek run --channel stderr …` contra el binario instalado.** Run #20 ancló process-side para `console` y `events` happy/error; el sub-comando `run` con `--channel` queda pendiente si se requiere process-side.
- **No cubre la combinación `--channel` × flag compossibility** (e.g. `--channel stderr --tail-bytes 4096 --limit 50 --outcome failure`). Probable extension con un test cross-flag composition.
- **No cubre la matriz `--channel` × view × follow**. Run #23 (FOLLOW) y Run #26 (OBSERVE) parcial. La combinación end-to-end del canal con `--follow` y `--tail-bytes` simultáneos no está enumerada en el suite.
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #28

- **Verde acumulado in-VM**: 1595 (post Run #27) + 7 (Run #28) = **1602 tests verde sobre `a74fa29f`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #28 añade 1, total 27 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 13 + este (Run #28) = 14.

## 6. Anchors del contrato OBS-E5a en CI

| Firma OBS-E5a | Anchor | Killing mutation | Run | Path |
|---|---|---|---|---|
| `--channel` reaches `ObservationQuery` | `CH-ADM-1` | M-C1 (con 4+5+identity) | #28 | in-VM |
| `--channel` sin valor → refusal | `CH-ADM-2` | (pin refusal) | #28 | in-VM |
| Script en lugar de value → se nombra explícitamente | `CH-ADM-2b` | M-C2 (con 3) | #28 | in-VM |
| `--channel` después del script → trailing refusal | `CH-ADM-2c` | (pin trailing refusal) | #28 | in-VM |
| Token desconocido → se nombra + expone vocabulario | `CH-ADM-3` | M-C2 (con 2b) | #28 | in-VM |
| Repetir el flag → union within=`OR`, across=`AND` | `CH-ADM-4` | M-C1 (con 1+5+identity) + M-C3 alone | #28 | in-VM |
| `argv → buildObservationQuery → accepts` end-to-end excluye stdout record cuando channel=stderr | `CH-ADM-5` | M-C1 (con 1+4+identity) | #28 | in-VM |

**7 nuevos anchors OBS-E5a in-VM sobre `a74fa29f`, con 3 mutaciones killing declaradas y una cross-row collapse de 4 filas (M-C1).**

**Run #28 cierra el set OBS-E completo**: a/b/c/d/e/f, 5 receipts en Runs #23-#27 + 1 en Run #28 = **6 sub-ramas OBS-E exhaustivamente ancladas con HF5-by-design**.

## 7. Comparativa de disciplina HF5 entre OBS-E5 set completo (a/b/c/d/e/f)

| Path | Tests | Mutaciones killing | Cross-row collapse máxima | Run |
|---|---|---|---|---|
| OBS-E5a `--channel` admission (Run #28) | 7 | 3 (`M-C1..M-C3`) | 4 (M-C1 → CH-ADM-1+4+5+identity) | #28 |
| OBS-E5b `observe` refusal matrix (Run #26) | 9 | 3 (`M-O1..M-O3`) | 3 (M-O1 → OBSERVE-4+5+6) | #26 |
| OBS-E5c `--follow` (Run #23) | 11 | (ADT by construction) | (no deck) | #23 |
| OBS-E5d `--limit` (Run #24) | 8 | 4 (`M-L1..M-L4`) | 1 (cada uno alone) | #24 |
| OBS-E5e `--tail-bytes` (Run #25) | 8 | 3 (`M-T1..M-T3`) | 2 (M-T1 → TAIL-2+4) | #25 |
| OBS-E5f `--outcome` (Run #27) | 6 | 3 (`M-OC1..M-OC3`) | 2 (M-OC1 → OUTCOME-1+4) | #27 |

**El set OBS-E completo tiene tres mutaciones cross-row collapse (M-C1=4, M-O1=3, M-T1 y M-OC1=2 cada una, M-L1..M-L4=1 cada una) y un ADT-by-construction (OBS-E5c). Run #28 ancla el collapse más catastrófico: M-C1 vuelca 4 filas con un solo cambio** (stop passing state.channels), incluyendo la **identity row** que ningún otro deck protege.

> **Orden cronológico OBS-E:** OBS-E5a (channel, fundamental) precede a OBS-E5b (observe refusal matrix sobre el query compartido). Runs en orden descendente #28..#23 reconstruyen la cadena.

## 8. Limitaciones operativas

- **Load 1m ~19 al run**: ventana más favorable de los Runs recientes. in-VM aguanta.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03). El back-log §6.2 (12 tests cross-process formales) sigue pendiente.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **§0.3/§8**: sigue `BLOCKED_EXTERNAL` sin tu autorización expresa. **1602 verde sobre `a74fa29f` es la mejor frontera defendible de sesión sin release**: las 6 sub-ramas OBS-E (a/b/c/d/e/f) están ancladas con HF5-by-design, los unit tests in-VM cierran el set §1.5 por completo, Run #21 sigue siendo el único cross-process Gradle pool verde (load 1m<5) del ciclo, Run #20 sigue anclando process-side §12 contra el binario instalado.
3. **No quedan OBS-E test classes sin ancla dedicada**: tras Run #28, las 6 clases `ObsE5*Test.kt` en `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/` tienen Run receipts.
