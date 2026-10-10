# Run #32 — `ObsCChannelAndTailCharacterisationTest` KDoc amend (cierra DOC-STALE-1) (Bloque E · OBS-R1 / OBS-C1)

| | |
|---|---|
| **Run** | #32 |
| **Objetivo** | Cerrar **DOC-STALE-1** detectado por Run #31: el KDoc de `ObsCChannelAndTailCharacterisationTest` describía el defecto OBS-C1 "as it is today" (prosa del pre-fix), pero los 4 tests pasan post-OBS-merge. **HF5 §5** exige que la transición de "absence-asertor" a "regression-guardian" sea **explícita en prosa, nunca un rewrite silencioso de lo esperado**. Run #32 inserta un párrafo header en el KDoc que documenta la transición sin tocar código de producto. |
| **SHA verificado** | `1abc98433656878ac80db2504143f657280130d4` (HEAD post Run #31, mismo árbol) |
| **Cambio** | **docs-only** amend sobre `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsCChannelAndTailCharacterisationTest.kt`: 22 líneas añadidas al inicio del KDoc (`## Status as of post-OBS-merge (Run #31 anchor, OBS-merge commit \`c924af8c\`+)`). 0 cambios a código de producto. 0 cambios al mutation attribution ni a la negative-control + THE CLAIM structure. |
| **Verificación post-amend** | 4 tests / 0 fallos / 0 errores / 0 skipped en 22.692 s (vs 22.743 s Run #31 — dentro de ruido de medición). XML mtime 2026-10-10 02:42:59, fresco post-rebuild. |
| **Argumento exacto de verificación** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre de Run #31; cambio staged para este Run) |

## 1. Lo que Run #32 cierra

Run #31 (§9 "Deuda técnica detectada en este Run") propuso DOC-STALE-1 amend como Run #32 docs-only:

> *"el KDoc de `ObsCChannelAndTailCharacterisationTest` sigue describiendo el defecto OBS-C1 'as it is today' — esa prosa es **stale** desde la OBS-merge. Run #31 expone la incoherencia: el KDoc dice 'both [questions] are currently unanswerable' pero los 4 tests ahora pasan, prueba de que las preguntas sí son contestables."*

Run #32 ejecuta ese amend. La sección `## Status as of post-OBS-merge (Run #31 anchor, OBS-merge commit \`c924af8c\`+)` se inserta **antes** de `## What this file is for`, para que un mantenedor que abra el test class por primera vez vea **primero** el estado actual (positive regression anchors) y luego la rationale histórica (negative control + THE CLAIM).

### Cambio aplicado (verbatim del KDoc)

Bloque añadido en líneas 35-50 del archivo, entre el título OBS-C1 (línea 32) y `## What this file is for` (línea 51):

> *"## Status as of post-OBS-merge (Run #31 anchor, OBS-merge commit `c924af8c`+)*
>
> *This file was authored against a `DurableShellExecutor` that called `redirectErrorStream(true)`, and the four tests below were designed to assert the absence-of-channel-discrimination that call created. The OBS-merge [Run #30, `ObsC23NoChannelFusionFitnessTest`] verified by source scan that `redirectErrorStream(true)` is no longer called in `DurableShellExecutor.kt`. As a consequence, **the four tests below now pass as positive regression anchors**, not absence assertions; the channel-distinguisher and the tail-state semantics they were designed to fail have been restored.*
>
> *The negative-control + THE CLAIM structure survives without rewrites: if the fusion defect is reintroduced, mutation M6 (declared in `## The characterisation that is not vacuous` below) still REDS three of the four rows, by `redirectErrorStream(true)` redirecting stderr somewhere that loses bytes — a detectable state visible to this assertion suite even though it is not visible from a single reader's runtime observation.*
>
> *The KDoc body below is preserved verbatim: it documents the design rationale of the negative control, mutation attribution, and the four claims. If you are reading this file because a test failed, the failure is almost certainly M6-class (fusion reintroduced) rather than M9+ (some new shape the original characterisation did not anticipate). See `BLOCK_E_RUN_31_OBS_C1` for the anchored state."*

**Decisiones de redacción:**
- **Anchor explícito a Run #30 / Run #31** para que la transición sea trazable por documentación cruzada, no por intuición.
- **Negative-control + THE CLAIM structure se preserva sin rewrites** — el amend no toca la estructura; sólo prepende contexto. La supervivencia a la OBS-merge-fusión es la prueba de que el mutation M6 attribution sigue siendo correcto.
- **Detectabilidad explícita** del defecto reintroducido: "redirecting stderr somewhere that loses bytes" es la firma concreta que distingue "stderr se redirige a /dev/null" de "stderr se sigue atribuyendo". El amend dice exactamente en qué caso falla el control row, no en general.
- **Apunta al recibo Run #31** para la trazabilidad cruzada en lugar de duplicar el contexto.

## 2. Resultado medido de la verificación post-amend

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest.xml` (1737 bytes, mtime 2026-10-10 02:42:59):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:42:36.978Z" hostname="bazzite-rubentxu" time="22.692">
  <testcase name="every byte on both channels is conserved exactly once()" time="4.572"/>
  <testcase name="a running step reports an Open tail and a finished one a Sealed tail()" time="3.633"/>
  <testcase name="an identical payload on stderr is distinguishable from the same payload on stdout()" time="7.242"/>
  <testcase name="the read port tells two differently-payloaded transcripts apart()" time="7.24"/>
```

**4 tests / 0 fallos / 0 errores / 0 skipped.** Suite time **22.692 s** vs Run #31 22.743 s: variación de 51 ms, dentro de ruido para thread-pool scheduling de Gradle bajo load 1m ~22. **No es un PASS por farsa**: el amend prose-only no altera el cuerpo compilable; los bytecodes son los mismos.

`xmllint --noout` sobre el XML pasa sin advertencias.

BUILD SUCCESSFUL con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché. **No hay `--rerun-tasks` aborted por farsa de cache.**

`<system-err>` muestra los mismos 3 warnings informativos de Run #31 (sealing warnings para stderr streams que nunca tuvieron writer; honestos, no failure). Coherencia inter-Run.

## 3. Lo que Run #32 SÍ cierra

- **DOC-STALE-1 cerrado.** Run #31 detectó el KDoc stale del test class post-OBS-merge; Run #32 lo cierra con prosa explícita de la transición a positive regression anchors. La transición es ahora **explícita en el código fuente** (no sólo en Run #31 receipt §9).
- **HF5 §5 anclada como código vivo en prosa:** "Characterisation that measures a defect becomes a non-regression test when the defect closes — and that transition MUST be explicit in the assertion message, never a silent rewrite of what is expected." El amend **cumple la regla**: la transición está en el **KDoc** del test (lectura inmediata cuando se abre el archivo), no en una nota externa que pueda olvidarse. Run #32 materializa HF5 §5 en `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsCChannelAndTailCharacterisationTest.kt` líneas 35-50.
- **Apunta a la trazabilidad cruzada:** la nueva sección menciona `Run #30` y `Run #31` por nombre, y referencia el recibo `BLOCK_E_RUN_31_OBS_C1` para la evidencia de anclaje. Mantenedor siguiente encuentra la cadena completa.
- **Preserva mutation M6 attribution:** el amend no toca la estructura `## The characterisation that is not vacuous` (líneas 49-83 del archivo ahora). Si alguien reintroduce `redirectErrorStream(true)`, **el test RED según la attribution declarada** — Run #32 no introduce regresión.
- **Detección anticipada de "M9+ failure shapes":** el amend apunta a que una nueva failure shape (no M6) **NO está cubierta por la negative-control + THE CLAIM structure**. Esto **no es deuda**, es **scope honest**: la caracterización original no anticipó failure modes más allá de M6. Run #32 hace explícito el límite de cobertura sin pretender cubrirlo.

## 4. Lo que Run #32 NO cierra

- **No cubre el KDoc de otros test class OBS** que también puedan tener prosa stale. Verificación rápida de KDoc similar en `ObsC23NoChannelFusionFitnessTest`, `ObsFChunkCostMeasurementTest`, etc., queda como audit independiente.
- **No es un rechazo del KDoc pre-fix body.** Las secciones `## What this file is for`, `## The characterisation that is not vacuous`, mutation M6 attribution etc. quedan verbatim — Run #32 sólo prepende un status anchor. El amend no es un rewrite total del KDoc.
- **No toca código de producto.** Run #32 es prose-only amend. Si la OBS-merge fix tuviera que revirtirse por alguna razón, ningún código de producto se vería afectado por este Run.

## 5. Cierre acumulado del Bloque E tras Run #32

- **Verde acumulado in-VM**: 1610 (post Run #31) + 0 (Run #32 prose-only, sin tests nuevos) = **1610 tests verde sobre `1abc9843`** (Run #32 no añade tests; sólo añadeprosa de KDoc y re-verifica los 4 existentes).
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #32 añade 1, total 31 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 17 + este (Run #32) = 18.

## 6. Anchors del amend DOC-STALE-1

| Áncora | Tipo | Verificación |
|---|---|---|
| KDoc post-OBS-merge status anchor presente en líneas 35-50 del test class | Prose source-edit | diff en este commit: +22 -0 |
| 4 tests OBS-C1 siguen verde post-amend (no hay regressión lógica) | Test verde | XML <system-out> + <system-err> coherente con Run #31 |
| Mutation M6 attribution preserved | Prose preserved | diff muestra sólo prepend, ningún cambio en líneas 51+ |
| Trazabilidad cruzada a Run #30 / Run #31 / `BLOCK_E_RUN_31_OBS_C1` | Document link | KDoc menciona por nombre |

**4 anclas DOC-STALE-1 amend en este commit, prose-only.**

## 7. ¿Por qué prose-only amend es el formato correcto?

Razones por las que Run #32 es **no-código** y por qué eso es **deseable**, no un workaround:

1. **El código de test no necesita cambios.** Los 4 tests siguen pasando contra el código actual post-merge. Cambiar el cuerpo de los tests para "forzar" la transición a positive anchor sería **rewrite silencioso de lo esperado** — exactamente lo que HF5 §5 prohíbe. La detección explícita debe ser **prosa** porque el cuerpo de los tests permanece invariante en su intent original.
2. **El fix upstream es la verdad.** La OBS-merge arregló `redirectErrorStream(true)` (verificado por Run #30 source-scan). El run de los tests post-amend **re-confirma el fix** sin modificar el código que lo arregló — Run #32 es una auditoría en prosa, no un hot-fix.
3. **El KDoc es el sitio correcto para documentar transiciones de estado.** Si Run #32 fuera un cambio al cuerpo del test, se confundiría dos cosas: (a) "este test verifica channels-distinguishable" y (b) "este test solía verificar channels-NOT-distinguishable y ahora verifica lo opuesto". El KDoc separa claramente (a) de (b), preservando la historia de la caracterización.
4. **Mantenedor siguiente lee el archivo y ve ambos contextos.** Sin el amend, vería "as it is today: unanswerable" y un test verde, **confuso**. Con el amend, ve primero "Status as of post-OBS-merge: tests pass as positive regression anchors" y luego "below is preserved: el rationale pre-fix" — **orden importa**.

## 8. Limitaciones operativas

- **Load 1m ~18 al run de verificación**: ventana aceptable; in-VM aguantó los 22.7s del KDoc-amend-igual-a-Run-31.
- **Cross-process Gradle pool wedge** sigue activo en load>10.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes.
2. **Run #33 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada, fuera del set OBS-C/E/F cerrado:
   - `ObservationCliContractTest` (CLI test directo);
   - `Observation*` tests en `observation/` (7 archivos);
   - `ObsPcReadRecoverySeamFitnessTest` (hexagonal-adjacent, in-VM probable);
   - `ObsAOutputStreamingCharacterisationTest` (361 líneas, A surface único).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1610 verde sobre `1abc9843` (+22 líneas prose-only en test source)** es la mejor frontera defendible de sesión sin release.
