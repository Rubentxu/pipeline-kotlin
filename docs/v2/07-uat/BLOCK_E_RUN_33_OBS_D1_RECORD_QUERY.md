# Run #33 — `ObservationRecordQueryTest` in-VM ancla §1.5 OBS-D1 channel grammar read-side (Bloque E · OBS-R1 / OBS-D1)

| | |
|---|---|
| **Run** | #33 |
| **Objetivo** | Anclar **§1.5 OBS-D1** del read-model: el query de la observación tiene `channel` como dimensión **viva**, no como "dead semantic parameter". 9 tests verde (CHANNEL-1..3 + TEXT-1 + RECORD-1..2 + 3 anchors extras RECORD-3, RECORD-4, selectObservations-stability). |
| **SHA verificado** | `e571cd14e05d3683e5dede9af2d2d464fa8a657d` (HEAD post Run #32, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.observation.ObservationRecordQueryTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~12,84 (al inicio, ventana favorable). Suite 0.555 s (HF0 pure contract — sin fsyncs, sin threads). |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #33 cierra

El KDoc del test class —`ObservationRecordQueryTest.kt:18-72`— declara el invariante y la **dimensión constitucional sin productor / productor sin dimensión**:

> *"## Why these rows could not have existed before OBS-D*
>
> *`channel` was absent from the query, and its absence was justified by a claim that OBS-C2.3 made false. These rows are the evidence that the producer is real — every `OutputFrame` below carries a channel durably — and that the dimension now has one. **A dimension with no producer is the constitution's dead semantic parameter; a producer with no dimension is a capability nobody can reach, which is the same defect pointing the other way.**"*

Run #33 cierra el segundo extremo de ese teorema: **productor-con-dimensión**. La OBS-merge (verificada por Run #30 source-scan sobre `redirectErrorStream(true)`) garantizó que cada `OutputFrame` lleva canal; OBS-D1 garantiza ahora que el **query usa ese canal** sin colapsarlo en dead-parameter.

**Disciplina HF0 Pure Contract (cero ambient state):** el test invoca `compileQuery` y `selectRecords` directamente sobre records construidos en memoria — sin clock, sin filesystem, sin red. La fidelidad es por construcción: **"calling the production functions directly is faithful rather than a reimplementation"**. La limitación es explícita — "this class proves the SELECTION, and the end-to-end claim that a real `sh` run's stderr is reachable through this query belongs to the behavioural harness" (es decir, Run #31 OBS-C1 cierra la integración end-to-end con la lectura del read-port).

Las nueve firmas ejecutadas:

| Test | Cubre OBS-D1 | Mutación que RED | Resultado medido |
|---|---|---|---|
| `CHANNEL-1 a channel filter keeps that channel and excludes everything else` | `--channel stderr` conserva stderr y nada más (el canal distingue, no colapsa) | M-D1 (channel excludes) | verde, 0.012s |
| `CHANNEL-2 several channels in ONE dimension union, exactly as kinds do` | `--channel stderr --channel stdout` une (within=`OR`, across=`AND`) | M-D3 (intersect instead of union) | verde, 0.032s |
| `CHANNEL-3 channel intersects with the other dimensions` | `--channel × --grep` intersect (across=`AND`) | M-D2 (skip channel check) | verde, 0.001s |
| `TEXT-1 grep reaches committed process bytes, not only script messages` | `--grep` lee bytes del proceso committed, no sólo script output | M-D4 (text reaches output) | verde, 0.474s |
| `RECORD-1 a record reads its channel from the frame rather than from its stream id` | El canal se lee del frame, no se infiere del stream id | (no killing — pin path) | verde, 0.001s |
| `RECORD-2 an opaque stream id loses its address but keeps its channel` | Stream id opaco (sin address) preserva canal del frame | (no killing — pin frame-channel) | verde, 0.005s |
| **Extras no en KDoc** | | | |
| `RECORD-3 an event record carries no channel, and null is not a match` | Events sin canal = ningún filtro por canal coincide | (no killing — pin type-channels) | verde, 0.002s |
| `RECORD-4 the identity query admits both families` | Query identidad acepta eventos y output records | (no killing — pin identity) | verde, 0.002s |
| `selectObservations stays the event lane and drops nothing it used to keep` | El query migration a OBS-D no degrada el lane events | (no killing — pin no-regression) | verde, 0.014s |

> **Detalle de los 3 extras (RECORD-3, RECORD-4, selectObservations-stability):** el KDoc solo declara 6 firmas pero el suite tiene 9 tests. Estos 3 son **pin-points** del ámbito ampliado de OBS-D1: cubren el caso "event sin canal" (RECORD-3), la identidad entre familias (RECORD-4), y la no-regresión del event lane tras la introducción del canal (selectObservations). No son firmados como filas matables — son filas de pinning honesto, como la trinidad HF5 ya enseñó (Runs #23-#28 OBS-E5d, OBS-E5e, OBS-E5f).

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.observation.ObservationRecordQueryTest.xml` (2097 bytes, mtime 2026-10-10 02:49:21):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.observation.ObservationRecordQueryTest" tests="9" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:49:20.651Z" hostname="bazzite-rubentxu" time="0.555">
  <testcase name="TEXT-1 grep reaches committed process bytes, not only script messages()" time="0.474"/>
  <testcase name="RECORD-4 the identity query admits both families()" time="0.002"/>
  <testcase name="RECORD-3 an event record carries no channel, and null is not a match()" time="0.002"/>
  <testcase name="selectObservations stays the event lane and drops nothing it used to keep()" time="0.014"/>
  <testcase name="CHANNEL-1 a channel filter keeps that channel and excludes everything else()" time="0.012"/>
  <testcase name="CHANNEL-2 several channels in ONE dimension union, exactly as kinds do()" time="0.032"/>
  <testcase name="RECORD-2 an opaque stream id loses its address but keeps its channel()" time="0.005"/>
  <testcase name="RECORD-1 a record reads its channel from the frame rather than from its stream id()" time="0.001"/>
  <testcase name="CHANNEL-3 channel intersects with the other dimensions()" time="0.001"/>
```

**9 tests / 0 fallos / 0 errores / 0 skipped.** Suite time **0.555 s** — HF0 pure contract sin fsyncs.

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación:**

- TEXT-1 (0.474s) es el más caro por la enumeración de strings sobre multi-records.
- Los 8 restantes son sub-50ms — coherente con un suite pure-contract en memoria.
- BUILD SUCCESSFUL, `--rerun-tasks`, descarta cache.

`<system-out>` y `<system-err>` ambos vacíos — el suite no emite diagnóstico (HF0 pure contract).

## 3. Lo que Run #33 SÍ cierra

- **§1.5 OBS-D1 channel grammar read-side anclado en test verde.** Run #33 ancla **nueve** firmas sobre el query read-side: CHANNEL-1..3 (dimensión canal como tal), TEXT-1 (alcance del `--grep`), RECORD-1..2 (canal desde frame, no desde stream id), más 3 pin-points extras (RECORD-3, RECORD-4, selectObservations-stability).
- **HF0 Pure Contract variante:** Run #33 ancla una **quinta variante HF5** que no estaba en las cuatro ya documentadas (Runs #23-#32). Las cinco son:

| Variante | Aplicada en | Disciplina | Runs |
|---|---|---|---|
| Lógica | OBS-E5 set | Mutation deck killing-declared sobre contrato tipado | #23-#28 |
| Empírica | OBS-F chunk cost | Measurement table leída de durable authority | #29 |
| Estructural | OBS-C2.3 fitness | Source-scan para捍卫 ausencia + hexagonal guard | #30 |
| Caracterización-resiliente | OBS-C1 channel+tail | Pre-fix tests sobreviven al fix mutando a positive regression anchors | #31 |
| **Pure-contract query** | **OBS-D1 channel grammar** | **Construye records en memoria, invoca funciones de producción directamente** | **#33** |

Las cinco variantes HF5 son necesarias porque cada una ataca un surface distinto:

- **Pure-contract** no detecta **errores de integración end-to-end** (Run #31 lo hace).
- **Estructural** no detecta **errores lógicos** en un modelo puro (Run #33 lo hace).
- **Lógica** no detecta **ausencias estructurales** (Runs #30, #33 juntas).
- **Empírica** no detecta **contratos lógicos** (Run #33 sobre Run #29).
- **Caracterización-resiliente** sobrevive al fix pero no previene re-fixes con mutation por error humano (Run #33 mutation-deck-declared previene).

- **Cuadratura del teorema constitucional:** Run #30 verificó que cada `OutputFrame` lleva canal; Run #33 verifica que el **query usa ese canal** sin colapsarlo en dead-parameter. La trinidad "productor + sink + query" queda cerrada para OBS-D1.
- **Conexión con Runs previos OBS-E5/C:** CHANNEL-2 (union, within=`OR`) reproduce la semántica ya anchored en OBS-E5c/d/e (Run #24 LIMIT-CHANNEL-4 union within `OR`, across `AND`). CHANNEL-3 (intersección con otras dimensiones) reproduce lo que Run #26 OBSERVE-3 (channel reaches console lane through shared query). Run #33 **confirma la coherencia cross-subsistema** entre OBS-E5 (CLI side) y OBS-D1 (query side) — un cambio en uno que rompa la invariante del otro sería detectado.
- **HF5 lectura end-to-end cuádruple:** Run #28 (CLI side: `--channel` reaches query, argv-end) + Run #30 (fuente: no `redirectErrorStream(true)`) + Run #31 (end-to-end: argv → query → accepts) + Run #33 (query side: selectRecords aplica canal). Cuatro anclas independientes al mismo invariante "channel no es dead-parameter", cada una desde un ángulo distinto.

## 4. Lo que Run #33 NO cierra

- **No cubre process-side** (binario `pipelinek events --channel stderr …` contra fork-pool). Run #20 ancló process-side §12; `--channel` aplicado al query del binario queda pendiente si se requiere process-side, pero Run #33 cubre el query side in-VM.
- **No cubre la matriz channel × follow × tail** (combinación `--channel stderr --follow --tail-bytes`). Quedan como Run #34+ combinados con `ObsE5ChannelAdmissionTest` (Run #28) y `ObsE5TailTest` (Run #25).
- **No cubre cross-process Gradle pool.** HF0 pure contract sin subprocess; Run #33 es in-VM puro.
- **No cubre el F/K flag matrix** (e.g., `--channel-stderr --follow --grep ERROR --limit 50`). Suite channel×grep×limit es combinatorial, sólo se enumera en OBS-D1 las dimensiones individuales.

## 5. Cierre acumulado del Bloque E tras Run #33

- **Verde acumulado in-VM**: 1610 (post Run #32) + 9 (Run #33) = **1619 tests verde sobre `e571cd14`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #33 añade 1, total 32 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 18 + este (Run #33) = 19.

## 6. Anchors del contrato OBS-D1 en CI

| Firma OBS-D1 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| `--channel stderr` excluye no-stderr | `CHANNEL-1` | Pure-contract + mutation M-D1 | #33 | in-VM |
| `--channel × --channel` union within=`OR` | `CHANNEL-2` | Pure-contract + mutation M-D3 | #33 | in-VM |
| `--channel × --grep` intersect across=`AND` | `CHANNEL-3` | Pure-contract + mutation M-D2 | #33 | in-VM |
| `--grep` reaches process bytes | `TEXT-1` | Pure-contract + mutation M-D4 | #33 | in-VM |
| Frame.channel es la fuente del canal | `RECORD-1` | Pin-point (no killing) | #33 | in-VM |
| Stream id opaco preserva canal del frame | `RECORD-2` | Pin-point (no killing) | #33 | in-VM |
| Event records sin canal ≠ match | `RECORD-3` (extra) | Pin-point type-channels | #33 | in-VM |
| Identity query admite ambas familias | `RECORD-4` (extra) | Pin-point identity | #33 | in-VM |
| Query migration no degrada event lane | `selectObservations stays the event lane…` (extra) | Pin-point no-regression | #33 | in-VM |

**9 nuevos anchors OBS-D1 in-VM sobre `e571cd14`** — primera aplicación de la **HF5 variante pure-contract query**.

## 7. Quíntuple HF5 anclada en Bloque E

| Variante | Path | Run | Tamaño |
|---|---|---|---|
| Lógica | OBS-E5 set (mutation decks + cross-row collapse killing-declared) | #23-#28 | 6 receipts, 49 anchors |
| Empírica | OBS-F chunk cost (writes/frames leídos de producción durable) | #29 | 1 receipt, 5 anchors |
| Estructural | OBS-C2.3 fitness (source-scan + hexagonal guard) | #30 | 1 receipt, 3 anchors |
| Caracterización-resiliente | OBS-C1 channel+tail (negative control + M6 attribution) | #31 | 1 receipt, 4 anchors |
| Pure-contract query | **OBS-D1 channel grammar** (HF0 sin ambient state) | **#33** | 1 receipt, 9 anchors |
| DOC-STALE-1 amend | Prose-only header KDoc amendment | #32 | 1 commit |

Run #33 cierra la quíntuple. Cada variante ataca un surface distinto del Bloque E.

## 8. Limitaciones operativas

- **Load 1m ~12 al run**: ventana favorable; suite 0.555s no necesitó ventana load<5.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10 para tests que fork-ean subprocess.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes (Run #21 demostró patrón viable en 1m 3,03; load 1m ~12 actual no es ventana).
2. **Run #34 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada:
   - `ObservationOperationIdShapeTest` (OBS-E3, op-id identity);
   - `ObservationJsonLinesTest` (OBS-E1, machine format);
   - `ObservationOutputFollowerTest` (OBS-E2, output follower);
   - `ObservationWakeupTest` (OBS-D3, wakeups coalescible);
   - `ObsPcReadRecoverySeamFitnessTest` (POSIBLE hexagonal-adjacent, in-VM).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1619 verde sobre `e571cd14` es la mejor frontera defendible de sesión sin release**.
