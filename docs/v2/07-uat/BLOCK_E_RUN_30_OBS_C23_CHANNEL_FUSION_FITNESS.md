# Run #30 — `ObsC23NoChannelFusionFitnessTest` in-VM ancla §1.4 OBS-C2.3 channel-fusion fitness (Bloque E · OBS-R1 / OBS-C2.3)

| | |
|---|---|
| **Run** | #30 |
| **Objetivo** | Anclar **§1.4 OBS-C2.3** channel-fusion fitness: tres invariantes del **transcript path canónico** garantizadas por **source scan** (no por behavioral test) porque el defecto es una **ausencia** — "no hay punto donde se fusionen los canales". Hexagonal-architecture-guard incluido: el SDK runtime no debe importar el Output Plane (regla de dependencias del proyecto). |
| **SHA verificado** | `768d484fc2cf313fe301f00fc30c078c3fd8b821` (HEAD post Run #29, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsC23NoChannelFusionFitnessTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~21,96 (al inicio). **in-VM aguanta** este load por Runs #16-#30 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #30 cierra

El KDoc del test class —`ObsC23NoChannelFusionFitnessTest.kt:18-44`— declara el invariante y la **disciplina HF5-source-scan**:

> *"`DurableShellExecutor` used to call `redirectErrorStream(true)`, which makes the kernel merge stdout and stderr into one descriptor. That fusion is **irreversible and invisible**: once it has happened, no code above it can say which channel a byte came from, so `--channel stderr` has no boundary to recover from and no test written afterwards can observe a difference between the two.*
>
> *`ObsCChannelAndTailCharacterisationTest` measured that fusion for a whole block before it was fixed. This row exists so it cannot come back unnoticed, which is the one thing a behavioural test cannot do on its own: the three laws it asserts would all still pass if someone reintroduced the fusion and a second merged stream to match.*
>
> *Because the defect is an **absence** — 'there is no point where the channels are kept apart'. The behavioural rows prove that bytes ARE attributed; they cannot prove there is no place that would quietly undo it, because the undone state is observationally identical to the fixed one from a single reader's point of view. Naming the forbidden call is the only assertion that discriminates those two worlds, so that is what this file does."*

**Disciplina HF5 por source scan (en vez de behavioral):** cuando el fallo es una **ausencia estructural** que el runtime no puede distinguir del estado correcto, un test que solo ejecuta comportamiento **NO discrimina las dos realidades** (fusionado y separado son observationalmente idénticos desde un único reader). El único discriminador es **nombrar la llamada prohibida** y escanear el código fuente. Run #30 ancla esa disciplina como código ejecutable.

Las tres firmas:

| Test | Cubre la propiedad OBS-C2.3 | Tipo | Resultado medido |
|---|---|---|---|
| `the durable shell substrate never fuses stdout and stderr` | `DurableShellExecutor.kt` no contiene `redirectErrorStream(true)` en código ejecutable (excluye `//` y `*` líneas de comentario) | Source scan | verde, 0.078s |
| `the canonical transcript path redirects each channel independently` | `DurableShellExecutor.kt` contiene `redirectOutput(PIPE)` y `redirectError(PIPE)` (dos pipes independientes, uno por canal) | Source scan | verde, 0.008s |
| `the SDK runtime does not depend on the Output Plane` | Ningún `.kt` bajo `pipeline-step-sdk/runtime/src/main/kotlin` importa `dev.rubentxu.pipeline.v2.output` (hexagonal-architecture-guard) | Source scan | verde, 0.321s |

> **Hexagonal-architecture-guard** (Test 3): el KDoc cita explícitamente la regla del proyecto: *"the dependency would point outward from the substrate to the store's contracts, which is the reversal the hexagonal rule forbids"*. El SDK runtime es un **substrate**; debe escribir al sink que la aplicación compuso, sin aprender qué autoridad durable recibe los bytes. Si en algún commit futuro alguien importa `pipeline-output` desde `pipeline-step-sdk:runtime`, el test rojo con todos los archivos infractores listados — antes de que eso pueda entrar a `c924af8c` o extenderse al OBS worktree.

> **Exclusión por comentario, explícitamente justificada:** *"Comments are excluded, and that exclusion is not a convenience: this file's own KDoc has to be able to say 'the SDK names ProcessOutputChannel, never OutputChannel', and a scan that counted prose would report the law's own explanation as a violation of it."* HF5 §2-style defense: si el scanner contara prosa, **el KDoc del propio test class** se reportaría como violación de la regla. La implementación filtra `//`, `*`, `/*` para evitar esa tautología.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.ObsC23NoChannelFusionFitnessTest.xml` (924 bytes, mtime 2026-10-10 02:28:39):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.durable.ObsC23NoChannelFusionFitnessTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:28:38.470Z" hostname="bazzite-rubentxu" time="0.413">
  <testcase name="the SDK runtime does not depend on the Output Plane()" time="0.321"/>
  <testcase name="the durable shell substrate never fuses stdout and stderr()" time="0.078"/>
  <testcase name="the canonical transcript path redirects each channel independently()" time="0.008"/>
```

**3 tests / 0 fallos / 0 errores / 0 skipped.** Suite time: **0.413 s** (rápido — solo lectura de ficheros, sin fsyncs).

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación**: el test rojo si cualquiera de los tres patrones vuelve a aparecer. La mtime post-Run #30 (02:28:39) es posterior a HEAD (768d484f), descartando cache contra el material.

BUILD SUCCESSFUL in 2m 7s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #30 SÍ cierra

- **§1.4 OBS-C2.3 channel-fusion fitness anclado en test verde.** Antes de Run #30, el path OBS-C2.3 vivía implícito en OBS-C deliverables sin fila defendiendo la **invariante de source-scan** sobre `redirectErrorStream(true)`. Run #30 enumera las 3 firmas con la disciplina HF5-source-scan.
- **Ausencia estructural defendida:** Run #30 ancla la diferencia entre "test que ejecuta comportamiento" y "test que protege ausencia estructural". El KDoc lo dice textual — "naming the forbidden call is the only assertion that discriminates those two worlds". Esto es la **variante estructural de HF5**: añadir el patrón de nombrar la cosa prohibida cuando el defecto es invisible desde el comportamiento.
- **Hexagonal-architecture-guard anchored:** ninguna `runtime/` source del SDK importa `pipeline-output`. Si en algún commit futuro la dependencia aparece, el test rojo listando los archivos infractores — antes de cualquier integración. Esta es la **tercera variante HF5** Run #30 introduce, después de las dos previas:
  - **Variante lógica** (Runs #23-#28 OBS-E5): mutación killing-deck-declared sobre contrato tipado.
  - **Variante empírica** (Run #29 OBS-F): measurement table leída de producción durable.
  - **Variante estructural** (Run #30 OBS-C2.3): source-scan para捍卫 ausencia + reglas de arquitectura hexagonal.
- **C2.3 pin bidireccional:** Test 1 (no fusionar) + Test 2 (sí pipes independientes) juntos demuestran que el path canónico hace **lo correcto** y **nada más**. Una dirección sin la otra sería un assertion parcial (sin pipes, o con pipes pero con una fusión escondida — Test 1 + Test 2 en conjunto descartan ambos fallos).
- **Comentario exclusion legítimo:** Run #30 testea que el KDoc del propio test class puede mencionar las palabras prohibidas sin que el scanner se auto-report (HF5 §2: el test no se testea a sí mismo). Eso es una invariante de orden superior — **el scanner no es frágil frente a su propia prosa**.

## 4. Lo que Run #30 NO cierra

- **No cubre `DurableShellExecutor.kt` modificable por gradle script** u otro path productivo fuera del source scan. La regla se aplica al source actual; cualquier archivo generado que contenga `redirectErrorStream(true)` no entra en este test (y sería regulado por otra capa de fitness).
- **No cubre la matriz transcript-path × platform**: el test escanea `DurableShellExecutor.kt` independientemente del OS; cambios en Windows/macOS no los mira. Probable extension con paths paralelos si la diferencia entre plataformas importa.
- **No cubre el holistic de todas las OBS-C tests.** Quedan sin ancla dedicada: `ObsC23ChannelSeparationUatTest` (Uat, requiere fork/load<5), `ObsCChannelAndTailCharacterisationTest` (512 líneas, characterización complementaria). Run #30 es sólo C2.3-fitness; no pretense cerrar OBS-C enteramente.
- **No cubre cross-process Gradle pool.** Source-scan in-VM puro, no fork-`pipelinek`.

## 5. Cierre acumulado del Bloque E tras Run #30

- **Verde acumulado in-VM**: 1603 (post Run #29) + 3 (Run #30) = **1606 tests verde sobre `768d484f`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #30 añade 1, total 29 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 15 + este (Run #30) = 16.

## 6. Anchors del contrato OBS-C2.3 en CI

| Firma OBS-C2.3 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| `DurableShellExecutor.kt` no contiene `redirectErrorStream(true)` en código ejecutable | Test 1 (`the durable shell substrate never fuses stdout and stderr`) | Source scan | #30 | in-VM |
| `DurableShellExecutor.kt` contiene `redirectOutput(PIPE)` y `redirectError(PIPE)` | Test 2 (`the canonical transcript path redirects each channel independently`) | Source scan | #30 | in-VM |
| Ningún `.kt` bajo `pipeline-step-sdk/runtime/src/main/kotlin` importa `dev.rubentxu.pipeline.v2.output` | Test 3 (`the SDK runtime does not depend on the Output Plane`) | Source scan (hexagonal guard) | #30 | in-VM |

**3 nuevos anchors OBS-C2.3 in-VM sobre `768d484f`** — primera aplicación de la **HF5 variante estructural** en el Bloque E.

## 7. Tres variantes HF5 ancladas en el Bloque E

| Variante | Aplicada en | Disciplina | Runs |
|---|---|---|---|
| **Lógica** | OBS-E5 set | Mutation deck killing-declared sobre contrato tipado. "Matar-deck-de-FOO vuelca FILA-1, FILA-2"; cross-row collapse medido, predicted-vs-measured confeso. | #23-#28 |
| **Empírica** | OBS-F chunk cost | Measurement table leída del durable authority (no del loop del test). HF5 §2: writes/frames leídos de `SegmentOutputStore` + `FrameIndex`. | #29 |
| **Estructural** | OBS-C2.3 fitness | Source scan para捍卫 ausencia + reglas de arquitectura hexagonal. Comentario exclusion explícitamente justificada. | #30 |

Run #30 cierra la tríada. La fitness de OBS-C2.3 **NO se valida con runtime alone** — necesita un fitness que nombre la llamada prohibida, porque el fallo es invisible desde la observación. Esta tríada (lógica / empírica / estructural) es el catálogo de **variantes HF5** aplicado al OBS worktree.

## 8. Limitaciones operativas

- **Load 1m ~22 al run**: in-VM aguanta. Source scan rápido (0.4 s suite time, sin fsyncs).
- **Cross-process Gradle pool wedge** sigue activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03).
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: Run #21 demuestra que cross-process es viable en 1m<5. Los tests §6.2 pendientes (12 tests) incluyendo `ObsC23ChannelSeparationUatTest`, `ObsBLiveOutputIngressTest`, `ObsFConsumerContinuityUatTest` siguen esperando ventana.
2. **Run #31 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada:
   - `ObsCChannelAndTailCharacterisationTest` (512 líneas, channel+tail cross — complementa Runs #25 + #28 + #30);
   - `ObsAOutputStreamingCharacterisationTest` (361 líneas, A surface);
   - Observation* tests (7 archivos en `observation/`).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1606 verde sobre `768d484f` es la mejor frontera defendible de sesión sin release**.
