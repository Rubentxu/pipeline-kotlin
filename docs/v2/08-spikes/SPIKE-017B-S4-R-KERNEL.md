# SPIKE-017B — S4-R-KERNEL

> **¿Puede el frontend SCRIPTED consumir la autoridad canónica de replay sin duplicar esa
> responsabilidad y sin perder el output tipado?**

- **Spike:** SPIKE-017B, hijo de SPIKE-017 (S4-R-POL)
- **Rama / SHA:** `s4-a1b-scripted-shell-spine` @ `fd7e45a7cd5264db525032d357c3fdd804bf0600`
- **Base de la evidencia previa:** `docs/v2/07-uat/S4_R0_REPLAY_IDENTITY_CONFLICT_MEMO.md` §4
  (dos autoridades de replay) y §8 (SPIKE S4-R-POL, 8 filas medidas)
- **Clasificación: FEASIBLE CON RESERVAS**
- **Cambio en `src/main`: CERO.** Todo el spike vive en el source set de test.

---

## 0. La invariante que este spike midió

> **scripted posee su dirección; no posee su replay protocol.**

Lo que se midió es exactamente esa frase descompuesta en obligaciones verificables:

| | OWNER | RESPONSABILIDAD |
|---|---|---|
| 1 | **Scripted frontend** | *addressing* (espacio de identidad) + adaptación Kotlin tipada |
| 2 | **Canonical durable** | reconciliación + recovery + protocolo de ejecución |
| 3 | **Step contract** | semántica (`effects` / `replayPolicy` / `recoveryPolicy`) |
| 4 | **Journal** | hechos durables |

Cuatro propietarios, cuatro responsabilidades. Si el candidato los respeta, no hay STOP.

### Marco normativo que gobierna el dictamen

| | Regla | Consecuencia exigida al candidato |
|---|---|---|
| **RPL-1** | Separate address, shared semantics. Namespaces de operación por frontend permitidos; semánticas de replay por frontend prohibidas. | La dirección puede diferir; la decisión no. |
| **RPL-2** | Descriptor truth. El `StepDescriptor` es la única autoridad de `effects` / `replayPolicy` / `recoveryPolicy`. | El frontend resuelve metadata por `StepKey`; no sustituye ningún campo. |
| **RPL-3** | Fingerprint honesty. El fingerprint codifica la política que realmente gobierna la reconciliación. | Se elimina el literal `ReplayPolicy.MEMOIZED` (`ScriptedRegistryInvoker.kt:203-205`). |
| **RPL-4** | Decisions do not materialise frontend values. | `ReuseCompleted` no se amplía: materializar es **después** de la decisión, con el codec declarado del Step. |
| **RPL-5** | Address namespace is not semantic policy. El prefijo `"scripted."` no cambia semántica. | Válido también para futuros frontends (worker remoto, MCP, agente Jenkins): no queremos cuatro kernels. |

### Corrección al encargo (aplicada)

El STOP previo sobre «segundo writer del `OperationJournal`» **se mantiene**. La sugerencia de
**prohibir `journal.get`** en la ruta scripted **se retira**:

- `journal.get(operationId)` **PERMITIDO** — pertenece a *addressing / identity space*. Localiza
  la fila que este frontend posee. Ver `ScriptedRegistryInvoker.kt:209` y `StepDispatchEngine.kt:374`.
- **PROHIBIDO** — la **tabla de decisión por `OperationStatus`** (`ScriptedRegistryInvoker.kt:216-242`).
  Una regexp que prohíba `journal.get` sería un error de diseño: aboliría el direccionamiento que el
  frontend sí debe poseer. Lo que se prohíbe es **interpretar** lo que devuelve.
- El namespace `"scripted." + stepKey` (`ScriptedRegistryInvoker.kt:369-370`) está defend ido por
  fitness: **no tocarlo, no unificarlo, no critcarlo.**

---

## 1. Qué se construyó y cómo se midió

**Esqueleto:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/spike/S4RKernelSpikeTest.kt`
(8 tests, 0 fallos, 0 skips).

Reutiliza el esqueleto de `S4RPolReplaySemanticsSpikeTest.kt` (mismo paquete, commit `fd7e45a7`):
`Prior` (declarado `internal` en aquel fichero a propósito), `CountingRegistry`, la forma `Spine`,
`seedPrior`/`seedKernelPrior`, `policyOf` por fuerza bruta del hash, y las tres superficies
(CANONICAL por `CanonicalDurableRunCoordinator`, SCRIPTED por `ScriptedRegistryInvoker`, y la nueva
CANDIDATE).

**El candidato NO es una clase de producción.** Es una composición **test-local de clases de
producción**:

```
ScriptedRegistryCall.operationId()          ← dirección (PROD, ScriptedRegistryInvoker.kt:70-73)
RegistryStepMetadataResolver.composite()    ← descriptor truth (PROD, RPL-2)
DurableInvocationResolver.reconcileInvocation()   ← decisión (PROD, :77-90)
RecoveryInterpretationEngine.interpret()    ← interpretación + efectos (PROD, :81-134)
RegistryExecutionPreparation.prepare()      ← admisión fail-closed (PROD, :33-67)
DurableStepExecutor.executeAndJournal()    ← ejecución + escrituras (PROD, :32-71)
buildDefaultExecutionBoundary(...)          ← única costura de ejecución (PROD)
ScriptedRegistryResult                      ← resultado cerrado, ya existente (PROD, :33-39)
```

No se creó `ScriptedReplayKernel`, ni `ScriptedDurableExecutor`, ni ningún carrier nuevo.
`CommonExecutionResult` (`CommonExecutionResult.kt:34-37`) **ya es** el carrier canónico, y K1 mide
que el seam canónico lo devuelve poblado.

**Comando y resultado real:**

```
cd v2 && ./gradlew :pipeline-application:test \
  --tests 'dev.rubentxu.pipeline.v2.application.spike.S4RKernelSpikeTest'
EXIT=0     (log: /var/home/rubentxu/.local/state/pipelinek-gates/s4rkernel-spike-run4.log)
tests=8 failures=0 errors=0 skipped=0
```

**Matriz medida (esta ejecución, extracto):**

```
| CANDIDATE | core.pwd  | fresh    | decision=Execute        | invocations=1 | journal=SUCCEEDED | fingerprintPolicy=MEMOIZED | capReads=1 | recoveryProbes=1 | journalWrites=2 |
| CANDIDATE | core.pwd  | SUCCEEDED| decision=ReuseCompleted | invocations=0 | journal=SUCCEEDED | fingerprintPolicy=MEMOIZED | capReads=0 | recoveryProbes=1 | journalWrites=0 |
| CANDIDATE | core.sh   | FAILED   | decision=Execute        | invocations=1 | journal=SUCCEEDED | fingerprintPolicy=RERUN    | capReads=1 | recoveryProbes=1 | journalWrites=1 |
| CANDIDATE | core.sh   | RUNNING  | decision=RecoverRunning | invocations=0 | journal=LOST       | fingerprintPolicy=RERUN    | capReads=0 | recoveryProbes=1 | journalWrites=1 |
| CANDIDATE | core.error| SUCCEEDED| decision=RejectedAbort  | invocations=0 | journal=SUCCEEDED | fingerprintPolicy=NEVER    | capReads=0 | recoveryProbes=1 | journalWrites=0 |
| SCRIPTED  | core.sh   | FAILED   | decision=SELF_INTERPRETED | invocations=0 | journal=FAILED  | fingerprintPolicy=MEMOIZED | capReads=0 | recoveryWrites=0 |
| SCRIPTED  | core.sh   | fresh    | decision=SELF_INTERPRETED | invocations=1 | journal=SUCCEEDED | fingerprintPolicy=MEMOIZED | capReads=2 | journalWrites=2 |
| CANONICAL | core.sh   | fresh    | decision=INTERNAL       | invocations=1 | journal=SUCCEEDED | fingerprintPolicy=RERUN    | capReads=-1 | journalWrites=2 |
```

---

## 2. Dictamen por fila

### K1 — FRESH: ejecuta una vez, por la frontera canónica, y el valor tipado sobrevive — **PASS**

| Evidencia | Cita |
|---|---|
| `decision=Execute`, `invocations=1`, `journal=SUCCEEDED`, `journalWrites=2` | `S4RKernelSpikeTest.kt` `k1 …` |
| El frontier canónico devuelve `encodedOutput` no nulo | `CommonExecutionResult.kt:34-37`; `RegistryExecutionBoundary.kt:171-178` |
| El valor que el frontend puede devolver es **byte-idéntico** al que produjo la frontera | aserción K1 |
| `journalWrites=2` = `beginOperation` + fila terminal, **ambos** del ejecutor canónico | `DurableStepExecutor.kt:44` y `:53-68` |

Medido: `Success(encoded=60 chars)` y `spine.lastSeamOutput == resultado.encodedOutput`.

**Lo que K1 expone además (P1, P2, P4):** `DurableStepExecutor.executeAndJournal` declara
`): StepOutcome` (`DurableStepExecutor.kt:42`), toma `val outcome = executionResult.outcome` (`:51`)
y `return outcome` (`:70`) — **descarta `CommonExecutionResult.encodedOutput`**, aunque lo persiste
(`:58-64`) y aunque la frontera se lo entrega. Es un estrechamiento accidental del lifetime de una
API interna, no una responsabilidad nueva. Ver §6.

### K2 — REUSE: ni handler ni capabilities, y aun así el valor Kotlin tipado — **PASS**

| Evidencia | Cita |
|---|---|
| `decision=ReuseCompleted`, `invocations=0`, `capReads=0`, `journalWrites=0` | `k2 …` |
| El valor reutilizado **iguala** al producido por la ejecución fresh | aserción `assertEquals(freshTyped.path, reusedTyped.path)` |
| El brazo `ReuseCompleted` no emite eventos ni escribe | `RecoveryInterpretationEngine.kt:117` |
| `ReuseCompleted` es un `data object`: no transporta output y no debe transportarlo | `CanonicalStructuralDecisions.kt:336` |

`capReads=0` es **exacto**, no una cota: en el candidato el único sitio que construye
`CanonicalRuntimeCapabilityAccess` y el único que construye `CanonicalRuntimeContext` están
**después** de `ProceedToExecution`; en el brazo `ReuseCompleted` no se alcanza ninguno de los dos, y
`coexecute` tampoco llega a ejecutarse.

### K3 — prior FAILED: re-ejecuta y **repara** la fila — **PASS**

| Superficie | `decision` | `invocations` | `journal` | fingerprintPolicy | retorno |
|---|---|---|---|---|---|
| **CANDIDATE** | `Execute` | **1** | **SUCCEEDED** | RERUN | `Success(encoded=60)` |
| **SCRIPTED** (actual) | tabla propia | **0** | **FAILED** | MEMOIZED | `Failed(REPLAY_COMPATIBILITY)` |

- `RERUN` + `SUCCEEDED → SKIP`, cualquier otro estado → `RERUN` (`EffectReplayPolicy.kt:74-79`).
- La tabla del invoker, en cambio, convierte `FAILED/ABORTED/FAILED_TIMEOUT/LOST` en
  `REPLAY_COMPATIBILITY` permanente (`ScriptedRegistryInvoker.kt:224-233`).

Confirma la fila 3 del memo y añade la reparación: el candidato **deja la fila terminal y correcta**,
donde el invoker la deja envenenada.

### K4 — prior RUNNING: entra en recovery y alcanza un estado terminal — **PASS con reserva**

| Evidencia | Cita |
|---|---|
| `decision=RecoverRunning`, `recoveryProbes=1`, `invocations=0`, `journal=LOST` | `k4 …` |
| `core.sh` declara `RecoveryPolicy.ExternalSubprocess` | `CoreShellStep.kt:447-456` |
| El puerto decide; el adaptador observa | `RunningSubprocessRecovery.kt:31-46`, `:55-58`, `:60-85` |
| El interpretador escribe la fila terminal y avanza cursor sólo si hay éxito | `RecoveryInterpretationEngine.kt:94-113` |

**RESERVA — NO OBSERVABLE:** un **Reattach exitoso** de un proceso vivo. Producirlo exige un
subproceso real de larga duración más un crash entre el spawn y la escritura terminal del journal.
Un spike no lo produce. Lo medido es el brazo fail-closed `Lost`
(`RunningSubprocessRecovery.kt:84` → `lostShellOutcome`, `OperationStatus.LOST`). Esto es una
**reserva**, no una prueba de reattach.

### K5 — NEVER es expresable — **PASS** (con una sub-fila medida, no end-to-end)

| Nivel | Medido | Cita |
|---|---|---|
| Decisión, fresh | `NEVER` + sin fila → `RERUN` | `EffectReplayPolicy.kt:87-88` |
| Decisión, journaled | `NEVER` + fila → `ABORT` | `EffectReplayPolicy.kt:87-88` |
| End-to-end candidato | `RejectedAbort`, `invocations=0`, `Failed(INFRASTRUCTURE)`, `fingerprintPolicy=NEVER` | `k5 …` |
| Fachada actual | `ScriptedStepFacade` **no declara** `error(...)`; ningún Step alcanzable declara `NEVER` | medido por reflexión en `k5` |

- `RejectedAbort` es una resolución de primera clase: `RecoveryInterpretationEngine.kt:119-131`.
- La contención de NEVER en el invoker actual es **latente**, no incidente: es un agujero de
  contención que el candidato cierra por construcción, porque sabe expresar NEVER.
- `core.error` (`CoreErrorStep.kt:147-155`, `requiredCapabilities = emptySet()` en `:170`) es el
  sujeto end-to-end. `http.request` (el otro NEVER, `HttpRequestStep.kt:79-90`, `requiredCapabilities`
  en `:240`) declara capabilities ausentes por defecto: su ejecución end-to-end es **NO OBSERVABLE**
  en este spike, y no se afirma lo contrario.

### K6 — la política del fingerprint es la del descriptor; la dirección no — **PASS**

| Superficie (core.sh, fresh) | `fingerprintPolicy` (bruto sobre el hash) |
|---|---|
| **CANONICAL** | `RERUN` |
| **CANDIDATE** | `RERUN` |
| **SCRIPTED** (actual) | **`MEMOIZED`** ← literal hardcodeado |

- El candidato hashea exactamente su propia `OperationInput` bajo `metadata.replayPolicy`, sin
  sustitución alguna.
- El canónico hashea bajo `metadata.replayPolicy` también (`StepDispatchEngine.kt:256`).
- El invoker actual hashea bajo `ReplayPolicy.MEMOIZED` para un Step `RERUN`
  (`ScriptedRegistryInvoker.kt:203-205`): el fingerprint **deja de codificar la política que
  realmente gobierna la reconciliación** (RPL-3).

**Corrección registrada.** La primera versión de este test afirmaba «la dirección es la única
diferencia» y **FALLÓ** (run 3, log `s4rkernel-spike-run3.log`). La afirmación era falsa: el
fingerprint es función de **toda** la `OperationInput`, y las dos superficies describen la operación
de forma legítimamente distinta — params canónicos `{payload}` (`StepDispatchEngine.kt:215-221`) frente
a params scripted, la tupla de identidad (`ScriptedRegistryInvoker.kt:191-199`). El hash completo
**debe** diferir, y difiere. Lo que no puede diferir es **el eje de la política**, que es el único
que la reconciliación lee, y eso es exactamente lo que aísla `policyOf`. El test afirma ahora las
tres truths: candidato = su input + política del descriptor; canónico = lo suyo + política del
descriptor; **hash completo ≠, política observada =**.

Y sobre la dirección, que es donde RPL-5 vive:

- `canonical.address` (`OpId.format()`, `OpId.kt:60-67`) ≠ `candidate.address`
  (`ScriptedRegistryCall.operationId()`, `ScriptedRegistryInvoker.kt:70-73`) — **y deben diferir**.
- `candidate.address == candidate.journalRow().id`: el candidato usa la regla **de producción**, no una
  reproducción.
- `input.stepId` canónico = `core.sh`; scripted = `scripted.core.sh` (`ScriptedRegistryInvoker.kt:369-370`),
  preservado verbatim. **Ninguno se normaliza al otro.**

### K6b — diferencial semántico candidato ≡ canónico — **PASS**

Dos escenarios (`core.sh`, `core.pwd`), prior `SUCCEEDED`, misma entrada:

| Eje | CANONICAL | CANDIDATE | ¿Deben coincidir? |
|---|---|---|---|
| `fingerprintPolicy` | RERUN / MEMOIZED | RERUN / MEMOIZED | **SÍ** ✔ |
| `journalTerminal` | SUCCEEDED | SUCCEEDED | **SÍ** ✔ |
| `handlerInvocations` | 0 | 0 | **SÍ** ✔ |
| `journalWrites` | 0 | 0 | **SÍ** ✔ |
| `decision` | interno | `ReuseCompleted` | comparable ✔ |
| `operationId` | distinto | distinto | **NO** — por diseño |

Ejes del diferencial (los que se comparan): `StepKey`, política de replay observada, `decision`,
handler count, recovery probes, transición y terminal de journal, encoded output, typed result,
failure kind, accesos a capability. **`operationId` nunca.**

### K7 — sin segundo writer, sin segundo protocolo de replay — **PASS**

| Medido | Valor | Por qué importa |
|---|---|---|
| Escrituras fresh de la ruta candidata | **2** | `beginOperation` + fila terminal, **ambos** de `DurableStepExecutor` (`:44`, `:53-68`) |
| Escrituras del frontend | **0** | Nunca escribe. El STOP de «segundo writer» queda evitado por construcción. |
| Escrituras en reuse | **0** | `ReuseCompleted` no escribe (`RecoveryInterpretationEngine.kt:117`) |
| Único conocimiento de status del frontend | la **resolución** | `lastDecision ∈ {Diverged, RecoverRunning, ReuseCompleted, RejectedAbort, Execute}` — ADT cerrada (`CanonicalStructuralDecisions.kt:328-343`). No hay `when (status)`. |

---

## 3. Las cuatro preguntas del encargo

**P1. ¿El `EncodedStepValue` puede recuperarse sin segunda ejecución del handler en la ruta fresh?**
**Sí.** Está disponible en `CommonExecutionResult` desde la frontera
(`RegistryExecutionBoundary.kt:171-178`) y el mismo ejecutor canónico lo persiste
(`DurableStepExecutor.kt:58-64`). K1 lo mide con **una sola** invocación del handler: el valor
devuelto por la frontera y el que el frontend puede entregar son byte-idénticos. Lo que falta no es
el valor sino el **canal**: `executeAndJournal` devuelve `StepOutcome` (`:42`, `:51`, `:70`) y tira
`encodedOutput`. El spike lo sorts con una lectura de la fila que el propio ejecutor acaba de
escribir; la respuesta de producción está en §6.

**P2. ¿Devolver `CommonExecutionResult` desde `DurableStepExecutor` sería útil para el frontend?**
**Sí, pero NO es un cambio sólo del scripted.** Ningún consumidor canónico actual lee el valor
tipado: `ParallelStageEngine.kt:102` lee `it.output?.result` desde el journal y
`decodeBranchTerminal` (`:393-402`) decodifica JSON a mano. Eso es la señal de que **el carrier falta
también en la ruta canónica**. Evaluar, no implementado (§6).

**P3. ¿Existe ya un carrier canónico reutilizable?**
**Sí, y es el correcto.** `CommonExecutionResult` (`CommonExecutionResult.kt:34-37`), con su doc
(`:9-12`) diciendo literalmente que existe «para que el coordinador nunca necesite un segundo
canal». No se crea ningún carrier nuevo.

**P4. ¿`CommonExecutionBoundary` seguiría siendo la única costura de ejecución?**
**Sí.** `CommonExecutionBoundary` (`CommonExecutionBoundary.kt:24-29`) es una `fun interface`; el
`buildDefaultExecutionBoundary` (`:105-109`) enruta por **familia estructural** (`SeamedExecutionRouter`,
`:80-90`), nunca por `StepKey`. Devolver el carrier no crea una segunda frontera: deja de perder
información en la única que ya existe. Y conforme a RPL-4, la materialización (decode con
`outputCodec`) ocurre en el caller, **después** de la decisión — exactamente donde
`ScriptedRegistryInvoker.invokeTyped:171-184` ya la hace.

---

## 4. Inventario: qué habría que tocar para hacerlo real

Clasificación: **ADAPTADOR** = responsabilidad del frontend scripted (dirección + adaptación Kotlin).
**AUTORIDAD** = Spine durable canónico.

### 4.1 ADAPTADOR

| # | Fichero | Cambio | Peso |
|---|---|---|---|
| A1 | `application/scripted/ScriptedRegistryInvoker.kt` **:209-243** | **Único cambio sustantivo.** Sustituir la tabla por `OperationStatus` por: (a) `journal.get` — se **mantiene**, es addressing; (b) resolver `StepMetadata` por `StepKey` vía `RegistryStepMetadataResolver`; (c) `Fingerprint.compute(input, stepId, metadata.replayPolicy, ATTEMPT)` — **elimina el literal `MEMOIZED` de :203-205**; (d) `DurableInvocationResolver.reconcileInvocation(...)`; (e) `RecoveryInterpretationEngine.interpret(...)`. | Sustantivo |
| A2 | `…/ScriptedRegistryInvoker.kt` **:102-115** (ctor) | Nuevas dependencias inyectadas: `stepMetadataResolver`, `invocationResolver`, `recoveryInterpretation`, y `runningSubprocessRecovery` para el caso `ExternalSubprocess`. Todas ya existen como tipos de producción. | Mecánico |
| A3 | `…/ScriptedRegistryInvoker.kt` **:188-202, 317-332, 344-358, 369-370** | **SIN CAMBIOS.** Dirección (`scriptedStepId`, `operationId`), addressing, materialización total (`restoredOutput`), forma de fila, namespace. Se tocan cero. | — |
| A4 | `…/ScriptedRegistryInvoker.kt` **:152-185** (`invokeTyped`) | **SIN CAMBIOS.** Es la última milla y ya es correcta. | — |
| A5 | `…/scripted/ScriptedFrontendRunner.kt` **:47-93** | Componer el resolver y el puerto de recovery. `controlDirRoot` **ya es parámetro** (`:56`) y ya viaja en el `CanonicalRuntimeContext` (`:78`) ⇒ `ExternalSubprocessRecovery(clock, controlDirRoot)` no necesita nada nuevo. | Mecánico |
| A6 | `application/MainScriptedSupport.kt` **:23-69** | Pass-through si la composición vive en el runner. `controlDirRoot` ya está (`:31`, `:52`). | Mecánico |
| A7 | `…/scripted/CompiledScriptedEntryPoint.kt` | **SIN CAMBIOS.** Es superficie de fachada; no enruta ningún Step. | — |

### 4.2 AUTORIDAD

| # | Fichero | Cambio | Peso |
|---|---|---|---|
| B1 | `application/durable/DurableStepExecutor.kt` **:32-71** | Devolver `CommonExecutionResult` en vez de `StepOutcome` (firma `:42`, `return` `:70`). Elimina el estrechamiento de K1/P1. **3 líneas. Evaluado, NO implementado aquí** (prohibido tocar `src/main`). | Mecánico, pero beneficiario *no* exclusivo del scripted |
| B2 | `application/durable/StepDispatchEngine.kt` **:436-447** | Único consumidor canónico de `executeAndJournal`: leer `.outcome` en el caller. La forma de `when` (387-403) se conserva intacta. | Mecánico |
| B3 | `application/durable/ParallelStageEngine.kt` **:102, 393-402** | **Opcional, y es donde está el valor real.** Con B1, el decode manual a mano desaparece y el valor tipado fluye por el carrier. Beneficia a la ruta canónica, no al spike. | Opcional |
| B4 | `durable/DurableInvocationResolver.kt`, `RecoveryInterpretationEngine.kt`, `RegistryExecutionPreparation.kt`, `RegistryExecutionBoundary.kt`, `RunningSubprocessRecovery.kt`, `OperationJournal` | **SIN CAMBIOS.** Ya son la autoridad completa. No se amplía `InvocationReconciliation.ReuseCompleted`: `DurableInvocationResolver` responde *«¿qué debe ocurrir?»*, nunca *«¿qué valor Kotlin necesita el frontend?»*. | — |
| B5 | `CoreStepRegistryFactory.kt`, descriptores, `StepRegistry` | **SIN CAMBIOS.** | — |

### 4.3 Lo que explícitamente NO habría que crear

- ❌ **Ningún kernel scripted nuevo.** Ni `ScriptedReplayKernel`, ni `ScriptedDurableExecutor`, ni
  equivalente. Un cuarto protocolo de replay *es* el fallo que este spike descarta.
- ❌ **Ningún `ScriptedReplayProtocol`, `StatusReuseTable`, `RecoverySelector` o `DecideOnStatus`.**
  La decisión es la del resolver; su lectura es la del interpretador.
- ❌ **Ningún journal.** Ni implementación, ni decorador de producción, ni puerto nuevo. El journal
  sigue teniendo un solo escritor por operación.
- ❌ **Ningún registry.** `StepRegistry` y `CoreStepRegistryFactory` siguen siendo el único registro.
- ❌ **Ningún runner.** `ScriptedFrontendRunner` y `CanonicalDurableRunCoordinator` se reusan.
- ❌ **Ningún modelo de artifact identity.** `ScriptedScopeIdentity` / `ScriptedArtifactIdentity` no
  se tocan; la dirección sigue siendo la de producción.
- ❌ **Ningún plugin manager.** La contribución de Steps externos sigue siendo por registro abierto.
- ❌ **Ningún branch engine.** `BranchInvoker` / `BodyInvoker` no se tocan.
- ❌ **Ningún carrier nuevo.** Ni `ScriptedDurableResult`, ni `DurableTypedResult`, ni
  `InvocationValueResult`. El carrier canónico es `CommonExecutionResult` y ya existe.
- ❌ **Ninguna ampliación de `InvocationReconciliation.ReuseCompleted`.** Prohibida explícitamente.
- ❌ **Ninguna regexp de fitness que prohíba `journal.get`.** Sería un error de diseño (§0).
- ❌ **Ninguna unificación del namespace `"scripted."`.** Está defendido por fitness.

---

## 5. La pregunta decisiva para el siguiente spike

> **¿El seam mínimo es que el resolver devuelva el `journaled` junto a la decisión, o puede el caller
> obtenerlo antes sin acoplar el resolver?**

### Respuesta: el caller YA lo obtiene antes. No hay que tocar la firma del resolver.

El `journaled` es un **parámetro de entrada** de `reconcileInvocation`:

```kotlin
// DurableInvocationResolver.kt:77-82
internal fun reconcileInvocation(
    metadata: StepMetadata,
    journaled: DurableOperation?,      // ← YA LO TIENE EL CALLER
    currentOperation: RerunOperation,
    operationId: String,
): InvocationReconciliation
```

Y el caller lo lee **antes** de llamar, tal como ya lo hace la ruta canónica
(`StepDispatchEngine.kt:374` → `journal.get(operationId, 1)`, consumido en `:387-403`). Además
`RecoveryInterpretationEngine.Request` ya transporta `fingerprint` e `input` (`:57-64`).

**Medido end-to-end (K2), no argumento:** con `decision=ReuseCompleted` y el `journaled` que el caller
ya tenía en la mano — sin que el resolver devuelva nada, sin un segundo `journal.get` para la
decisión y sin segunda ejecución del handler — el frontend recupera el valor Kotlin tipado, con
`invocations=0`, `capReads=0`, `journalWrites=0`.

**Por qué ampliar `ReuseCompleted` a `ReuseCompleted(encodedOutput)` sería un error:**

1. `DurableInvocationResolver` es la **decisión pura** (`DurableInvocationResolver.kt:74-75`: «No
   journal, cursor, event or executor is touched here»). Cargar el output en la resolución mezcla
   «qué debe ocurrir» con «qué valor necesita el caller» — viola RPL-4 y destruye la propiedad que
   hoy hace testeable esa clase a HF0.
2. `ReuseCompleted` es un `data object` porque las dos resoluciones que portan payload llevan cada una
   el suyo. Un output en la rama de reuse no es un caso nuevo: es un **segundo canal**, que es
   exactamente lo que `CommonExecutionResult.kt:9-12` dice querer evitar.
3. El output vive en `journaled.output`, y `journaled` ya está en la mano del caller. Devolverlo en la
   resolución es un viaje de ida y vuelta.

**El seam mínimo es, por tanto, exactamente el que ya existe:** el caller resuelve dirección →
`journal.get` → decisión → interpretación → y, si la decisión dice `ReuseCompleted`, materializa
desde la referencia que ya poseía, a través del `outputCodec` declarado del Step.

**Una salvedad honesta sobre el camino FRESH (no sobre REUSE).** En `REUSE` el `journaled` en mano
basta. En `FRESH` no hay fila todavía, y el valor vive en `CommonExecutionResult.encodedOutput`, que
`DurableStepExecutor` persiste (`:58-64`) pero no devuelve (`:42`, `:51`, `:70`). El spike lo
resuelve leyendo la fila que el propio ejecutor acaba de escribir — sin segunda ejecución, pero con
un viaje extra al journal. La solución de producción es **B1** (devolver `CommonExecutionResult`),
que es un cambio de **3 líneas en la autoridad** y que, como muestra `ParallelStageEngine.kt:102` y
`:393-402`, le sirve también a la ruta canónica. Ese es el punto donde este spike **recomienda tocar
`src/main`**, y por eso aquí queda evaluado y no implementado.

---

## 6. Reservas y lo que este spike NO demonstró

1. **Reserva R1 — la unificación real toca `src/main`.** Por estar prohibido en este encargo, el cambio
   A1/B1/B2 **no está implementado**: existe una composición test-local que lo demuestra, no un
   cambio de producción. El dictamen es sobre factibilidad, no sobre entrega.
2. **Reserva R2 — K4 no demuestra Reattach.** Sólo el brazo fail-closed `LOST`
   (`RunningSubprocessRecovery.kt:84`). Probar un reattach vivo requiere un subproceso de larga
   duración y un crash inyectado entre spawn y journal write. Es una UAT, no un spike.
3. **Reserva R3 — `http.request` (NEVER + red) no es observable end-to-end.** Declara capabilities
   ausentes por defecto (`HttpRequestStep.kt:240`); la preparación fail-closed lo rechaza. K5 usa
   `core.error`. El hecho de que un Step NEVER **externo** tenga la misma semántica es una consecuencia
   estructural de RPL-2, no algo medido aquí.
4. **Reserva R4 — el contador de `recoveryProbes` cuenta invocaciones del puerto, no recuperaciones
   realizadas.** El orden congelado a1/a2.3 consulta el puerto en cada camino no terminal
   (`DurableInvocationResolver.kt:83-89`), y `ExternalSubprocessRecovery.recover` cortocircuita a
   `NotRunningShell` salvo si la política es `ExternalSubprocess` **y** el estado es `RUNNING` **y**
   hay `controlDirRoot` (`:65-67`). K4 es la única fila donde ocurre una recuperación real.
5. **Reserva R5 — `capabilityReads` no es simétrico.** Para el scripted cuenta las construcciones de
   bridge en el invoker (2 en fresh: `ScriptedRegistryInvoker.kt:251-253` y `:264`). Para el
   candidato cuenta las suyas (1), y el bridge interno de `coexecute`
   (`RegistryExecutionBoundary.kt:144`) no se cuenta. En el brazo `REUSE` la cuenta es **exacta en
   ambos casos** — 0 — porque el punto de construcción es inalcanzable, no porque no se contourne.
6. **Reserva R6 — sin CI remota.** `754ddda0` retiró los workflows (ver política de verificación en
   `AGENTS.md`). La evidencia de este spike es la ejecución local sobre el SHA exacto
   `fd7e45a7`, con `EXIT=0`, `tests=8 failures=0 errors=0 skipped=0`. No es un `STEP-CERT` ni un
   `PRODUCT-GATE`.
7. **Reserva R7 — la matriz de `S4-R-POL` no se re-ejecutó aquí.** Las filas de comparación con el
   surface canónico provienen de la misma clase de producción, pero el spike previo no se volvió a
   correr; su recibo es evidencia de **su** SHA.
8. **Reserva R8 — el fingerprint completo difiere entre superficies, y debe.** Medido y afirmado en
   K6 (§2). Si alguien lo lee como divergencia semántica, la lectura es incorrecta: el eje que la
   reconciliación consulta es la política, y coincide.

---

## 7. Clasificación final

# FEASIBLE CON RESERVAS

**Factible** porque, medido y no razonado:

- Las cuatro piezas canónicas **ya existen**, son alcanzables desde el mismo módulo, y componEN sin
  cambio de contrato.
- **Ninguno de los STOP 1..10 se activa.** En particular, el STOP de «segundo writer del journal» no
  se activa: con A1, las escrituras las hacen exclusivamente `DurableStepExecutor` (`:44`, `:53-68`)
  y `RecoveryInterpretationEngine` (`:99-108`) — medido en K7, escrituras del frontend = **0**.
- K1..K7 pasan sobre el SHA exacto, con `src/main` intacto.
- El seam mínimo (§5) **no requiere ninguna firma nueva**: el resolver no devuelve el `journaled` y
  no debe hacerlo.
- El STOP de «decode ad-hoc» no se activa: el materializar va por `StepCodec`/`outputCodec` del Step
  (`RegistryExecutionBoundary.kt:171-178`, `ScriptedRegistryInvoker.kt:171-184`), nunca por JSON a
  mano.

**Con reservas** porque:

1. La unificación real toca `src/main` (A1, B1, B2) y aquí está fuera de alcance por mandato —
   existe una composición que lo demuestra, no el cambio (R1).
2. El camino `FRESH` necesita B1 para que el valor tipado llegue al frontend por el carrier y no por
   una lectura extra al journal (§5). B1 favorece **también** a la ruta canónica (§3, P2).
3. K4 no demuestra reattach (R2); `http.request` no es observable end-to-end (R3).
4. La evidencia es local y de spike, no un `STEP-CERT` (R6, R7).

**Recomendación de fitness (no aplicada aquí):** la inviolabilidad se defiende sobre el **comportamiento
observable**, no sobre una lista de clases a prohibir. Concretamente, y en la línea del STOP ya
corregido: lo que un fitness de `ScriptedRegistryInvoker` debe proscribir es la **tabla de decisión por
`OperationStatus`** (patrón `when (…status…)` que devuelva una `ScriptedRegistryResult`), y la
aparición de un **literal de `ReplayPolicy`** en el fuente. Lo que **no** debe proscribirse es
`journal.get` — abolirlo sería abolir el direccionamiento, que es responsabilidad legítima del
frontend — ni el prefijo `"scripted."`, ya defendido por el fitness existente
(`ScriptedRegistryInvoker.kt:369-370`).

---

## 8. Trazabilidad

| Artefacto | Ruta |
|---|---|
| Este dictamen | `docs/v2/08-spikes/SPIKE-017B-S4-R-KERNEL.md` |
| Esqueleto del spike | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/spike/S4RKernelSpikeTest.kt` |
| Spike previo (base reutilizada) | `…/spike/S4RPolReplaySemanticsSpikeTest.kt` |
| Memo de origen | `docs/v2/07-uat/S4_R0_REPLAY_IDENTITY_CONFLICT_MEMO.md` |
| Segunda autoridad medida | `…/application/scripted/ScriptedRegistryInvoker.kt:187-294` |
| Autoridad canónica | `…/durable/DurableInvocationResolver.kt:77-90`, `…/RecoveryInterpretationEngine.kt:81-134` |
| Carrier canónico | `…/durable/CommonExecutionResult.kt:34-37` |
| Logs de ejecución | `/var/home/rubentxu/.local/state/pipelinek-gates/s4rkernel-spike-run{1,2,3,4}.log` |
