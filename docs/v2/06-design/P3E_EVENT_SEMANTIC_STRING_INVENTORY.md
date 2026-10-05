# P3-E / E1 — Inventario de semántica wire publicada en `pipeline-events`

Estado: **auditoría cerrada, read-only**. No autoriza por sí sola ninguna modificación de
producción; las decisiones que exige se toman en E2..E6.

Fecha de captura: sobre `b96c647e` (árbol limpio).

---

## 0. Por qué una clasificación binaria no basta

La primera hipótesis era `CLOSED SEMANTIC VOCABULARY` frente a `FREE TEXT / IDENTITY`.
Medir el productor la refuta: de los campos semánticos del Event Plane hay **cuatro**
formas de estar, no dos. La cuarta y la tercera son las que importan, porque ambas
señalan un defecto aguas arriba y no un tipo perdido:

| Disposición | Qué significa | Qué autoriza |
|---|---|---|
| `CLOSED_SEMANTIC` | existe una autoridad ADT/enum/cambio-finito **antes** del evento; el evento es una proyección | tipo de proyección + decoder exhaustivo |
| `FREE_TEXT_OR_IDENTITY` | nombre, id, ruta, URI, digest, mensaje o explicación diagnóstica | `String` es la representación correcta; **no tocar** |
| `UNRESOLVED_SEMANTIC` | el campo tiene significado de dominio pero su productor también usa `String`, o mezcla varios conceptos | **nada**. Hay que cerrar la autoridad upstream primero |
| `CONSTANT_PROJECTION` | el wire lleva un único literal productivo y no representa una elección | nada. Sin enum ceremonial de un caso |

La ley que decide, aplicada por procedencia y no por "es un `String`":

```text
¿el PRODUCTOR deriva el valor de un ADT/enum/conjunto finito?
   │                        │
  sí                       no
   │                        │
   ▼                        ▼
CLOSED_SEMANTIC       ¿el CONSUMIDOR decide con match por token exacto,
                      o el valor es un nombre / id / diagnóstico?
                          │                        │
                         sí                       no
                          │                        │
                          ▼                        ▼
                  UNRESOLVED_SEMANTIC      FREE_TEXT_OR_IDENTITY
                  (hay decisión, no hay     (o CONSTANT_PROJECTION
                   autoridad cerrada)        si un solo literal)
```

La observación que reencuadra el bloque: **"String semántico" no siempre significa
"enum perdido"**. A veces significa que la autoridad upstream no está modelada. P3-E
tiene que cerrar esa diferencia antes de congelar el Event Plane, porque un Observer
construido encima de un esquema cuyo significado todavía se expresa con cadenas
convierte cada defecto aguas arriba en deuda contractual pública.

---

## 1. Alcance medido

Mecánica, reproducible, sobre `v2/pipeline-events/src/main/kotlin` (17 ficheros):

```text
val <nombre> : String | String? | List<String>
dentro de un data class
```

Resultado:

| Métrica | Valor |
|---|---|
| Ficheros escaneados | 17 |
| `String` | 282 |
| `String?` | 15 |
| `List<String>` | 4 |
| **Total campos** | **301** |
| Clases con al menos un campo | 78 |
| `eventId` + `runId` (identidad pura) | 143 |
| **Resto a clasificar** | **158** |

Los cuatro `List<String>` publicados, completos:

| Campo | Clasificación |
|---|---|
| `WsCleaned.patterns` | `FREE_TEXT_OR_IDENTITY` — globs escritos por el usuario |
| `GateEvaluated.directiveKeys` | `FREE_TEXT_OR_IDENTITY` — `DirectiveKey.value`; identidad, no discriminante |
| `PostConditionSelected.selectedConditions` | `CLOSED_SEMANTIC` ← `PostCondition` |
| `PostConditionSelected.skippedConditions` | `CLOSED_SEMANTIC` ← `PostCondition` |

---

## 2. Tabla maestra de los campos con valor semántico

Ordenado por disposición. `Wire` es la grafía histórica **exacto** tal y como viaja; P3-E
no la normaliza (E3 lo prohíbe explícitamente: `failure`/`failed` y `success`/`succeeded`
conviven y esa asimetría es historia, no error).

### 2.1 `CLOSED_SEMANTIC`

| Campo | Autoridad upstream | Wire | Consumidores | Observación |
|---|---|---|---|---|
| `RunFinished.outcome` | `RunOutcome` (4 casos) | `success` `unstable` `failure` `aborted` | `RunLifecycleEngine.closeRun` emite; observadores externos leen | proyección limpia, `when` exhaustivo ya |
| `StageFinished.outcome` | `StageOutcome` **+ decisión de emisión** | `success` `unstable` `failed` | `StageExecutionEngine.finalizeStage` | **no** es función total: `FAILED` con run abortando no emite, y `SKIPPED` tiene evento propio |
| `ParallelBranchFinished.outcome` | `StepOutcome` | `failure` `unstable` `success` | `ParallelStageEngine:216` | `else -> "success"` — ver §5.1 |
| `RetryAttemptFinished.outcome` | `OperationStatus` | `succeeded` `failed` `unstable` | `RetryEngine:128` | `else -> error(...)` correcto; el hermano de §5.1 no aprendió la lección |
| `DirectiveAdmitted.phase` | enum de fase | `.name` | motor de directivas | proyección trivial |
| `DirectiveAdmitted.policy` | `DirectiveExecutionPolicy` | `evaluate …` | motor de directivas | proyección de ADT |
| `PostConditionSelected.stageOutcome` | `StageOutcome` | idem `StageFinished` | `PostPlanner` | **participa del bucle `typed→String→typed`**, §5.2 |
| `PostConditionSelected.selectedConditions` | `PostCondition` | `.name` ×7 | observadores | `ALWAYS SUCCESS FAILURE UNSTABLE ABORTED UNSUCCESSFUL CLEANUP` |
| `PostConditionSelected.skippedConditions` | `PostCondition` | `.name` ×7 | observadores | ídem |
| `StashFailed.operation` | **no existe** — productor `stashFailedEvent(name, op: String, …)` | `stash` \| `unstash` | `StashOperationsAdapter` | 20 sitios de emisión, exactamente 2 valores: cerrado **por observación**, sin autoridad tipada. El tipo pertenece a la costura de stash, no a `events`. Ver §5.6 |

### 2.2 `FREE_TEXT_OR_IDENTITY`

Se mantienen como `String`. Tiparlos sería empeorar el modelo.

`eventId` · `runId` · `name` · `label` · `path` · `url` · `sha256` · `message` ·
`StageSkipped.reason` (← `GateVerdict.NotSatisfied.reason`) · `GateEvaluated.reason` ·
`DirectiveDenied.reason` · `relPath` · `branch` · `osName` · `remoteUri` ·
`WsCleaned.patterns` · `CatchErrorTriggered.message` · `GateEvaluated.directiveKeys`

Los `reason` de gate y directive son **explicación del veredicto**, no discriminante: el
productor demuestra que vienen de una decisión ya tomada.

`GateEvaluated.directiveKeys` merece una línea propia porque se parece a un campo cerrado
y no lo es: `BeforeStageDirectiveEngine.kt:203` lo produce como
`predicates.map { it.key.value }`, es decir **proyecta un `DirectiveKey` tipado a su
nombre**. El conjunto de valores posibles está restringido por el registro de directivas,
pero ningún consumidor matchea por token para decidir: la lista sirve para saber *qué
claves participaron*, que es identidad. Tiparlo como si fuera discriminante sería
confundir "vocabulario restringido" con "elección".

### 2.3 `UNRESOLVED_SEMANTIC`

| Campo | Por qué no es `CLOSED` todavía |
|---|---|
| `WaitUntilCompleted.outcome` | el productor nuevo (`WaitUntilEngine`) emite `String`; ver §3 completo |
| `CatchErrorTriggered.buildResult` | 3 autoridades en conflicto; ver §4 |
| `CatchErrorTriggered.stageResult` | viaje 4 capas, **nadie lo lee**; ver §4 |

### 2.4 `CONSTANT_PROJECTION`

| Campo | Valor único | Productor |
|---|---|---|
| `TimeoutScheduled.timeoutAction` | `"abort"` | uno solo |
| `TimeoutTriggered.action` | `"abort"` | uno solo |

No merecen un enum de un caso. El `kind` del propio evento ya expresa la semántica; la
decisión de si retirarlos pertenece a una boundary futura, no a P3-E.

---

## 3. `WaitUntilCompleted.outcome` — caracterización completa

Es el caso que la ley de cuatro disposiciones obliga a no tipar a ciegas. Tres
productores, y **no todos son equivalentes**.

### 3.1 Matriz de escenarios

| Escenario | `OperationStatus` durable | `StepOutcome` | Evento `outcome` | En restart |
|---|---|---|---|---|
| predicado satisfecho | `SUCCEEDED` | `Success` | `completed` | `AdvanceAfterPredicateSatisfied` → reemite `completed` |
| predicado insatisfecho/fallido (poll N) | `FAILED` (poll N) | — (sigue) | — (sólo `WaitUntilPolled`) | `AdvanceAfterPredicateUnsatisfied` → backoff |
| body `Cancelled` | `ABORTED` | `Failure(ENGINE)` | `aborted` | `Aborted` → reemite `aborted` |
| deadline excedido | `FAILED_TIMEOUT` | `Failure(TIMEOUT)` | `deadline-exceeded` | `DeadlineExceeded` → reemite `deadline-exceeded` |
| divergencia de fingerprint | *(no muta)* | `Failure(REPLAY_COMPATIBILITY)` | **ninguno** | — |

### 3.2 Lectura de la matriz

**Los tres tokens mezclan dos ejes distintos.** `completed` y `deadline-exceeded` son las
dos caras de una misma pregunta — *¿terminó el predicado?* — y `aborted` responde a otra:
*¿terminó el agregado?* No es un vocabulario cerrado homogéneo; es una proyección de dos
conceptos sobre un campo.

**`RejectDivergence` no emite `WaitUntilCompleted`.** Es una salida terminal sin evento:
un observador que use este evento como "el waitUntil terminó" se equivoca en la quinta
fila sin ningún indicio en el stream.

**El productor nuevo es el único que conoce los tres tokens.** `BodyExecutionEngine`
(loop legacy) emite `completed` y `deadline-exceeded`, nunca `aborted`; y decide el resto
con un `else` sobre `StepOutcome` (§5.1).

**El KDoc del evento está desfasado.** `DomainEvent.kt:819` declara
`@param outcome "completed" or "deadline-exceeded"`. Hay un tercer token en producción
desde hace tiempo y el contrato publicado no lo menciona.

**El decoder es fail-open.** `JsonEventLog.kt:821`:

```kotlin
val outcome = EventJsonFields.stringField(s, "outcome") ?: "completed"
```

Un `outcome` ausente o corrupto se re-tipifica como **éxito**. Esto contradice la ley de
E3 (`unknown discriminator → fail closed`, sin defaults) y es un defecto activo hoy,
independientemente de qué se decida sobre el tipo.

### 3.3 Productores

| Productor | Ruta | Naturaleza |
|---|---|---|
| `WaitUntilEngine` (durable, WU-LPR-302) | `pipeline-application/…/waituntil/WaitUntilEngine.kt` | autoridad real; 4 de 5 salidas emiten |
| `BodyExecutionEngine` (legacy) | `…/durable/BodyExecutionEngine.kt:305,323` | loop legacy; 2 tokens, `else` sobre `StepOutcome` |
| `CoreWaitUntilStep` | `…/application/CoreWaitUntilStep.kt:193` | **candidato no enrutado; ver §5.3** |

---

## 4. `CatchErrorTriggered.buildResult` / `stageResult` — la cadena completa

El campo parece `CLOSED` si se conocen los valores Jenkins habituales. La auditoría dice
que **no**, y por una razón más profunda que "el productor usa `String`".

### 4.1 Tres autoridades en conflicto para el mismo dato

```text
DSL        StageScope.catchError(buildResult: String? = null, stageResult: String? = null)
             @Deprecated(LFC1-007)   ← superficie que se está retirando
   ↓
IR         CompiledPipeline.CatchErrorOverlay(buildResult: String, stageResult: String)
             NO-NULL. El null del DSL ya se resolvió en algún sitio.
   ↓
Contexto   ContextOverlay.CatchErrorOverlay(String, String, …)
   ↓
Decisión   RunLifecycleEngine.walkCatchErrorChain   ← aquí se decide el destino del run
   ↓
Evento     CatchErrorTriggered(buildResult: String?, stageResult: String?)
             NULLABLE otra vez, con ida y vuelta por "" en el codec
```

El mismo dato es nullable en el DSL, no-null en el IR y nullable en el evento. El codec
lo traduce con una ida y vuelta por cadena vacía (`EventJsonWriter.kt:605` escribe
`event.buildResult ?: ""`; `JsonEventLog.kt:764` lee `?.takeIf { it.isNotEmpty() }`). En
producción el overlay de dominio **nunca es null**, así que esa ida y vuelta existe sólo
para satisfacer un tipo publicado que el productor real ya no puede cumplir.

### 4.2 La decisión matchea por token sobre un `String` abierto

`RunLifecycleEngine.kt:127`:

```kotlin
when (overlay.buildResult) {
    "FAILURE" -> Unit                    // re-lanza hacia el siguiente scope
    "SUCCESS" -> return CanonicalContinuation.Continue
    else      -> return CanonicalContinuation.ContinueUnstable
}
```

El vocabulario Jenkins es `{SUCCESS, UNSTABLE, FAILURE}`. El `else` funde `UNSTABLE` con
**cualquier otra cadena**: un typo, `"success"` en minúscula, `""`, `"WAT"`. Todos
caen en `ContinueUnstable`, es decir: **suprimen el fallo del pipeline**. Es un
fail-open en la máquina de control del run, no en un observador.

Y el KDoc del propio DSL dice `null = default Jenkins UNSTABLE`, mientras el
default real de Jenkins para `buildResult` es `SUCCESS`. La documentación local y el
comportamiento Jenkins discrepan, y la implementación local sigue la documentación local.

### 4.3 `stageResult` es un parámetro semántico muerto

Viaja DSL → IR → contexto → evento por cuatro capas y **ningún consumidor lo lee**: la
decisión de §4.2 usa exclusivamente `buildResult`. Es el `dead semantic parameter` de la
Semantic Constitution Law §2 (`metadata accepted but never interpreted`).

### 4.4 El decoder también es fail-open aquí

`JsonEventLog.kt:765`:

```kotlin
val stageResult = EventJsonFields.stringField(s, "stageResult") ?: "UNSTABLE"
```

Un campo ausente se convierte en `UNSTABLE`, que es un hecho semánticamente distinto de
`FAILURE` (la pipeline continúa vs. aborta). Es peor que el default de §3.2 porque el
valor inventado **cambia el significado del run**.

### 4.5 Decisión implicada

El DSL está `@Deprecated` y siendo reescrito pre-compiler hacia `try/catch` del
orquestador. Esa es la ventana para cerrar la autoridad: el camino de salida puede no
emitir `CatchErrorTriggered`, y el valor por defecto debe decidirse con la política de
compatibilidad documentada, no heredado de un `else`.

---

## 5. Defectos encontrados durante la auditoría

Todos reproducibles por lectura; ninguno depende de la clasificación de E1 para
corregirse, y todos son anteriores a P3-E.

### 5.1 `else` sobre ADT sellado en frontera de proyección

`ParallelStageEngine.kt:216` proyecta `StepOutcome` con `else -> "success"`. Un
`StepOutcome.Cancelled` futuro se reportaría como **éxito**. Es el patrón que
`RetryEngine:128` ya resolvió con `else -> error(...)` y un comentario explicando por
qué — la lección se aprendió una vez en el repo y el hermano no la aplicó.

Escaneo de hermanos sobre `pipeline-events`: hay **un solo defecto real**
(`ParallelStageEngine`). El otro hit (`DslCompiledPipelineCompiler:387`,
`else -> "{}"`) es fallback de esquema JSON, no proyección de ADT. El defecto es
**aislado**, no sistémico.

### 5.2 El bucle `typed → String → typed` de `post`, y una autoridad de stage partida en tres

`StageExecutionEngine.runPostBlock` (línea 304) recibe `stageFinishedOutcome: String` y
lo reconvierte con `PostCondition.outcomeOf(String)` (línea 314) —que ya falla cerrado a
`INFRASTRUCTURE`— antes de pasarlo a `PostPlanner`, que sí recibe el ADT tipado. La
conversión cruza una frontera de función y el type system no la ve.

La forma total del defecto, en el camino normal de un stage:

```text
StageExecutionEngine.StageOutcome.SUCCESS     (enum duplicado)
   ↓ .text
"success"                                     (String)
   ↓ PostCondition.outcomeOf(String)
domain.post.StageOutcome.Succeeded            (ADT canónico)
   ↓ PostPlanner
plan                                         (decisión pura)
```

Y el dato interesante: **la autoridad de stage está repartida en tres sitios que no se
conocen entre sí**.

| # | Autoridad | Tokens | Productor real |
|---|---|---|---|
| 1 | `StageExecutionEngine.StageOutcome` (enum duplicado) | `success unstable failed skipped` | `finalizeStage`; su caso `SKIPPED` **no tiene ningún productor** |
| 2 | `PostCondition.outcomeOf(String)` | `success unstable failed aborted skipped` | `runPostBlock` |
| 3 | literal `"skipped"` en el coordinador | `skipped` | `CanonicalDurableRunCoordinator:423` |

La fila 3 es la que importa para el diseño. `runPostBlock` tiene un **segundo consumidor**
—el camino de `SkipStage`— que no pasa por ninguno de los dos enums: escribe el literal
`"skipped"` directamente. Por tanto:

- `PostCondition.outcomeOf("skipped")` **sí** tiene consumidor. Una primera lectura de esta
  auditoría lo dio por rama inalcanzable y es incorrecto: el propio KDoc de `runPostBlock`
  lo declara, y el coordinador lo llama. Queda corregido aquí antes de que se use como
  base de una decisión.
- `aborted` **sí** es alcanzable por `outcomeOf` y **no** por ningún productor: el token
  existe en el decodificador y nadie lo escribe.
- `StageExecutionEngine.StageOutcome.SKIPPED` sigue muerto, pero el **concepto** skip está
  vivo. Lo que está partida es la autoridad, no el comportamiento.

Consecuencia para E2: unificar sólo el enum duplicado no cierra el bucle. Hay que dar a
`runPostBlock` un parámetro ADT, y dar al camino de skip una forma de construir ese ADT
sin escribir una cadena.

### 5.3 `CoreWaitUntilStep` emite hechos falsos

El handler registrado emite `WaitUntilPolled(conditionResult = true)` con el comentario
`// Stub: condition assumed met` y `WaitUntilCompleted(outcome = "completed",
totalAttempts = 1, totalDurationMs = 0L)` **sin evaluar nada**.

No está enrutado en producción (`StructuralFamilyResolver` sigue yendo a `LegacyCore`),
así que no es un defecto activo: es una **bomba de relojería en la superficie pública**.
Un `StepDefinition` registrado que produce un evento de éxito sin ejecutar es exactamente
lo que la Semantic Constitution Law §2 prohíbe (`silent no-op`, `default success`) y lo
que ADR-0074 clasifica `IMPLEMENTED_UNCERTIFIED`.

El mismo fichero duplica el vocabulario: `WaitUntilOutput.resultOutcome: String` con el
comentario `"completed" or "deadline-exceeded"`, y un getter que hace
`if (resultOutcome == "completed") Success else Failure(TIMEOUT)` — **cualquier** otra
cadena se convierte en fallo por timeout.

### 5.4 `totalAttempts` fabricado en la rama `Aborted`

`WaitUntilEngine.kt:312-319`. `WaitUntilReconciliationDecision.Aborted` **no tiene
`attempt`** (sólo `operationId` y `reason`), así que el motor escribe:

```kotlin
attempt = decision.operationId.hashCode()     // journal.updateStatus(…)
emitCompleted(overallStartMs, decision.operationId.hashCode(), "aborted")
```

El `journal.updateStatus` **escribe en el estado durable un número de intento que nunca
existió**. Esto no es sólo un campo de evento mal proyectado: es integridad del journal.
Un replay posterior lee un `ABORTED` en un intento inexistente. `totalAttempts` publicado
recibe el mismo hash, así que un observador que calcule "cuántos intentos llevó este
waitUntil" lee basura.

### 5.5 `StageOutcome` duplicado, con una decisión de emisión separada

`StageExecutionEngine.StageOutcome` es una segunda autoridad sin `Aborted` y con un
`SKIPPED` que el enum no alcanza (el skip real llega por el literal del coordinador,
§5.2 fila 3, y el skip tiene además su propio evento `StageSkipped`).
`finalizeStage(…, emitStageFinished: Boolean = true)` es una decisión **separada** del
outcome, con 2 llamadas en `false`; `StageOutcome.FAILED` por tanto tiene dos desenlaces y
una función total `StageOutcome → StageFinishedOutcome` sería conceptualmente falsa.

El propio KDoc de la clase lo admite: *"the coordinator passed outcome STRINGS while the
engine passed [StageOutcome]"*. La divergencia está documentada en el fichero donde vive,
lo que la convierte en deuda conocida y no en defecto oculto.

### 5.6 `StashFailed.operation`: cerrado sin autoridad, y dos tipos homónimos

`StashOperationsAdapter.stashFailedEvent(name, op: String, reason)` es el productor. Se
llama desde 20 sitios de emisión y **sólo con dos literales**: `"stash"` nueve veces y
`"unstash"` once. Por observación es un vocabulario cerrado, pero no hay ningún tipo del
que derivarlo.

La corrección no es un enum en `events`. La autoridad va en la costura de stash —donde se
decide qué operación se intentó— y el evento la proyecta. Poner el ADT abajo del todo
haría que el Event Plane fuera dueño de una decisión que no toma.

Además hay **dos tipos llamados `StashFailed`**:

| Tipo | Módulo | Campos |
|---|---|---|
| `application.Capabilities.StashFailed` | `pipeline-application` | `failureKind`, `message` |
| `events.StashFailed` | `pipeline-events` | `operation`, `reason` |

Son dos cosas distintas —un fallo de adquisición de capacidad y un fallo de operación de
stash— con el mismo nombre en dos módulos publicados. `StashOperationsAdapter` lo resuelve
importando el segundo con el alias `StashFailedEvent`, que es la clase de defecto que los
nombres homónimos producen siempre: el alias documenta el síntoma, no lo previene. E5 debe
decidir si esto es un caso de colisión nominal que la autoridad de madurez tiene que
registrar.

### 5.7 `StepSpec.Error.failureKind`: vocabulario cerrado con default fail-open

Encontrado verificando el backlog de SDDK (item `bl-bl-01M3HT3MYY`, P2, capturado el
2026-09-27). Los criterios **siguen vigentes hoy**: es deuda real, no una alerta caducada.

`error()` declara su kind en tres receptores distintos, y el `StepSpec.Error` que lo
transporta lo declara una cuarta vez:

```kotlin
data class Error(val message: String, val failureKind: String = "UNKNOWN")
fun error(message: String, failureKind: String = "UNKNOWN")   // ×3
```

La autoridad cerrada existe y es fuerte: `FailureKind`, diez casos, y el codec de
`CoreErrorStep` **falla cerrado** ante un token desconocido. El defecto no está en la
existencia del vocabulario sino en su default:

- `error("msg")` es una intención **autorada**: alguien la escribió, con un mensaje, para
  parar el run. Eso es `USER` por definición.
- `UNKNOWN` significa "este runtime no pudo clasificar el fallo", que es lo que devuelve un
  decoder que no reconoce un token — un estado epistémico distinto.

El efecto en producción es que **todo `error()` sin anotación salía al stream durable con
`failureKind = UNKNOWN`**, indistinguible de una avería no diagnosticada. Para el Observer
que S5.4 quiere construir, esa es exactamente la diferencia entre "el pipeline falló por
diseño" y "no se sabe qué pasó".

DosProperties que lo hacen notable:

1. **Ningún test lo cubría.** Los tests existentes verifican lo correcto —que un
   `failureKind` desconocido falle cerrado— pero nadie miraba el default. Misma clase de
   defecto que el `decodeEvent` sin rama de §4.1: un comportamiento que existía sin nada
   vigilándolo.
2. **El patrón es aislado.** Un barrido de defaults `String` en el DSL devuelve exactamente
   estos cuatro sitios, todos `failureKind`. No es sistémico.

Corrección aplicada en E4a: default `USER` en los cuatro sitios, test que fija el valor y
que los tres receptores no divergan, y mutación que devuelve `UNKNOWN` a uno solo de los
tres para probar que el test la detecta.

El parámetro sigue siendo `String`: `pipeline-scripting-api` es ABI publicada y tiparlo
contra `FailureKind` es la migración de E6, gobernada por la madurez de E5.

---

## 6. Qué NO autoriza este inventario

1. **No autoriza** crear un tipo de proyección para `WaitUntilCompleted.outcome` antes de
   cerrar la matriz de §3.1. La matriz dice que el campo mezcla dos ejes y que una salida
   terminal no emite; el tipo se deriva **después** de esa decisión.
2. **No autoriza** tipar `CatchError` sólo en el Event Plane. §4.1 muestra tres autoridades
   en conflicto; un enum abajo no arregla el `else` de §4.2.
3. **No autoriza** retirar `StageOutcome.Skipped`/`Aborted` de `pipeline-domain`: es ABI
   publicada. Y el hallazgo correcto es matizado, no binario: `Aborted` no tiene productor;
   `Skipped` **sí** lo tiene (§5.2 fila 3), pero llega por un literal en el coordinador en
   lugar de por el tipo. Eso hace el caso de E2 más fuerte, no más débil: lo que falta no
   es el valor, es la autoridad compartida. La pregunta "¿debe un stage skipped ejecutar
   algún `post`?" ya tiene respuesta afirmativa en producción —los ejecuta— y queda sólo
   la pregunta independiente de si `Skipped` debe seguir viviendo en `StageOutcome` o
   tener forma propia.
4. **No autoriza** un `EventOutcome` universal. Los cuatro productores tienen
   vocabularios y alcances distintos; deduplicar por estética reintroduce el defecto que
   P3-E viene a cerrar.
5. **No autoriza** normalizar `failure`/`failed` ni `success`/`succeeded`. Son historia
   de cable, no descuido.

---

## 7. Decisiones que este inventario deja abiertas

| # | Decisión | Depende de |
|---|---|---|
| D1 | Modelo de terminación de `WaitUntilCompleted`: un eje o dos; y si `RejectDivergence` debe emitir | E4 |
| D2 | Autoridad upstream de `CatchError`: ADT en el DSL vs. sólo en IR, y valor por defecto | E4 |
| D3 | Si `stageResult` se interpreta o se retira | E4 |
| D4 | Tratamiento de `CoreWaitUntilStep`: `QUARANTINED` vs. implementar el handler real | decisión independiente |
| D5 | Madurez de `pipeline-events` y del resto de módulos publicados | E5 |

D4 y D5 no bloquean E2 ni E3. D1, D2 y D3 bloquean E4 y por tanto E6.