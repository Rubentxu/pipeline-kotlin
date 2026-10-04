# S4 — Retention: la autoridad del terminal del RUN

> Recibo del tramo C de BLOCK 1. Cierra el hueco que `M1_INTEGRATION_REGROUND_RECEIPT` dejó
> escrito: *«The Output Plane is never pruned. Retention is a product decision with no owner yet.»*
> No certifica BLOCK 1, que sigue abierto por `StageBody.Scripted` (tramo D) y por la auditoría
> mecánica de cierre.

## 1. El hueco, y por qué cerrarlo era más urgente que la fuga

`OutputRetentionPort` llegó con el vocabulario cerrado completo —`RunLifecycle`, `RetainUntil`,
`OutputPruneIntent`, `OutputPruneReport`— y **cero llamadores en producción**. `prune` y
`hasOutputFor` no los llamaba nadie.

La fuga («el plano no se poda nunca») es el síntoma visible. Lo peligroso es el otro: una
capacidad de borrado **sin dueño** es peor que no tenerla, porque la primera persona que la cablee
tendrá que inventarse su propia noción de «¿terminó este run?». Y cualquier noción así, derivada de
un `Files.walk` o de una fila del journal, es una **segunda autoridad sobre el ciclo de vida de un
run** — exactamente lo que `OutputRetention.kt` está escrito para impedir en el store.

De modo que el trabajo no fue «poda el plano», sino **colocar la autoridad donde el hecho ya vive**:
el runtime ejecutó el run, luego el runtime sabe que terminó.

## 2. La forma

```text
RunOutcome / terminal del RUN          CanonicalDurableRunCoordinator.run(), el `finally`
  -> decisión de política               RunOutputRetention.intentFor()   [PURA]
    -> OutputPruneIntent               RetainUntil.authorize(runId, RunLifecycle.Terminal)
      -> OutputRetentionPort.prune     OutputPlaneProvider.storeFor(controlDirRoot)
        -> SegmentOutputStore          borra, y no sabe qué es un run
```

`SegmentOutputStore` no recibe el `runId` más que como **dato de un intent ya autorizado**, y su
única firma de borrado es `prune(intent: OutputPruneIntent)`.

## 3. Decisiones, y lo que cuesta cada alternativa

### 3.1 El terminal del RUN, no `finalizeStage` — precisión 1 del owner

El hook está en el `finally` de `run()`, el único punto por el que pasan **todas** las salidas: el
run completado, el abortado por un fallo de step, el abortado desde dentro de `finalizeStage` por
un finalizador `post` que falla, y el que relanza una violación de invariante.

`finalizeStage` estaba descartado por una razón concreta, no estética: **corre para un stage que está
abortando el run entero**. Colgar la liberación de ahí haría que un stage en curso de abort
descartara la salida que el mensaje de fallo del propio run y el post-mortem todavía necesitan, y que
un run de dos stages liberara dos veces. La fila `post-failure` del test existe exactamente para
probar que la tercera salida —la que pasa por `finalizeStage`— sigue autorizando **una** liberación.

### 3.2 El puerto llega como supplier, no como instancia

`retention: () -> OutputRetentionPort` en vez de `retention: OutputRetentionPort`. La recuperación
del plano no es gratis (ADR-M1 D4/O3), así que una configuración que retiene todo no debe pagar —ni
debe ser perturbada por— un store que no va a usar. Consecuencia observable, y fila propia: con
`ExplicitReleaseOnly` el supplier **no llega a resolverse**.

Efecto colateral útil: `intentFor` es puro y no necesita sistema de ficheros para probarse.

### 3.3 Un fallo de poda es un valor, no una excepción

Esto corre dentro de un `finally`. Una excepción que escapara ahí **reemplazaría el resultado del
propio run**: un build que pasó se reportaría como fallido porque una eliminación posterior encontró
un directorio de sólo lectura. El store lanza —es el borde del adaptador— así que el lanzamiento se
convierte en `RunOutputDisposition.ReleaseFailed` y se reporta. `Error` sigue propagándose: esto
captura fallo operativo, no una JVM rota.

La fila `a release that cannot happen does not change the run outcome` usa un store **real no
recuperado**, no un mock: es una condición alcanzable, y es la que un `finally` no puede propagar.

### 3.4 La política que el composition root declara — y su consecuencia, dicha

```kotlin
outputRetention = RunOutputRetention(
    retention = { OutputPlaneProvider.storeFor(controlDirRoot) },
    policy = RetainUntil.ExplicitReleaseOnly,
)
```

`ExplicitReleaseOnly`, y el motivo es la consola del propio producto: `pipeline console
--control-dir` lee el transcript de un run **que ya terminó** (medido en B2e sobre la distribución
instalada). Un `RunTerminalPlus` aquí borraría la salida que el producto existe para servir.

Eso tiene una consecuencia que no se esconde: **nada se libera automáticamente**, y la superficie de
producto que pediría un `OperatorReleased` no está construida. Se registra aquí como hueco de
superficie, no como fila cerrada. La fila `the declared policy keeps a finished run's console
readable` es su guarda: si alguien cambiara la política por `RunTerminalPlus` creyendo que «retener
hasta el final» es lo mismo que «nunca borrar», esa fila cae.

### 3.5 Por qué `RunOutputRetention` es público

`CanonicalDurableRunCoordinator` es público y lo toma como parámetro opcional, que es la convención
que ya siguen sus otros colaboradores opcionales (`StepRegistry`, `DirectiveRegistry`,
`WaitUntilControlJournal`). Kotlin no deja que un constructor público exponga un tipo `internal`, y
las alternativas eran peores: un `lambda` borraría el veredicto, y un marker-interface público más una
implementación `internal` añadiría un segundo nombre para la misma cosa.

Público aquí significa «la costura de retention del runtime tiene nombre», **no** «el store se
publica». La mitad que BLOCK 2 no publica —`SegmentOutputStore`, su layout y su recovery— se alcanza
sólo por la interfaz `OutputRetentionPort`, y ningún tipo de esta costura lo nombra.

## 4. El `if (false)` de producción, y la propiedad que pretendía representar

Búsqueda negativa del owner's list: `SegmentOutputStore.kt:587` tenía

```kotlin
val committedInSegment = (base - segmentBaseInternal).coerceAtLeast(0L)
val onDisk = if (Files.exists(layout.segmentFile)) Files.size(layout.segmentFile) else 0L
if (false) truncateTo(layout.segmentFile, committedInSegment)
```

**Determinar la propiedad antes de tocar**, como manda la orden: *un segmento más largo que el
extent comprometido significa que un escritor murió entre `write` y `commit`, y hay que truncarlo al
committed extent relativo a su propio base.*

La autoridad de esa propiedad ya existía y era una sola: `reconcile()`, que corre en `recover()`, y
que O3 vuelve **obligatoria antes de cualquier reserva** — el propio `init` de `Reserve` lanza si
encuentra una reserva viva, precisamente porque «recovery owns that decision». El propio KDoc de
`reconcile()` lo decía: la segunda truncación era redundante, y el mutation harness ya la había
medido como no load-bearing.

Activar la línea habría creado **dos autoridades sobre un mismo hecho**. Se **eliminó**, junto con
los dos locales que quedaban muertos, y el motivo quedó escrito en el sitio: una guarda desactivada
es una afirmación que el código no está haciendo.

## 5. Dos defectos propios de P1 que este gate encontró

No sonRnuevos: los introduje en `3b7740f5` y no pasé `detekt` sobre `scm-git` ni sobre el propio
fitness. Aparecieron al correr el gate por primera vez desde entonces.

1. **`UnusedParameter` ×3** en `GitCheckoutExecutor.kt`: al quitar `emitEcho`, `gitFetch`,
   `gitResetHard` y `gitClone` conservaron un `req: GitCheckoutRequest` que ya no usaban. Parámetro
   muerto y tres call sites actualizados. El `emitEcho` de P1 sí era correcto; se le olvidó barrer
   lo que dejaba detrás.
2. **`VariableNaming`** en `CoreShSingleAdmissionAuthorityTest.kt`: un `private val` en
   `SCREAMING_SNAKE_CASE` (`FUNCTION_DECLARATION`), que Detekt sólo admite para `const`.

Ninguno se resolvió bajando el umbral: se corrigió la causa.

## 6. El growth guardrail del coordinator tenía razón

`CoordinatorGrowthGuardrailTest` (techo 572) cayó en `604` porque el coordinator **interpretaba** el
veredicto de un colaborador (`settleOutputRetention`, 22 líneas). El guardrail no se tocó: la
interpretación se movió a `RunOutputRetention`, que es donde vive el significado, con un sink de
diagnóstico por defecto (stderr del operador) para que la frase la diga quien entiende el
veredicto.

Resultado: el coordinator quedó en **567 líneas — cinco por debajo del techo existente**. No hizo
falta subir el ratchet. El guardrail obligó al diseño correcto, que es exactamente su propósito.

## 7. Tramo E — `scm.git`: investigación, no implementación forzada

El substrate que el owner describe **ya existe**, completo y genérico:

```text
SHELL_OPERATIONS_CAPABILITY  ->  ShellOperations  ->  ShOperationsAdapter
  ->  ShExecution.invokeShell  ->  redaction (secretPatternRegistry)
    ->  Output Plane (ingestTranscriptIntoOutputPlane)
      ->  ShellInvocationResult (tipado)
```

Un handler de plugin que declara `SHELL_OPERATIONS_CAPABILITY` recibe hoy exactamente esa cadena, con
admisión fail-closed y sin duplicar runtime. **Lo que falta son dos cosas concretas**, y ambas son de
núcleo, no de `scm.git`:

- **G1 — el comando es un `script`, no un argv.** `ShellCommand(script, returnMode)`. `scm.git` hoy
  corre argv con `ProcessBuilder` porque componer `git clone` como string de shell es un cambio de
  comportamiento (quoting) para un plugin que hoy ejecuta argv. Hace falta un puerto de proceso a
  nivel argv sobre el mismo substrate.
- **G2 — el resultado tipado no lleva referencia de stream.** `ShellInvocationResult` es
  `{UnitValue | Stdout | Status | Failed | Interrupted}`: **nadie**, ni siquiera `core.sh`, puede
  nombrar por tipo el stream donde acabó su transcript. El `typed ProcessResult / OutputStreamRef`
  que pide el owner todavía no existe.

Ambos son cambios de una capability pública y de un tipo de resultado tipado: es exactamente la
superficie de **S6 (Unified Plugin SDK)**, donde el bloque.owner prueba que un JAR externo aporta
Step + Directive + Event + DSL + capability. Y BLOCK 2 de Fabric, según el owner, sólo necesita el
**read-side** publicado y no es owner del write-side de procesos de plugins.

**Decisión: no se fuerza en BLOCK 1.** Se registra G1 y G2 como input concreto de S6. No se publica
`OutputAppendPort` a plugins, y no se crea una ruta específica para `git`.

## 8. Evidencia

Todo con XML fresco, `0` líneas `^e:`, y exit code leído directo.

### 8.1 Focal — `RunOutputRetentionTest` (HF2, `sh` real, store real en disco)

```text
cd v2 && ./gradlew :pipeline-application:test \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.RunOutputRetentionTest' \
  -PexcludeSlowTests=true --rerun-tasks
```

`BUILD SUCCESSFUL`, `EXIT=0`, `0 ^e:`. **10 tests, 0 fallos, 0 errores, 0 skipped** (3,834 s de
tiempo de test: los procesos hijo corrieron de verdad).

| fila | qué prueba |
|---|---|
| `every run exit reaches the terminal hook exactly once and the stage never does` | 3 salidas distintas (completado / fallo de step / fallo de `post`) autorizan **una** liberación y sólo `RunReachedTerminalState` |
| `a failed run still releases and keeps its own outcome` | un run fallido también se libera; retention pregunta «terminó», no «pasó» |
| `a finished run releases every stream its stages wrote in one pass` | 2 stages → **1** intent, 2 streams, bytes reales; los directorios desaparecen del layout |
| `the declared policy keeps a finished run's console readable` | `hasOutputFor` + los bytes siguen en disco tras el run |
| `a retaining policy never opens the plane at all` | el supplier no llega a resolverse |
| `Forever never authorises an automatic release` | el hold no autoriza ni en el terminal |
| `the release intent carries the run and nothing else` | reflexión Java: un campo `String`, ningún accessor de outcome |
| `a repeated release is idempotent` | 2ª poda: 0 removidos, 0 bytes, sin excepción |
| `a restart between terminal and release reaches the same decision` | `forgetAll()` + store nuevo: mismo veredicto y **árbol durable idéntico** (no hay cola de cleanup propia) |
| `a release that cannot happen does not change the run outcome` | store no recuperado real: `RunOutcome.Success` intacto, `ReleaseFailed` reportado |

### 8.2 Focal — `RetentionAuthorityFitnessTest` (mecánico)

`BUILD SUCCESSFUL`, `EXIT=0`, `0 ^e:`. **9 tests, 0 fallos, 0 errores.**

| fila | ley |
|---|---|
| `only the run terminal may claim a run reached its terminal state` | un único sitio construye el intent |
| `only the run terminal asks the store to delete` | un único `.prune(` **+ mitad positiva**: el seam llega de verdad al puerto |
| `no live run can be expressed as a reason to delete` | nadie nombra `StillRunning`; `Terminal` sólo en el seam |
| `the retention decision is not spelled as an outcome string` | el seam no contiene `"success"`/`"unstable"`/`"failure"`/`"aborted"`/`RunOutcome` |
| `the stage lifecycle does not decide retention` | `StageExecutionEngine`/`ParallelStageEngine`/`PostPlan` no nombran retention |
| `the output module cannot know whether a run is alive` | fuera del vocabulario, `pipeline-output` no nombra lifecycle ni outcome |
| `the store itself names no lifecycle and can only delete on an intent` | el store, fila por fila |
| `no intent can carry a run outcome because the module cannot see one` | **gráfico**: `pipeline-output` no tiene ninguna dependencia `project(` |
| `the journal can reference output but cannot delete it` | **gráfico**: `pipeline-events` no depende de `:pipeline-output` |

Dos de estas son doble: texto y grafo. El texto prueba la intención; la dependencia imposible la
convierte en estructura.

### 8.3 Mutaciones, con atribución 1:1

Restauradas con backup + `sha256sum -c` verificado. **Nunca** `git restore`.

| mutación | cambio | filas caídas |
|---|---|---|
| **M1** | la liberación baja de `run()`'s `finally` a `finalizeStage`, y se cablea en el stage engine | **1 de 10**: `a finished run releases every stream its stages wrote in one pass` (2 intents en vez de 1) |
| **M2** | `RunOutputRetention.onRunTerminal` deja propagar el `throw` del store | **1 de 10**: `a release that cannot happen does not change the run outcome` |
| **MF1** | segundo sitio de producción construyendo `RunReachedTerminalState` (`MainConsoleCli`) | **1 de 9**: `only the run terminal may claim a run reached its terminal state` |
| **MF2** | `pipeline-events` gana dependencia de `:pipeline-output` | **1 de 9**: `the journal can reference output but cannot delete it` |

### 8.4 Dos de mis aserciones estaban mal antes que el código

`RetentionAuthorityFitnessTest` falló **2 de 9** en su primera ejecución, y fallaban por alcance:

- `only the run terminal asks the store to delete` no excluía al propio seam, así que reportaba al
  único llamador lawful.
- `the output module cannot know whether a run is alive` no excluía `OutputRetention.kt`, el fichero
  que **define** el vocabulario y por tanto tiene que nombrar `RunLifecycle` — sin él, un run vivo no
  tendría caso bajo el que ser borrado, que es la ley.

Se corrigieron los scans y se añadió la fila específica del store, que es la forma estrecha real de la
ley. Queda escrito porque es el tipo de error que un recibo debe hacer visible.

### 8.5 Regresión de lo afectado

El owner pidió explícitamente no full suite todavía, así que esto es **quirúrgico por clases
afectadas**, no el gate completo.

```text
cd v2 && ./gradlew :pipeline-output:test :pipeline-architecture-tests:test \
  :pipeline-step-sdk:scm-git:test \
  :pipeline-application:detekt :pipeline-output:detekt :pipeline-step-sdk:scm-git:detekt \
  -PexcludeSlowTests=true --rerun-tasks
```

`BUILD SUCCESSFUL in 3m 23s`, `EXIT=0`, `0 ^e:`.

| módulo | tests | fallos | errores | skipped |
|---|---:|---:|---:|---:|
| `pipeline-output` | 48 | 0 | 0 | 0 |
| `pipeline-architecture-tests` | 442 | 0 | 0 | 10 |
| `pipeline-step-sdk:scm-git` | 47 | 0 | 0 | 8 |

Los 18 skipped son preexistentes. `pipeline-architecture-tests` incluye
`CoordinatorGrowthGuardrailTest`, que es donde cayó la primera versión de este cambio.

```text
cd v2 && ./gradlew :pipeline-application:test \
  --tests '…durable.RunOutputRetentionTest' --tests '…durable.RetentionAuthorityFitnessTest' \
  --tests '…CoordinatorRunLifecycleCharacterizationTest' --tests '…S2BPostCoordinatorIntegrationTest' \
  --tests '…durable.OutputSingleAuthorityFitnessTest' --tests '…CoreShSingleAdmissionAuthorityTest' \
  --tests '…BodyExecutionCharacterizationTest' --tests '…S2CGateCompositionInterpreterTest' \
  -PexcludeSlowTests=true --rerun-tasks
```

`EXIT=0`, `0 ^e:`. **9 clases, 47 tests, 0 fallos, 0 errores, 0 skipped**, XML de las 9 a las 21:08:30.

Elegidas por lo que tocan: el guardrail de crecimiento, la caracterización del ciclo de vida del run,
`finalizeStage` + `post`, la autoridad única del output plane, el fitness de P1, el spine de cuerpos, y
el composition root.

**Total: 584 tests, 0 fallos, 0 errores.**

### 8.6 Una corrección al propio plan de evidencia

El primer intento de regresión fue `:pipeline-application:test` **completo** con `--rerun-tasks`, y no
terminó: Gradle re-forkeaba el worker de tests continuamente durante 25 min sin escribir un solo XML.
El diagnóstico no fue el código sino la máquina — `free -h` mostró **swap al 100 % (4,0 de 4,0 Gi)**
con un worker de Gradle de `PipelinekFabric` de 12 h+ ocupando CPU y memoria. Se paró y se pasó a la
regresión quirúrgica de arriba, que es además lo que el owner había pedido.

Queda escrito porque un `BUILD FAILED` por thrash y un fallo de producto se parecen en el log y no son
lo mismo. El **full gate no se ha corrido en este tramo**, y no se declara verde.

## 9. Lo que este tramo NO cierra

- **`StageBody.Scripted` sigue siendo sólo IR.** `CompiledPipeline.kt:285` lo declara, el validator
  lo acepta por construcción, y el coordinator lanza
  `IllegalArgumentException("Unsupported Scripted…")` en su dispatch. Cero callers productivos. Es
  el tramo D y es lo que impide cerrar BLOCK 1.
- **No hay superficie de release por operador.** La política declarada retiene siempre; el
  `OperatorReleased` existe como caso del ADT y no tiene llamador. Decisión de superficie de producto,
  no se inventa aquí.
- **`ShellInvocationResult` no lleva `OutputStreamRef`** (G2 del tramo E). Afecta también a
  `core.sh`, no sólo a plugins.
- **El full gate no se ha corrido.** Por orden explícita del owner: sólo al cierre de BLOCK 1, y
  BLOCK 1 no está cerrado.
