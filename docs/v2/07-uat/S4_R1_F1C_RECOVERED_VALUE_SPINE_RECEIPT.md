# S4-R1 F1-C — El valor recuperado llega al programa

**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` · **ADR:** `ADR-S4-R1` §2.3 §2.4 §2.7
**Base:** `d0fa34e83215744dbf3a1b348ac78d9bea2e5353` · **Rama:** `s4-a1b-scripted-shell-spine`
**Veredicto:** las dos manifestaciones de `implementation conformance: PARTIAL` quedan **CERRADAS**,
y el cierre de F1 queda pendiente sólo de la evidencia consolidada (§9).

```text
UNA sola durable spine

FRESH ───────┐
REUSE ───────┼─→ CommonExecutionResult ─→ dispatch ─→ consumidor
RECOVER ─────┘

sin: segunda autoridad scripted · pérdida de typed output · clasificación
     Step-specific en el observer · reejecución para fabricar valores · branches por StepKey
```

---

## 1. Lo que F1-C0 congeló, y por qué ese congelamiento era la única forma honesta de empezar

F1-C0 (`d0fa34e8`) midió la verdad **antes** de tocar producción, en dos mitades explícitas:

| mitad | qué mide |
|---|---|
| **HALF A** | lo que el spine **hace**, end-to-end, con `core.sh` real y journal real |
| **HALF B** | lo que el contrato **dice**, con `classifyShellTerminal` + `toStepOutcome` sobre los MISMOS hechos |

La distancia entre las dos mitades **era** el defecto, y un solo valor esperado la habría borrado.
Cero cambios en producción en ese commit: 595 líneas de test, 16 tests, 0F/0E.

Medición congelada, abreviatura de las 12 filas:

| returnMode | hecho | status | valor | veredicto congelado |
|---|---|---|---|---|
| NONE | exit 0 / exit 42 | SUCCEEDED / FAILED | ninguno | correcto |
| **STATUS** | **exit 42** | **FAILED** | **ninguno** | **DEFECTO** |
| STDOUT | exit 0, `output.txt` = `"abc"` / vacío | SUCCEEDED | ninguno | gap ×3 |
| — | `timeout.flag` / directorio vacío / sin control root | FAILED_TIMEOUT / LOST / RUNNING | — | correcto / correcto / fail closed |

**Las dos REDs iniciales de F1-C0 eran premisas mías, no defectos**: conté 4 gaps y afirmé 3, y una
fila asumía que un heartbeat fresco produciría terminal en mano cuando no hay proceso real detrás.
Se corrigió el **alcance** del test, nunca la afirmación.

## 2. C1 — el observer da hechos, y deja de dar significados

`RunningSubprocessObservation.Recovered(RecoveredTerminal)` → **`Observed(terminal: DurableTaskTerminal)`**.

| antes | después |
|---|---|
| `recoveredShellTerminal(exitCode)` — resucitado dentro del observer | **eliminado** |
| `TimedOut` como caso propio del observer | `DurableTaskTerminal.Cancelled(InterruptionRecord(TIMEOUT))` |
| `Lost` como caso propio del observer | `DurableTaskTerminal.Lost(FailureRecord(DURABLE_TASK_LOST, INFRASTRUCTURE, …))` |

`observedTerminal(controlDir, exitCode)` lee `result.txt` → `exitCode` y `output.txt` → `capturedStdout`
con `Files.exists`, de modo que **`null` significa ausente y `""` significa presente-y-vacío**: dos
hechos que el observer anterior colapsaba. El console log **no** se lee como sustituto del stdout
tipado: son dos canales distintos y confundirlos sería fabricar.

El resolver no conoce `returnMode`, ni `core.sh`, ni ejecuta codecs. `returnMode` viaja **dentro**
del input durable y lo recupera el `inputCodec` del propio Step.

## 3. C2 — materializador genérico + proyección propiedad del Step

Dos piezas nuevas, ninguna de las cuales es una autoridad:

- `pipeline-domain/…/domain/step/RecoveredStepProjection.kt` — `fun interface RecoveredStepProjection<I, O>`
  + `sealed interface RecoveredProjection<out O> { Materialised(value, outcome); InsufficientEvidence(reason) }`.
  **Aditiva**: no rompe `StepContract` ni `StepDefinition`. El engine pregunta por la **interfaz**,
  nunca por `StepKey`.
- `pipeline-application/…/durable/RecoveredExecutionMaterializer.kt` — puro,
  `(definition, encodedInput, terminal, stepPayload) → Materialised | InsufficientEvidence | NoProjection | MalformedInput`.
  No tiene handler, ni capabilities, ni I/O, ni reloj, ni branch por `StepKey`.

```text
resolve definition   StepRegistry          — el registry posee la identidad
decode input         inputCodec del Step   — el Step posee su payload
project the terminal RecoveredStepProjection — el Step posee su semántica
encode the value     outputCodec del Step  — el Step posee su forma de cable
```

**Sin fallback**: un Step con terminal recuperable y sin proyección falla cerrado **nombrando el
Step** (`NoProjection(key)`), que es la razón por la que el seam es no-nullable (ver §6.1).

### 3.1 La guarda de evidencia insuficiente vive en la PROYECCIÓN

`classifyShellTerminal` mapea `STDOUT` a `Stdout(capturedStdout.orEmpty())`. Para el camino **fresh**
eso es correcto: el runtime acaba de ejecutar el proceso, así que `null` significa «no imprimió nada».

Para un terminal **recuperado** es incorrecto, y silenciosamente: `null` significa que nadie escribió
`output.txt`. `orEmpty()` colapsa «presente-y-vacío» y «ausente» en un mismo valor, así que un
`returnStdout` recuperado respondería una pregunta que nadie observó.

Meter la guarda en `classifyShellTerminal` habría significado enseñarle a una autoridad compartida
una distinción («lo acabo de ejecutar» vs «lo encontré después») que no es suya: se llamaría también
desde el camino fresh, donde la guarda sería código muerto, y **una regla inerte en un camino y
decisiva en otro es una regla que nadie puede razonar**. Por eso vive en el Step que sabe qué modo de
retorno necesita qué evidencia.

`core.sh` **reutiliza** `classifyShellTerminal` y `toStepOutcome`; no copia su tabla.

## 4. C3 — R14: el carrier llega entero

```kotlin
RecoveryInterpretation.Settled(val result: CommonExecutionResult)   // era (outcome: StepOutcome)
StepDispatchEngine.Dispatched(val result: CommonExecutionResult, val context: ExecutionContext)
```

Sin accesor `outcome` de conveniencia: los consumidores proyectan explícitamente `.result.outcome`
(cursor del dispatch engine, composición del carrier en 4 sitios, 2 lecturas de body,
`ParallelStageEngine`, `StageExecutionEngine`, `ScriptedRegistryInvoker`, `S4RKernelSpikeTest`).

El `OperationOutput` de recovery usa **el mismo puente que `DurableStepExecutor`**
(`JsonPrimitive(encoded.value)`, reloj del sistema), de modo que una fila recuperada y una fresh se
escriben con una sola forma y nada aguas abajo necesita saber de qué dirección salió el valor.

### 4.1 La regla del status durable, descubierta durante la implementación

El outcome materializado gobierna el status **sólo** para `Exited`. Para `Lost`/`Cancelled` lo gobierna
**el terminal**:

```kotlin
status = if (terminal is DurableTaskTerminal.Exited) m.result.outcome.toOperationStatus()
         else terminalStorageStatus(terminal)
```

Sin esa corrección un `Lost` se journalizaba `FAILED`, y eso **destruye el vocabulario de
almacenamiento** que dice «no se pudo determinar el resultado», del que dependen D-1 y §2.3. La regla
quedó fijada por una aserción nueva, no sólo por un KDoc (§6, `S4RPolReplaySemanticsSpikeTest` fila 4).

## 5. El hallazgo que cambió el diseño: el input durable NO es el payload del Step

Lo encontró el gate, con 8 REDs que decían literalmente
`the durable input could not be decoded by the Step's own codec` — **en ambas superficies**.

**Ninguna** superficie almacena el payload del Step como input durable. Ambas guardan un **envoltorio
de identidad**, porque el fingerprint se calcula sobre identidad:

```text
canónico   params = { payload: <JSON del Step, como string>, sandboxProfile?, bodyStructure? }
scripted   params = { callSiteId, dynamicScopePath, invocationOrdinal,
                      encodedInput: <JSON del Step, como string>, definitionDigest }
```

Un codec al que se le entrega el envoltorio falla en `kind` y el error se lee como registro corrupto.
Solución: `Request.stepPayload: String?`, que **entrega la superficie que escribió el payload**
(`step.payload.encoded` en la canónica, `call.encodedInput` en la scripted). `input` queda intacto,
porque el append al journal **debe** conservar el envoltorio: cambiarlo cambiaría el fingerprint de
toda operación ya escrita en disco, y el campo lleva sólo la vista de materialización, nunca una
re-serialización.

## 6. Mutaciones: tres, con atribución medida

Las tres compilan por construcción (usan sólo símbolos ya importados), porque **un error de compilación
no es un RED** — Gradle ejecutaría la clase vieja.

| mutación | fichero · pre-hash | mutado | resultado | restaurado |
|---|---|---|---|---|
| **M-F1-C1** exit no-cero clasificado Failure **antes** de `returnMode` | `CoreShellStep.kt` · `c462c9ad…` | `8ef392d1…` | 17 tests → **2 RED**, 15 verdes | `c462c9ad…` ✓ |
| **M-F1-C2** `capturedStdout == null` eliminado (`orEmpty()` vuelve a fabricar) | `CoreShellStep.kt` · `c462c9ad…` | `caa67576…` | 17 tests → **2 RED**, 15 verdes | `c462c9ad…` ✓ |
| **M-F1-C3** carrier estrechado a `StepOutcome` en el brazo `Materialised` | `RecoveryInterpretationEngine.kt` · `915273df…` | `d5ec4bc9…` | **sobrevivió** → luego **1 RED**, 37 verdes | `915273df…` ✓ |

Restauración por `cp --` + `sha256sum -c` en las tres; 0 residuos de `MUTANT` en todo el árbol. **No
se usó `git restore` ni `git checkout`** para ninguna.

### 6.1 Las dos primeras matan 2 filas, no 1, y la atribución es 1 causa

M-F1-C1 tumba **A4** y **«the materialiser materialises a real core shell terminal…»**; M-F1-C2 tumba
**A7** y el mismo test del materializador. Leídos los mensajes, **ambos pares son la misma fila de la
tabla vista desde dos ángulos**:

```text
M-F1-C1  fila STATUS+42  → A4 esperaba SUCCEEDED, obtuvo FAILED
                        → el materializador esperaba Success, obtuvo Failure(SCRIPT, "shell exited with code 42")
M-F1-C2  fila STDOUT+0+output.txt AUSENTE
                        → A7 esperaba FAILED, obtuvo SUCCEEDED
                        → el materializador obtuvo {"kind":"STDOUT","outcome":"SUCCESS","value":""}
```

Es decir: **1 mutación, 1 fila, 2 observadores de esa fila, 15 filas intactas**. Se declara así porque
«2 REDs, 2 mutaciones» sería falso y «2 REDs, 1 mutación» sin más detalle escondería que la fila se
observa dos veces.

### 6.2 M-F1-C3 sobrevivió a todo, y por eso nació una fila

Es el resultado más importante del bloque. La primera pasada (matriz) y la segunda (las tres fronteras
de consumidor) dieron:

```text
S4R1F1CRecoveryTruthMatrixTest   17/17  VERDE bajo la mutación
S4RKernelSpikeTest                8/8   VERDE
S4RRecIndeterminateEffectSpikeTest 12/12 VERDE
ScriptedScopeTest                13/13  VERDE   (antes de la fila nueva)
```

**R14 no estaba certificado por ningún test del radio.** La mutación estrecha el carrier que llega al
*consumidor* y deja el `append` al journal intacto (`encoded = m.result.encodedOutput`), así que el
valor se journalea correctamente y simplemente nunca llega al programa. La matriz lee
`row?.output` — la fila del journal — y por su propio KDoc mide «los bytes que producción realmente
journaleó». **No podía verlo, y no es un defecto suyo: es la frontera equivocada para R14.**

La fila que faltaba, en `ScriptedScopeTest`:

```text
a recovered exit code reaches user Kotlin as the exit code it observed
```

Inyecta `Exited(42)` — el terminal **con hechos**, frente al `Lost` de la fila vecina que
correctamente falla cerrado — y afirma que el Kotlin del usuario recibe `42` con `launches == 1`.
Bajo la mutación cae con `FailureKind.ENGINE`, que es exactamente la forma consumer-facing de la
pérdida de R14. Contra el código real pasó **14/14** antes de usarse como diana.

**Atribución final de M-F1-C3: 1 RED, 37 verdes.** La fila previa, que ya declaraba el hueco en su
propio KDoc («an `Exited(42)` terminal DOES carry that fact… the canonical gap F1-C owns»), queda ahora
como su contraparte: la que falla cerrado porque no hay hechos, y la que entrega el valor porque sí.

## 7. Aserciones reescritas con transición declarada, y por qué no son rebajas

| aserción | antes | después | por qué |
|---|---|---|---|
| `S4RPolReplaySemanticsSpikeTest` fila 4 | `Failed(REPLAY_COMPATIBILITY)` | `Failed(INFRASTRUCTURE)` + **nueva** `LOST` | el kind cambió porque la ley nombra el **hecho del substrate**, no la negativa de una superficie |
| `S4RRecIndeterminateEffectSpikeTest` fila 5 | `SUCCEEDED` (sin proyección) | `FAILED` (fail closed) | el `Probe` no declara proyección; sin ella no hay valor honesto |
| `S4RecoveryRequiredNeverExecutesTest` | `terminal is RecoveredTerminal.Lost` | `DurableTaskTerminal.Lost` + **nuevo** `record.code == "REATTACH_WINDOW_EXPIRED"` | el **comportamiento es idéntico**; lo obsoleto era el nombre del tipo |

La primera merece el detalle, porque el valor se extrjo del XML y no de una hipótesis:

```text
control dir virgen → StepReconcilerL1.Classification.Lost
                  → Observed(Lost(FailureRecord("DURABLE_TASK_LOST", INFRASTRUCTURE, …)))
                  → classifyShellTerminal(Lost, NONE) → failure.asShellFailure()
                  → toStepOutcome() → Failure(INFRASTRUCTURE)
```

Es decir: **no** es `failedClosed`. El brazo `RecoverRunning` **materializó el hecho real** a través de
la proyección de `core.sh`. La respuesta pasó de nombrar *la negativa de una superficie* a nombrar *el
hecho observado*, que es lo que hace que las dos mitades converjan en vez de sólo coincidir en estar
cerradas. La aserción nueva sobre `LOST` fija la regla de §4.1 por test.

## 8. Fitness: `RecoveredValueSpineFitnessTest` (4 leyes, 250 líneas)

Lee **código sin comentarios** vía `codeOnly()`, que **borra** el bloque de comentario. No es
hipotético aquí: **cinco** ficheros de producción contienen hoy la cadena `when (stepKey)` dentro de
KDoc, y los cinco *describen la prohibición* que este fitness aplica. Un escaneo que contara prosa
estaría midiendo la diligencia de la documentación.

| ley | afirma | verificado |
|---|---|---|
| 1 | ningún `when` sobre clave de Step en `src/main` | sonda temporal → RED con `file:line`, otras 3 GREEN |
| 2 | el observer nombra `StepOutcome` / `OperationStatus` / `ShellReturnMode` / `classifyShellTerminal` | `NINGUNO` medido |
| 3 | `Settled` y `Dispatched` llevan `CommonExecutionResult`, y el motor no re-deriva (`asStepOutcome(` / `asOperationStatus(`) | firmas confirmadas; re-derivación ausente |
| 4 | exactamente **un** consumidor de producción de `RecoveredExecutionMaterializer` | 2 ficheros: declaración + `RecoveryInterpretationEngine` |

**La Ley 1 se estrechó después de medir, y por eso importa el registro.** La versión obvia —«ningún
`when (key)` en producción»— es **falsa**: `BodyExecutionEngine` construye
`PluginStepId("wait-until-poll")` y `PluginStepId("retry-attempt")` como segmentos de **identidad**
durable, y `LspMetadata` / `YamlEventContractCodec` conmutan sobre enums sin relación. Una ley que
falla sobre código correcto es una ley que acaba «arreglándose» borrando la ley. Construir un
`PluginStepId` no es conmutar sobre una clave.

### 8.1 La sonda que valida el propio fitness

Un fitness que nunca ha fallado no demuestra nada. Se insertó un `when (definition.contract.key)`
degenerado en `RecoveredExecutionMaterializer` y se comprobó que **la Ley 1 y sólo la Ley 1** pasa a
ROJA, citando `RecoveredExecutionMaterializer.kt:32`. Sonda retirada; `grep` de residuos = 0.

**Corrección de procedimiento registrada**: el hash de referencia de esa sonda se capturó *después*
de aplicarla, así que el `sha256sum -c` posterior falló con «la suma no coincide» y la alarma era
**mía, no del fichero**. La restauración se verificó por contenido (195 líneas, bloque `val key` /
`val projection` / `?:` intacto) y por ausencia de la sonda, y el fitness volvió a 4/4.

## 9. Evidencia de gate

### 9.1 Radios de impacto

```text
cd v2 && ./gradlew :pipeline-domain:test :pipeline-architecture-tests:test --rerun-tasks
arranque 12:46:31 · EXIT=0 · ^e: 0
221 XML frescos · failures=0 · errors=0 · skipped=10 · stale=0

cd v2 && ./gradlew :pipeline-application:test --rerun-tasks --tests <14 clases del radio>
arranque 12:50:06 · EXIT=0 · ^e: 0 · BUILD SUCCESSFUL in 3m 50s
14 clases · 142 tests · 0F · 0E · 0S · XML más antiguo 12:53:56 > arranque
```

### 9.2 Gate focal final, tras retirar `RecoveredTerminal` y añadir el fitness

```text
cd v2 && ./gradlew :pipeline-application:test --rerun-tasks --tests <14 clases>
arranque 13:14:17 · EXIT=0 · ^e: 0 · BUILD SUCCESSFUL in 3m 28s
14 clases · 143 tests · 0F · 0E · 0S · 14 XML frescos
```

El delta 142 → 143 es la fila nueva de §6.2. Verificado **por nombre y frescura de XML**, nunca por
exit code.

### 9.3 Arquitectura completa, con el fitness incluido

```text
cd v2 && ./gradlew :pipeline-architecture-tests:test --rerun-tasks
EXIT=0 · ^e: 0 · 89 clases · 440 tests · REDS=0
RecoveredValueSpineFitnessTest  tests=4 F=0 E=0
```

### 9.4 Mutaciones

| corrida | arranque | EXIT | `^e:` | veredicto |
|---|---|---|---|---|
| M-F1-C1 | 12:54:53 | 1 | 0 | `BUILD FAILED in 2m 12s` · 2 RED / 15 verdes |
| M-F1-C2 | 12:57:54 | 1 | 0 | 2 RED / 15 verdes |
| M-F1-C3 (p1, matriz) | 13:00:19 | 0 | 0 | **17/17 verde — sobrevivió** |
| M-F1-C3 (p2, consumidores) | 13:02:42 | 0 | 0 | **33 verdes — sobrevivió** |
| M-F1-C3 (p3, con la fila nueva) | 13:08:20 | 1 | 0 | **1 RED / 37 verdes — KILLED** |
| post-restauración | 13:10:34 | 0 | 0 | 51 tests · 0 RED |
| sonda del fitness | 13:22:42 | 1 | 0 | Ley 1 RED, resto verde |

### 9.5 Dos fallos de compilación que habrían producido falsos rojos

Ninguno cuenta como evidencia, y los dos se detectaron leyendo `^e: ` antes que el resultado:

1. `S4RecoveryRequiredNeverExecutesTest.kt:267,272` — una edición previa dejó dos referencias a
   `failure` cuando el local se llama `record`. Gradle habría ejecutado la clase vieja y reportado 10
   REDs, **ninguno de ellos real sobre ese fichero**.
2. La fila nueva, en su primer intento: `DurableTaskOutput(controlDir = …)` con un `Path`, cuando el
   campo es `String`. Fichero no versionado, así que la corrección no podía apoyarse en `git`.

## 10. Retirada: `RecoveredTerminal`

Cuarenta y una líneas de `src/main` (28 de declaración + KDoc) y **cero** consumidores. Medido antes
de borrar: los únicos usos como tipo eran las cuatro auto-referencias de la propia declaración; la
línea 520 del test que la menciona está **dentro de un string**, y el `import` de la línea 7 quedaba
sin uso. Se retiraron también el import muerto y dos referencias KDoc `[RecoveredTerminal]`
convertidas a backticks, para no dejar links colgantes.

El valor de retirarla no es hygiene: un tipo semántico **nominado** en `src/main` invita a que alguien
lo use para clasificar un terminal otra vez, que es exactamente el defecto que §2 cerró.

## 11. Lo que este recibo NO es

- **No es el cierre de F1.** Falta la evidencia consolidada: conformance del ADR a `SATISFIED`, API/ABI,
  y el full local `./gradlew check --rerun-tasks` sobre el SHA de cierre.
- **El PRODUCT-GATE sigue `BLOCKED_EXTERNAL`.** No hay superficie de CI remota desde `754ddda0`, y «CI
  verde» no es una evidencia disponible en este repositorio. Declarar `NOT_RUN` como `PASS` sería un
  falso verde por construcción.
- **El gate focal no sustituye al full.** 14 clases y 143 tests son el radio de impacto *de este
  bloque*, no la suite. El `./gradlew check` completo se ejecuta una sola vez, al cierre.
- **No se certifica la calidad de la_WINDOW_EXPIRED policy.** D-1 sigue `DEFERRED`.

## 12. Mantenido DEFERRED durante F1

| # | por qué no aquí |
|---|---|
| **D-1** reattach no-terminal | cambiaría el resultado observable de un caso ya certificado |
| **D-2 / D-3** terminal semántico durable de `Unstable` | tocaría esquema y serialización |
| cualquier `OperationStatus.UNSTABLE` | D-2/D-3 |
| formato / schema / protocolo durable | F1 no lo cambia; el envoltorio de identidad se conserva intacto |
