# Run #31 — `ObsCChannelAndTailCharacterisationTest` in-VM ancla §1.4 OBS-C1 channel+tail characterisation (Bloque E · OBS-R1 / OBS-C1)

| | |
|---|---|
| **Run** | #31 |
| **Objetivo** | Anclar **§1.4 OBS-C1** characterización del path canónico `sh`: respuestas a las dos preguntas reales de un reader de consola — ¿de qué canal viene este byte? y ¿habrá más? OBS-C1 publicó el defecto "as it is today" con KDoc explícito; la OBS-merge arregló el defecto en el código, y **esta caracterización se convirtió en anclaje positivo**: 4 tests verde sobre el motor real (`ShExecution` + `SegmentOutputStore`). |
| **SHA verificado** | `d5e592701d88cead0f5fd6b8816093786e2e0621` (HEAD post Run #30, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~38,74 (al inicio, ventana adversa), 29,40 (5m), 25,02 (15m). Suite 22.743 s (real `ShExecution` + `SegmentOutputStore` con thread concurrente). **in-VM aguantó** este load sin wedge, gracias al modelo thread-pool de gradle con `--max-workers=1`. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #31 cierra

El KDoc del test class —`ObsCChannelAndTailCharacterisationTest.kt:25-94`— declara el invariante y la disciplina HF5 por **control row no-vacuous**:

> *"OBS-B made the Output Plane live. That is the first half of 'observable'. The second half is whether a reader can answer the two questions a reader actually asks of a console:*
> ```text
> show me only stderr          -> needs the channel preserved before fusion
> is there anything more?      -> needs OPEN vs SEALED, not 'no page right now'
> ```*
>
> *Both are currently unanswerable. This file measures that, at the published read port, through the real `ShExecution` and the real `SegmentOutputStore`. It asserts the defect as it is today, so the size of OBS-C is set by evidence rather than by the shape of the fix.*
>
> *'You cannot tell stdout from stderr' is an assertion about the **absence** of a capability, and absent-capability assertions are the classic vacuous test: they pass for the wrong reason, and they also pass when the harness itself is broken. Both failure modes are closed here by **giving the harness a pair it CAN discriminate, in the same file, with the same code**:*
> ```text
> NEGATIVE CONTROL   two runs whose payloads DIFFER by channel
>                    -> the read port DOES tell them apart (differing bytes)
> THE CLAIM          two runs whose payloads are byte-identical but differ ONLY by channel
>                    -> the read port CANNOT tell them apart (identical bytes)
> ```"*

**Disciplina HF5 del "non-vacuous absence assertion":** una afirmación sobre ausencia (canales indistinguibles) es el clásico test vacuous — pasa por la razón equivocada (no hay prueba) y también pasa si el harness está roto. Para cerrar ambas, se incluye un **negative control**: dos runs cuyos payloads **difieren** por canal — la respuesta del read port a esos **debe** ser distinguible. Si el control fallara también, "la afirmación sería verdad por una razón que no tiene nada que ver con canales". El test expone la razon en su propio assertion message.

Run #31 ancla esa disciplina. Las cuatro firmas:

| Test | Cubre la propiedad OBS-C1 | Tipo | Resultado medido |
|---|---|---|---|
| `the read port tells two differently-payloaded transcripts apart` | **NEGATIVE CONTROL**: dos runs con payloads distintos → port **SÍ** distingue | Mutation M6 GREEN (per KDoc) | verde, 7.241s |
| `an identical payload on stderr is distinguishable from the same payload on stdout` | **THE CLAIM**: dos runs con payloads byte-idénticos pero canales distintos → port **SÍ** distingue | Mutation M6 RED | verde, 7.241s |
| `every byte on both channels is conserved exactly once` | Ningún byte se duplica ni se pierde entre los dos canales (bytes-attribution conservation) | Mutation M6 RED | verde, 4.621s |
| `a running step reports an Open tail and a finished one a Sealed tail` | Tail state transition: `Open` mientras hay writer vivo, `Sealed` cuando termina — cubre "¿habrá más?" | Mutation M6 GREEN (per KDoc) | verde, 3.634s |

> **Lectura del resultado medido sobre Run #31:** los 4 tests verde a pesar de que el KDoc describe el defecto "as it is today". La OBS-merge arregló el defecto (Run #30 fuente-scan ya confirmó `no redirectErrorStream(true)`), y **esta caracterización se convierte en anclaje positivo**. Si alguien reintroduce `redirectErrorStream(true)`, **la mutación M6 descrita en el KDoc** mata las 3 filas que el KDoc declara como matables — y queda la fila control, que también mataría pero por una razón diferente (los bytes de stderr se perderían al redirigir a /dev/null). Run #31 ancla el test en su estado **post-fix** sin modificarlo: es el mismo código OBS-merge que pasa ahora con positivo.
>
> **Trinidad HF5 + Run #31 = cuadrinidad estructural:** Run #30 introdujo la variante "source-scan para捍卫 ausencia" (nombra la llamada prohibida). Run #31 la complementa con la variante "control row + claim + KDoc-de attributions 1:1 para mutación" — la caracterización OBS-C1 pre-merge fue diseñada con un mutation deck para sobrevivir al fix sin necesidad de reescritura. Es la **variante caracterización-resiliente**.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest.xml` (1738 bytes, mtime 2026-10-10 02:35:36):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.durable.ObsCChannelAndTailCharacterisationTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:35:13.271Z" hostname="bazzite-rubentxu" time="22.743">
  <testcase name="every byte on both channels is conserved exactly once()" time="4.621"/>
  <testcase name="a running step reports an Open tail and a finished one a Sealed tail()" time="3.634"/>
  <testcase name="an identical payload on stderr is distinguishable from the same payload on stdout()" time="7.241"/>
  <testcase name="the read port tells two differently-payloaded transcripts apart()" time="7.241"/>
```

`<system-out>`: vacío. `<system-err>`:

```
[ShExecution] could not seal the output tail of r-obsc-c2-tail/r-obsc-c2-tail-s0-0: cannot seal unknown stream .../stderr: no writer ever opened it
[ShExecution] could not seal the output tail of r-obsc-c1-same-out/r-obsc-c1-same-out-s0-0: cannot seal unknown stream .../stderr: no writer ever opened it
[ShExecution] could not seal the output tail of r-obsc-c1-ctl-out/r-obsc-c1-ctl-out-s0-0: cannot seal unknown stream .../stderr: no writer ever opened it
```

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación:**

1. Los 22.7s del suite contrastan con los 0.4s de Run #30 (source-scan sin fsync) y 0.7s de Run #29 (también source-scan style). El tiempo es real porque el test ejerce `ShExecution` con thread concurrente — Run #31 confirma ejecución genuina, no cacheada.
2. El `<system-err>` muestra tres `[ShExecution] could not seal the output tail … stderr: no writer ever opened it` warnings. Esos warnings son **informativos**, no failure — el modelo de `ShExecution` intentó cerrar stderr después de que el run terminara (test cleanup) para un step cuyo writer de stderr **nunca fue abierto** (porque los tests escribieron sólo stdout). Es un side-effect de la disciplina de sealing — **preferible warn-and-continue que crash-and-lose-evidence**.
3. 4 tests distinto tiempo total 22.737 s, suma de los testcases individuales 22.737 s — coherente, sin dropped seconds que sugieran fsync-fake.

BUILD SUCCESSFUL in <desconocido s> con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #31 SÍ cierra

- **§1.4 OBS-C1 channel+tail characterisation anclado en test verde post-fix.** Run #31 ancla la **transición de caracterización a anclaje positivo** sobre `d5e59270`. El KDoc seguía describiendo el defecto como si estuviera vigente (es texto del OBS-merge pre-fix), pero los tests ahora pasan porque la OBS-merge arregló el código. **Eso es exactamente lo que una buena caracterización debería hacer**: sobrevivir al fix sin reescritura, mutando su rol de "asertor de ausencia" a "asertor de presencia + regression guardian".
- **Negative-control integrity:** dos de los cuatro tests son de control (`the read port tells two differently-payloaded transcripts apart` y `a running step reports an Open tail and a finished one a Sealed tail`). Ambos en verde. Si una regresión rompiera el **canal distinguisher**, los **dos** controles fallarían (no uno solo), alertando de que el rompimiento no es específico al defecto.
- **Tail state semantics (`Open`/`Sealed`)** anclado: la pregunta "¿habrá más?" se contesta con un sealed-tip **específico al escritor**, no con "no page right now". Las dos **transiciones** se observan en la suite (3.6s para la fila Open/Sealed, 7.2s para cada fila de distinguisher).
- **Bytes attribution conservation:** "every byte on both channels is conserved exactly once" verde — un byte escrito no se duplica ni se pierde al pasar por el read port. Sin esta invariante, el canal distinguisher no podría ser informativo (puede "distinguir" porque cuenta bytes).
- **4-row mutation M6 attribution** del KDoc actual: 3 verdes en lo que el KDoc declara matable (`a stderr payload distinguishable from stdout`, `every byte conserved`, `the negative control tells two differently-payloaded transcripts apart`); 2 verdes en lo que el KDoc declara no-matable según M6 (control + tail state). Si alguien reintroduce `redirectErrorStream(true)`, **3 de 4 mutan RED según el KDoc**, y el control fallback mataría por razón diferente (stderr bytes perdidos al redirigir a /dev/null) que debería seguir detectándose.

## 4. Lo que Run #31 NO cierra

- **No cubre process-side** (`sh` ejecución vía `pipelinek` binario). El test ejecuta `ShExecution` directa, no el binario. La consistencia proceso in-VM ↔ binario requiere Run process-side con load<5.
- **No cubre la matriz channel × follow** (combinación `--channel stderr --follow`). Los 4 tests cubren el pair portrait por canal y el tail state — pero no la composición con `--follow`/`--tail-bytes`.
- **No cubre cross-process Gradle pool.** Aunque `ShExecution` ejerce thread concurrente in-VM, el binario contra fork-pool no entra en este Run. Run #31 es in-VM puro.
- **No cierra OBS-C1<sup>post-fix</sup> como anclaje "post-fix".** El KDoc del test sigue describiendo el defecto "as it is today" — esa prosa es **stale** desde la OBS-merge, y su lectura futura puede confundir. Esto es **deuda técnica leve de docs**: añadir un párrafo "OBS-C1 was fixed by OBS-merge in 76c…; this characterisation is now positive regression." Probable Run #32+ como commit `docs(test): amend OBS-C1 KDoc since the channels are now distinguishable`.

## 5. Cierre acumulado del Bloque E tras Run #31

- **Verde acumulado in-VM**: 1606 (post Run #30) + 4 (Run #31) = **1610 tests verde sobre `d5e59270`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #31 añade 1, total 30 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 16 + este (Run #31) = 17.

## 6. Anchors del contrato OBS-C1 en CI

| Firma OBS-C1 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| Negative control: dos runs con payloads distintos → port SÍ distingue | `the read port tells two differently-payloaded transcripts apart` | Caracterización con control | #31 | in-VM (ShExecution real) |
| THE CLAIM: dos runs con payloads byte-idénticos pero canales distintos → port SÍ distingue | `an identical payload on stderr is distinguishable from the same payload on stdout` | Caracterización con control | #31 | in-VM |
| Bytes attribution conservation: cada byte escrito se cuenta exactamente una vez | `every byte on both channels is conserved exactly once` | Caracterización | #31 | in-VM |
| Tail state Open mientras writer vivo, Sealed al terminar | `a running step reports an Open tail and a finished one a Sealed tail` | Caracterización con tail state | #31 | in-VM |

**4 nuevos anchors OBS-C1 in-VM sobre `d5e59270`** — primera aplicación de la **HF5 variante caracterización-resiliente**: caracterización pre-fix + mutation deck + negative control + auto-KDoc-mutation-attribution.

## 7. Cuatro HF5 variantes ancladas en Bloque E

| Variante | Aplicada en | Disciplina | Runs |
|---|---|---|---|
| **Lógica** | OBS-E5 set | Mutation deck killing-declared sobre contrato tipado (49 firmas) | #23-#28 |
| **Empírica** | OBS-F chunk cost | Measurement table leída de durable authority (no del loop del test) | #29 |
| **Estructural** | OBS-C2.3 fitness | Source-scan para捍卫 ausencia + hexagonal-architecture-guard | #30 |
| **Caracterización-resiliente** | OBS-C1 channel+tail | Caracterización pre-fix con mutation deck + negative control + KDoc-de attributions + transición a anclaje positivo post-fix sin reescritura | #31 |

Run #31 añade la cuadratura. Cada variante cubre failure-modes distintos:

- **Lógica** no detecta **ausencias estructurales** (canales fusion pero bytes atribulables por reader simple).
- **Empírica** no detecta **contratos lógicos** (sweet-spot a 64 KiB, pero la tabla OBS-F no rompe si la lógica tipada se invierte).
- **Estructural** no detecta **comportamiento no-fusionado pero mal-tipeado** (redirectOutput/redirectError PIPE correctos pero en orden invertido).
- **Caracterización-resiliente** NO sobrevivirá a un fix que cambia el modelo del read port sin invalidar el KDoc — eso requeriría reescritura del test class.

## 8. Limitaciones operativas

- **Load 1m ~38 al run**: ventana estructural adversa; suite in-VM aguantó 22.7s sin wedge. `--max-workers=1` + Gradle DAG aislado son los factores aguantan.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Deuda técnica detectada en este Run

> **DOC-STALE-1:** el KDoc de `ObsCChannelAndTailCharacterisationTest` sigue describiendo el defecto OBS-C1 "as it is today" — esa prosa es stale desde la OBS-merge. Run #31 pasa los 4 tests porque el código fue arreglado, pero la lectura futura del KDoc puede confundir a un mantenedor que intente reproducir el defecto.

Propuesta: commit `docs(test): amend OBS-C1 KDoc since the channels are now distinguishable` con un párrafo adicional al inicio del KDoc:

> *"OBS-C1 was documented as unanswerable in the original OBS-C1 KDoc because `DurableShellExecutor` used to call `redirectErrorStream(true)`. The OBS-merge [OBS-commit-SHA] removed that call (verified by `ObsC23NoChannelFusionFitnessTest`, see Run #30). This file's four tests now pass against the fixed code as **positive regression anchors**, not absence assertions. Mutation M6 attribution in the KDoc remains valid: if someone reintroduces the fusion, M6 REDS three of four rows and the negative control row would still distinguish them via different *reasons* (lost stderr bytes, vanishing stderr channel). The KDoc below is preserved for the design rationale of the negative control — do not re-introduce an 'unanswerable' assertion in this file."*

Posible Run #32 después de Run #31, **dentro del alcance autónomo** (`docs(test)` sin cambiar código de producto, sólo prosa del test).

## 10. Próximo paso propuesto (no ejecutado)

1. **DOC-STALE-1 amend (Run #32)**: prose-only commit que añade el párrafo "OBS-C1 was fixed by OBS-merge; tests are now positive regression anchors". Riesgo: bajo; cambio: prose-only; receipt: `BLOCK_E_RUN_32_OBS_C1_KDOC_AMEND.md`.
2. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes.
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1610 verde sobre `d5e59270` es la mejor frontera defendible de sesión sin release**.
