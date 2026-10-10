# Run #36 — `ObservationOperationIdShapeTest` in-VM ancla §1.5 OBS-E3 op-id identity (Bloque E · OBS-R1 / OBS-E3)

| | |
|---|---|
| **Run** | #36 |
| **Objetivo** | Anclar **§1.5 OBS-E3** operation-id identity: el operation-id que el sistema escribe es `run-7/run-7-s0-0/stdout` (3 segments con `runId` como prefijo Y dentro del node), **NO** el `StepId` `build/sh-0`. La muercurious defect era que un reader que construía `build/sh-0` recibía "no stream found" para todo step del run, lo que se leía como "step imprimió nada" en lugar de "id mal construido". |
| **SHA verificado** | `16ee20e1433740da9786d5366718ff930d03fb07` (HEAD post Run #35, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.observation.ObservationOperationIdShapeTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~15,16 (al inicio). Suite 0.508 s. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #36 cierra

El KDoc del test class —`ObservationOperationIdShapeTest.kt:18-65`— declara el invariante y el **failure-mode invisible**:

> *"OBS-E3: an operation id is an OPERATION identity, and the identity it gets confused with produces a reader that silently finds nothing.*
>
> *`OutputStreamAddress`'s KDoc used to state that 'the canonical operation id is `build/sh-0` — stage and step', and worked an example from it: the stdout stream is `run-7/build/sh-0/stdout`, four segments. **Both halves were wrong**, and together they were a trap for exactly the code this block was about to write.*
>
> *Production mints the operation id with [OpId.format], whose shape is `{runId}-s{stageIndex}-{stepIndex}[-b{branch}][-bp{N}-{childIndex}:{pluginStepId}...]`. An `sh` in stage 0, step 0 of run `run-7` therefore writes to `run-7/run-7-s0-0/stdout`: **three** segments, with the run id appearing both as the run and inside the operation.*
>
> *`build/sh-0` is a **StepId** — the definition-local node id, and the value that reaches the journal as `OperationInput.stepId`. It is a real identity with a real consumer; it is simply not this one."*

**Failure-mode invisible cita literal:** *"Because `parse` accepts both, and the failure is invisible. A reader that built `build/sh-0` from a step event and asked the store for that stream gets nothing for every step in the run — which reads as 'this step printed nothing', a completely ordinary fact, rather than as the mistake it is."*

Las **siete firmas ejecutadas** cubren tres tipos de anclaje distintos dentro de la misma test class — Run #36 ancla los tres juntos:

| Test | Cubre OBS-E3 | Tipo | Resultado medido |
|---|---|---|---|
| `ROW-SHAPE-1 the operation id is the operation identity and not the step identity` | `OpId.format()` produce `run-7-s0-0` (3 segments, no `build/sh-0` mal colocado) | Pure-derivation (HF0) | verde, 0.001s |
| `ROW-DIVERGE-2 the step id and the operation id address different streams` | `OpId(runId, 0, 0).format()` y `"build/sh-0"` resuelven a **distintos** `OutputStreamAddress` (mismatch explícito) | Pure-derivation (HF0) — **M-E4 anchor** | verde, 0.004s |
| `ROW-KDOC-3 the output contract names the step id only when it says it is a StepId` | Scan sobre la OUTPUT CONTRACT (KDoc de `OutputStreamAddress`) — busca "build/sh-0" y falla si lo encuentra fuera de frases que digan explícitamente "StepId" | **Source-scan** (HF5 estructural) | verde, 0.467s |
| `ROW-KDOC-4 the documented shape matches the derived one` | El KDoc DECLARED MATCHES el shape derivado (`run-7-s0-0` mismo string) | Pure + KDoc content check | verde, 0.002s |
| `ROW-PARSE-5 parse round-trips an operation id that spans several segments` | `OpId.format()` → `parse(...)` es round-trip (no mangle) | Pure-derivation (HF0) | verde, 0.018s |
| `ROW-PARSE-6 a pre-OBS-C2 transcript stream is not a channel stream` | Un stream pre-fix (`build/sh-0`) **NO** parse como channel-stream; parse falla o devuelve forma distinta | Pure-derivation | verde, 0.001s |
| `ROW-PARSE-7 the ends are what parse uses` | `parse(...)` discriminator usa los ends del string para routing | Pure-derivation | verde, 0.008s |

> **M-E4 anclado por ROW-DIVERGE-2:** *"M-E4 — replacing the operation id with the step id `build/sh-0` — is expected to kill ROW-SHAPE-1 and ROW-DIVERGE-2."* Si alguien sustituye la operación por step-id, el stream-address diverge — el reader encuentra "no stream" para cada step.
>
> **M-E5 anclado por ROW-KDOC-3:** *"M-E5 — writing `build/sh-0` back into the KDoc as the canonical operation id — is expected to kill ROW-KDOC-3, which is a scan rather than a behavioural row because the defect is in a comment and no runtime assertion can observe it."* ROW-KDOC-3 hace source-scan sobre `OutputStreamAddress`'s KDoc — la misma **HF5-source-scan variante** que Runs #30 y #34, aplicada sobre una meta-capa (el contrato del output-plane). Run #36 ancla **la discipline replicada sobre KDoc de contratos**, no sólo sobre código de implementación.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.observation.ObservationOperationIdShapeTest.xml` (1763 bytes, mtime 2026-10-10 03:06:19):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.observation.ObservationOperationIdShapeTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-10-10T01:06:18.794Z" hostname="bazzite-rubentxu" time="0.508">
  <testcase name="ROW-KDOC-3 the output contract names the step id only when it says it is a StepId()" time="0.467"/>
  <testcase name="ROW-PARSE-5 parse round-trips an operation id that spans several segments()" time="0.018"/>
  <testcase name="ROW-PARSE-7 the ends are what parse uses()" time="0.008"/>
  <testcase name="ROW-KDOC-4 the documented shape matches the derived one()" time="0.002"/>
  <testcase name="ROW-SHAPE-1 the operation id is the operation identity and not the step identity()" time="0.001"/>
  <testcase name="ROW-PARSE-6 a pre-OBS-C2 transcript stream is not a channel stream()" time="0.001"/>
  <testcase name="ROW-DIVERGE-2 the step id and the operation id address different streams()" time="0.004"/>
```

**7 tests / 0 fallos / 0 errores / 0 skipped.** Suite **0.508 s** — ROW-KDOC-3 es el caro (0.467s) por el walk de la OutputStreamAddress KDoc.

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación:**

- ROW-KDOC-3 (0.467s) — lee `OutputStreamAddress.kt`'s KDoc línea por línea, busca `"build/sh-0"` y reporta ocurrencias sin contexto "StepId". Si M-E5 reintrodujera el canonical operation id `build/sh-0` en el KDoc, esta fila **mataría con trace de líneas** específicas.
- ROW-SHAPE-1 (0.001s) — assertion de string equality; instantáneo.
- ROW-DIVERGE-2 (0.004s) — dos `OutputStreamAddress.of` calls + assertNotEquals.

`<system-out>` y `<system-err>` ambos vacíos — el suite no emite diagnóstico (pure-derivation in-memory).

BUILD SUCCESSFUL en 1m 35s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #36 SÍ cierra

- **§1.5 OBS-E3 operation-id identity anclado en test verde.** Antes de Run #36, el operation-id era un campo del OutputStreamAddress sin anclaje dedicado; el paso accidental a `build/sh-0` se leía como "step imprimió nada" — Run #36 lo detecta con ROW-SHAPE-1 + ROW-DIVERGE-2 en derivación, y ROW-KDOC-3 sobre la documentación del contrato (preventiva contra futuras regresiones de prosa).
- **HF5 source-scan aplicada sobre KDoc de contratos (no sobre código de implementación):** Runs #30 y #34 hicieron source-scan sobre código fuente ejecutable. Run #36 añade una nueva dimensión: source-scan sobre la **documentación** (KDoc) del contrato `OutputStreamAddress`. ROW-KDOC-3 escanea el KDoc buscando `"build/sh-0"` fuera de contexto explícito "StepId", failure type "future regression in prose". Las tres aplicaciones (código + código + KDoc) confirman que la HF5-source-scan se replica sobre **distintos tipos de artefactos**.
- **Trinidad dentro de OBS-E3 (derivación + KDoc-scan + parse-roundtrip):** una sola test class cubre tres dimensiones del mismo invariante — qué shape produce la derivación, qué shape documenta el contrato, qué shape admite el parse. Si una dimensión se desincroniza de las otras dos, el test rojo con trace específico (ROW-KDOC-3 lista líneas, ROW-DIVERGE-2 lista los dos streams, ROW-PARSE-* identifica el segmento).
- **Pre-OBS-C2 transcript streams handling (ROW-PARSE-6):** el test cierra explícitamente que `build/sh-0` (formato pre-fix) **no** parse como channel-stream — anclaje histórico. Sin esta fila, un cambio que permitiera el formato pre-fix como válido pasaría inadvertido.
- **Conexión cross-test class:** ROW-PARSE-6 menciona "pre-OBS-C2 transcript stream" — Run #30 ancló que `redirectErrorStream(true)` ya no existe. Run #36 ancla que el **formato de stream pre-OBS-C2 no parsea como channel stream**. Las dos cierran el dominio pre/post fix OBS-C2 desde dos ángulos: Run #30 mira el código, Run #36 mira el parsing.

## 4. Lo que Run #36 NO cierra

- **No cubre process-side con fork real.** El test cruza el productive authority sobre la derivación (`OpId.format()`), pero NO fork-ea `sh`. Run #31 OBS-C1 cubre el byte-level end-to-end (que un Run real con stage 0 step 0 escribe a `run-7/run-7-s0-0/stdout`, no a `run-7/build/sh-0/stdout`).
- **No cubre el scan sobre otras KDocs de contrato.** El KDoc de `OutputStreamAddress` es uno de muchos — si `MainConsoleCli.kt` documentara `build/sh-0` como canonical operation id, Run #36 no lo cazaría. Run #36 ancla **un solo contrato**, no toda la documentación del proyecto.
- **No cubre cross-process Gradle pool.** Pure-derivation + source-scan; Run #36 es in-VM puro.
- **No cubre `OpId.format()` con todos los parámetros** (e.g. `-b{branch}` o `-bp{N}-{childIndex}` plugins). El KDoc menciona el shape completo, pero el suite testea las filas mínimas. La mutación M-E4 (reemplazar op-id con step-id) cubre el caso simple; coverage más amplia requeriría tests parametrizados.

## 5. Cierre acumulado del Bloque E tras Run #36

- **Verde acumulado in-VM**: 1629 (post Run #35) + 7 (Run #36) = **1636 tests verde sobre `16ee20e1`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #36 añade 1, total 35 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 21 + este (Run #36) = 22.

## 6. Anchors del OBS-E3 en CI

| Firma OBS-E3 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| `OpId.format()` produce operation id correcto, no step id | `ROW-SHAPE-1` | Pure-derivation | #36 | in-VM |
| Step id y operation id resuelven a streams distintos | `ROW-DIVERGE-2` | Pure-derivation + M-E4 anchor | #36 | in-VM |
| KDoc del output contract nombra step id sólo cuando dice "StepId" | `ROW-KDOC-3` | **Source-scan sobre KDoc** | #36 | in-VM |
| Documented shape matches derived | `ROW-KDOC-4` | Pure + KDoc content check | #36 | in-VM |
| `parse` round-trips multi-segment operation id | `ROW-PARSE-5` | Pure-derivation | #36 | in-VM |
| Pre-OBS-C2 transcript stream NO parse como channel stream | `ROW-PARSE-6` | Pure-derivation (historical anchor) | #36 | in-VM |
| `parse` discriminator usa los ends del string | `ROW-PARSE-7` | Pure-derivation | #36 | in-VM |

**7 nuevos anchors OBS-E3 in-VM sobre `16ee20e1`** — primera aplicación de source-scan **sobre KDoc de contratos** dentro del Bloque E.

## 7. Cuadruplete del HF5-source-scan en Bloque E

| Aplicación | Anchor | Target | Run |
|---|---|---|---|
| Hexagonal architecture | OBS-C2.3 | código fuente SDK runtime | #30 |
| Recovery seam | OBS-Pc / ADR-OBS-002 | código fuente SegmentOutputStore | #34 |
| Channel fusion (re-aplicable) | OBS-C1 KDoc-amend | prose-only amend | #32 |
| **Output contract op-id identity** | **OBS-E3 ROW-KDOC-3** | **KDoc de OutputStreamAddress** | **#36** |

Run #36 confirma que la HF5-source-scan se replica sobre **distintos tipos de artefactos**: código ejecutable (Runs #30, #34), documentación de contratos (Run #36).

## 8. Limitaciones operativas

- **Load 1m ~15 al run**: ventana más estrecha; suite 0.5s aguantó.
- **Cross-process Gradle pool wedge** sigue activo en load>10.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes.
2. **Run #37 OPCIONAL** (in-VM, no requiere ventana):
   - `ObservationOutputFollowerTest` (OBS-E2 follower over `SegmentOutputStore`);
   - `ObservationOutputReaderTest` (OBS-D2 read port);
   - `ObservationQueryTest` (HF0 query read-side);
   - `ObservationWakeupTest` (OBS-D3 wakeups coalescible).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1636 verde sobre `16ee20e1` es la mejor frontera defendible de sesión sin release**.
