# ADR-S4-R1 — Una única autoridad durable de reconciliación

**Estado:** `PROPOSED` — decisión de ownership pendiente
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Sustituye a:** el alcance de replay que `ADR-0103` dejó explícitamente fuera (§ Out of scope)
**Une:** el spike `S4-R-REC` y el epoch `R1-E`, que son la misma decisión arquitectónica
**Base de código:** `cdcf68c6867b567da427c542e88def6a30cca787`
**Cero cambios de código en este documento**

---

## 0. La decisión

> **Existe una única autoridad durable de reconciliación. Dados el estado persistido,
> `ReplayPolicy`, `RecoveryPolicy`, los efectos declarados, el fingerprint y —cuando la estrategia
> lo requiera— la observación de recovery, decide uno de:
> `Execute · Reuse · Recover · FailClosed · Diverged · Abort`.**

Eso **no** significa una única clase haciendo todo. Significa **un único lugar donde los hechos
adquieren significado de reconciliación**.

```text
durable facts + metadata + recovery observation
                        ↓
        RECONCILIATION AUTHORITY   ← decisión pura
                        ↓
   Execute · Reuse · Recover · FailClosed · Diverged · Abort
```

### 0.1 La propiedad que este ADR fija

> **El observer establece hechos. La autoridad les da significado. El intérprete materializa la
> decisión.**

Que en este repositorio ya se cumple en un caso concreto: la autoridad canónica es
`DurableInvocationResolver`; el observador es `RunningSubprocessRecovery`; el intérprete es
`RecoveryInterpretationEngine`. Los tres existen. Lo que este ADR hace es **declarar** ese reparto,
prohibir que se rompa, y cerrar los huecos que la medición encontró.

---

## 1. Prohibiciones — la autoridad NO puede

Cada punto es un límite estructural, no una preferencia de estilo. Los siete están medidos: cada
uno corresponde a un componente que hoy posee la capacidad y no debería.

```text
1. leer el sistema de ficheros
2. inspeccionar procesos
3. lanzar procesos
4. escribir en el journal
5. emitir eventos
6. hacer admission de capabilities
7. decodificar outputs de Step
```

La razón de fondo es que las siete cosas son **efectos**, y un componente puro no produce efectos.
Un componente puro se prueba en HF0 sin fichero, sin reloj y sin proceso; ése es el valor de la
separación, y es lo que permite que scripted y canónico compartan la misma decisión sin compartir
infraestructura.

### 1.1 Responsabilidades que permanecen separadas

```text
identity / fingerprint construction
durable reconciliation authority        ← ESTE ADR
recovery observation
execution / admission
persistence
typed output restoration / projection
```

Ninguna de las cinco restantes se implementa «dentro de» la autoridad. Y la autoridad no se
implementa «dentro de» ninguna de las otras. Una clase que hiciera las cinco sería peor que el
estado actual, porque devolvería el sistema a una autoridad única *de facto* que no es la única
por diseño.

---

## 2. `observation ≠ decision ≠ interpretation`

Tres capas, tres vocabularios, y una regla de dirección entre ellos.

```text
RunningSubprocessRecovery          hechos; sin StepOutcome, sin OperationStatus
  RunningSubprocessObservation
        ↓
DurableInvocationResolver         significado; decisión pura
  InvocationReconciliation
        ↓
RecoveryInterpretationEngine      materialización
  proyección + journal + eventos + cursor
```

### 2.1 El observer no puede contestar «¿te aplica?»

`RunningSubprocessRecovery` es un `fun interface` cuyo único método es
`observe(operationId: String)`. **No recibe `RecoveryPolicy` ni la fila journaled.**

Ese es el arreglo, y ya está en producción. La primera forma del port tomaba la política declarada y
la fila, y devolvía un único sentinel `NotRunningShell` para tres hechos sin relación:

```text
(1) el recovery NO aplica        — la política declarada no es ExternalSubprocess
(2) la fila no está RUNNING      — no hay nada a lo que reatacharse
(3) el recovery SÍ aplica y el
    sustrato NO es observable     — no hay control root configurado
```

(1) y (2) legítimamente caen a la política de replay. **(3) no.** No es «no hay nada que
recuperar», es «me exigen recuperar y no puedo mirar», y la medición del spike cerró el
consecuencia de extremo a extremo: para un Step que declara `RERUN`, el caso (3) alcanzaba
`Execute`, re-ejecutaba un subprocess cuyo efecto externo previo se desconocía, y después
terminalizaba la fila como `SUCCEEDED` — de modo que el efecto desconocido se volvía
indistinguible de un éxito fresco.

Un observer al que se le puede preguntar «¿te aplica?» vuelve a participar en la decisión de
**cuándo** hay recovery. Esa decisión pertenece exclusivamente a la autoridad. Por eso el puerto no
tiene ese caso: preguntar sería devolverle el poder.

### 2.2 Tres estados epistemológicos distintos

El arrangement correcto separa el que no hay nada, del que no se pudo mirar, **y del que se miró y
no apareció nada dentro de la ventana**:

| observación | qué afirma | es terminal durable |
|---|---|---|
| `Lost` | el sustrato aportó evidencia compatible con pérdida | sí |
| `Unavailable(cause)` | **no** existe sustrato suficiente para obtener conclusión | **no** — fail-closed, la fila queda `RUNNING` |
| `ReattachWindowExpired` | el proceso seguía siendo reatachable y no apareció terminal durante nuestra ventana de observación | ver §2.3 |

`Unavailable` **nunca** se convierte en `LOST`. `LOST` dice «miré y no hay nada recuperable»;
`Unavailable` dice «me exigieron mirar y no pude». Convertir el segundo en el primero terminalizaría
una fila que una ejecución posterior bien configurada todavía podría reconciliar, y destruiría la
única evidencia de que la operación sigue en vuelo.

`Lost` y `ReattachWindowExpired` están igualmente separados porque son afirmaciones distintas: el
primero habla del sustrato, el segundo habla de nuestra ventana de observación. Confundirlos fue lo
que hizo que un proceso vivo se reportara como perdido.

### 2.3 `ReattachWindowExpired` — política de compatibilidad, no Mentira en la observación

El observer **no** puede decir `LOST` cuando lo que ocurrió es que se agotó la ventana: eso sería
interpretación dentro de la capa de hechos. Y el observer **no** puede inventar un terminal que no
tiene.

```text
OBSERVER
  ReattachWindowExpired        ← hecho, honesto, sin status

CURRENT COMPATIBILITY
  ReattachWindowExpired  ->  Recover(RecoveredTerminal.Lost(...))

DEFERRED
  ReattachWindowExpired  ->  reconciliación NO terminal:
                             fila RUNNING, sin Execute, sin inventar terminal
```

La fila de compatibilidad conserva el comportamiento certificado byte a byte. La fila diferida es
una **decisión**, no un defecto, y pertenece a su propio punto de decisión.

### 2.4 `RecoveredTerminal` — el terminal semántico, no el de almacenamiento

La autoridad pura produce un terminal **semántico cerrado**, no un `OperationStatus`:

```kotlin
sealed interface RecoveredTerminal {
    data object Succeeded
    data class Failed(val failure: PipelineFailure)
    data class TimedOut(val failure: PipelineFailure)
    data class Lost(val failure: PipelineFailure)
}
```

y el intérprete hace la única proyección mecánica:

```text
Succeeded -> OperationStatus.SUCCEEDED
Failed    -> OperationStatus.FAILED
TimedOut  -> OperationStatus.FAILED_TIMEOUT
Lost      -> OperationStatus.LOST
```

El intérprete **no vuelve a clasificar observaciones**. Recibe una decisión ya cerrada y sólo sabe
cómo persistirla. Si el intérprete recibiera `Completed(exitCode)` / `TimedOut` / `Lost` y decidiera
de ahí el terminal, habría recuperado una segunda autoridad semántica fuera de la autoridad — que
es exactamente el defecto que este ADR cierra.

### 2.5 Sin `RecoveryPolicy.Unavailable`

`RecoveryPolicy` expresa **qué estrategia se declara** (`None`, `ExternalSubprocess`, futuras
estrategias reales). `RecoveryObservation.Unavailable` expresa el **resultado epistemológico** de
intentar observar una estrategia que sí era necesaria.

```text
RecoveryPolicy.None                 -> la autoridad NO solicita recovery
RecoveryPolicy.ExternalSubprocess   -> la autoridad EXIGE observación
Observation.Unavailable             -> se intentó observar y no hay sustrato suficiente
```

El último caso termina `FailClosed`, nunca `Execute`. Introducir un `RecoveryPolicy.Unavailable`
habría creado una colisión entre dos cosas que no son comparables: una política declarada y un
resultado de observación. La razón está en el código, que ya la separa: la autoridad calcula el
requisito con `recoveryPolicy == ExternalSubprocess && journaled?.status == RUNNING`, es decir
consulta la **política**; el observer sólo recibe `operationId`.

---

## 3. La autoridad, en el estado medido

`DurableInvocationResolver.reconcileInvocation` devuelve un ADT cerrado de seis casos, consumido por
las **dos** superficies:

| caso | significado | efectos |
|---|---|---|
| `Execute` | no es un caso de recovery: es su ausencia | el llamador procede al executor |
| `ReuseCompleted` | el resultado journaled es reutilizable | SIN evento de ciclo de vida, SIN escritura de journal |
| `RecoverRunning(terminal)` | una shell RUNNING se recuperó a un terminal sin re-invocarla | escribe el terminal, avanza el cursor |
| `FailClosed` | recovery obligatorio y sustrato inobservable | **efecto nulo**, fila `RUNNING` intacta |
| `Diverged` | divergencia de fingerprint | fallo tipado, sin evento: el Step nunca empezó |
| `RejectedAbort` | el historial no es reutilizable bajo su política | fallo tipado alrededor del ciclo de vida |

`ScriptedRegistryInvoker` ya **delega** en esta autoridad: inyecta `DurableInvocationResolver` y
`RecoveryInterpretationEngine`, resuelve `StepMetadata` real por registry, hashea con
`metadata.replayPolicy` —el literal `MEMOIZED` desapareció— y nunca lee un status para elegir. Eso
es lo que `ADR-0103` R1-E consumió y lo que este ADR convierte en ley.

### 3.1 Por qué `ScriptedRegistryResult` sigue siendo `{Success, Failed}`

El ADT responde «¿la invocación entregó un valor utilizable?», no «¿era inestable?». Ampliarlo con
`Unstable` duplicaría un hecho semántico que el carrier tipado ya posee, y crearía una segunda
autoridad capaz de discrepar. El outcome viaja **al lado** del valor, en `ScriptedTypedResult`, cuyo
constructor es privado y cuya única entrada `from(value)` lo **deriva** con `outcomeOf`.

Esa es la razón de fondo de §2: la autoridad y el observer no participan en clasificar el outcome
del Step, sino en reconciliar una operación durable ya existente. Quien clasifica el outcome es la
boundary canónica, y su regla vive en un único sitio.

---

## 4. Segunda autoridad en el código — declarada, no borrada

`JournaledScriptedOperationRuntime` es una **segunda autoridad durable** que vive en `src/main`:

```text
Fingerprint.compute(input, SCRIPTED_SHELL_STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT)
existing.toReplayResult(...)          tabla de replay propia
ShellInvocationResult.toOperationStatus()   status propio
```

**Alcance medido:** se construye **sólo desde tests** (`ScriptedScopeTest`, 5 sitios), **cero** desde
producción. El runner real compone `DurableInvocationResolver` + `DurableStepExecutor` +
`RecoveryInterpretationEngine` + `RegistryExecutionBoundary`.

Este ADR **no la borra y no la rediseña**, y deja constancia de por qué:

```text
Es "construida pero inalcanzable", no "ausente".
Una afirmación de single authority que no nombre la excepción es incompleta.
```

Cerrarla —borrarla, moverla a test sources, o convertirla en unreachable por construcción— es
trabajo con su propio criterio de salida, y este ADR no se lo apropia.

### 4.1 La adaptación que D2 ya aplicó

El puerto de operación pasó a llevar el outcome, así que esta clase hubo que supplying uno. Se
**reutilizó** `ShellStepOutcomeClassifier.toStepOutcome()`, que es la autoridad única declarada de
`core.sh`, y `replayFailure` devuelve justo `ShellInvocationResult.Failed`, que ese clasificador
mapea a `StepOutcome.Failure`. Ningún camino queda mintiendo y no se crea una segunda
implementación semántica.

**Limitación documentada, no introducida aquí:** `toStepOutcome` no tiene brazo `Unstable`
(`Status` clasifica como `Success`), así que en ese camino legacy un shell UNSTABLE reproducido
sigue leyendo `Success`. Cerrarlo exigiría añadir un brazo `Unstable` al clasificador, que es una
decisión de semántica de `core.sh` y **no** pertenece a este ADR.

---

## 5. DEFERRED — explícitamente fuera, y por qué

Ninguna de estas se resuelve aquí. Se enumeran porque un ADR que dice «single authority» sin decir
qué queda abierto es una promesa que la implementación no puede cumplir.

| # | diferido | por qué no aquí | consequence |
|---|---|---|---|
| **D-1** | `ReattachWindowExpired` → reconciliación no terminal (§2.3) | es semántica de reconciliación nueva, y cambia el resultado observable de un caso certificado | deja la fila `RUNNING` sin terminal inventado |
| **D-2** | terminal semántico durable para `Unstable` | `Unstable` se persiste como `OperationStatus.FAILED`, luego es indistinguible de un fallo real | **un Step `Unstable` es incacheable** y la segunda invocación re-ejecuta |
| **D-3** | `OperationStatus` como dos ejes (estado operacional vs resultado semántico) | cambiarlo toca esquema, serialización, journal, lectores, reconciliadores, matriz de replay, migración | punto de decisión propio |
| **D-4** | `JournaledScriptedOperationRuntime` (§4) | es alcanzable sólo desde tests; su cierre tiene criterio de salida propio | segunda autoridad en el código, declarada |
| **D-5** | identidad de iteración de loops | gobernada por ADR-S4-R2 | ver `ADR-0103` § Out of scope |
| **D-6** | `while` y `for` sin llave | el lowering no tiene `bodyStartOffset`/`bodyEndOffset` para ellos, y `ScriptedLoopScope` puede necesitar otra forma | en ADR-S4-R2 como ley declarada y no resuelta |
| **D-7** | `PLUGIN_LOCK_DIGEST` | sin dueño declarado | reservado por `ADR-0103` |

### 5.1 D-2 en detalle, porque es el hallazgo más caro de este slice

```text
CanonicalStructuralDecisions.kt:299   StepOutcome.Unstable -> OperationStatus.FAILED
EffectReplayPolicy.kt regla 4         «REUSE OF A SUCCEEDED ROW» — SUCCEEDED únicamente
EffectReplayPolicy.kt regla 7         MEMOIZED + READ_ONLY + not SUCCEEDED -> EXECUTE
```

Un Step `Unstable` persiste una fila `FAILED`; ninguna `ReplayPolicy` declarada reutiliza una fila
no-`SUCCEEDED`; el segundo intento re-ejecuta. No es que el reuse pierda el marcador: es que **el
reuse no ocurre**.

D2 reparó la **propagación** —el outcome viaja y se recupera del payload tipado— y dejó la
**conducta de replay** intacta a propósito, porque arreglarla exige una decisión de protocolo
durable. Deliberadamente **no** se introdujo `Unstable → SUCCEEDED` en scripted para conseguir un
reuse: eso restauraría un split-brain donde el frontend scripted y la proyección durable canónica
discrepan sobre el mismo Step declarado, que es precisamente lo que R1-E eliminó.

Opciones para D-2, **ninguna elegida aquí**:

| | opción | superficie |
|---|---|---|
| A | `OperationStatus.UNSTABLE` | esquema, serialización, journal, lectores, reconciliadores, replay matrix, migración, UAT de resume |
| B | estado operacional terminal + `semanticOutcome` separado | dos ejes explícitos; `OperationStatus` deja de cargar el resultado semántico |
| C | restaurar el outcome desde el typed output como **hecho de reconciliación** | la autoridad recibe un hecho nuevo; no cambia el formato |
| D | otra representación algebraica del terminal durable | por estudiar |

Lo que este ADR **sí** establece para D-2 es la restricción: sea cual sea la opción, la autoridad
debe seguir siendo **una**, y el eje semántico no puede decidir replay por su cuenta.

---

## 6. Compatibilidad

Este ADR **no cambia** el formato durable, no bumpea `RUNTIME_COMPATIBILITY_VERSION` y no
reinterpreta historia existente.

La decisión de compatibilidad de R1-E —que una fila `ExternalSubprocess + RUNNING` con sustrato
inobservable falle cerrada y deje la fila `RUNNING`— es comportamiento ya implementado y
certificado. Este ADR lo **declara** correcto; no lo cambia.

Lo que sí es material de future review, y queda anotado para el slice que lo implemente:

- si D-1 se adopta, cambia el resultado observable de un caso certificado;
- si D-2 o D-3 se adoptan, cambian el esquema durable y por tanto la dimensión correcta para
  declararlo es `ScriptedArtifactIdentity.runtimeCompatibilityVersion`, que ya existe.

---

## 7. Consecuencias

### Positivas

- «Single authority» deja de ser una intención y pasa a tener sujeto, límites y lista de prohibiciones.
- La frontera observación/decisión/interpretación queda nombrada, y por tanto defendible.
- Las tres disposiciones de las que dependen cuatro componentes distintos quedan explícitas.
- La segunda autoridad del código queda **declarada**, que es lo mínimo que hace honesta la
  afirmación principal.

### Negativas / coste

- La autoridad pura no puede crecer: cada caso nuevo requiere decidir si es decisión o
  interpretación, y esa decisión es cara.
- `Unavailable` obliga a un camino fail-closed que no puede terminalizar, lo que complica el
  cierre de un run y es correcto sólo mientras la fila siga siendo reconciliable.
- Declarar D-4 sin cerrarla es deuda viva; la alternativa era una afirmación incompleta.

### Neutras

- `RegistryExecutionBoundary` sigue siendo la autoridad de la proyección `typed output →
  StepOutcome`, y D2 la consolidó en `outcomeOf`. Este ADR no la mueve.

---

## 8. Referencias

- `docs/v2/07-uat/S4_R_REC_RECOVERY_OBSERVABILITY_RECEIPT.md` — el spike que midió el colapso
- `docs/v2/07-uat/S4_R1_E_RECOVERY_AND_IDENTITY_EPOCH.md` — el epoch que lo reparó
- `docs/v2/07-uat/S4_D2_SCRIPTED_UNSTABLE_SEMANTICS_RECEIPT.md` — `cdcf68c6`; frontera de `Unstable`
- `docs/v2/04-adrs/ADR-0103-one-replay-authority.md` — reserva el nombre y el scope, y lleva el
  addendum autoritativo a ADR-0093
- `docs/v2/04-adrs/ADR-0066-call-site-identity-determinism.md` — el triple de identidad vigente
- `AGENTS.md` — DR-1..DR-12, DURABLE SCRIPTED REPLAY LAWS
- `v2/pipeline-application/…/durable/RunningSubprocessRecovery.kt` — el observer
- `v2/pipeline-application/…/durable/DurableInvocationResolver.kt` — la autoridad
- `v2/pipeline-application/…/durable/RecoveryInterpretationEngine.kt` — el intérprete
- `v2/pipeline-step-sdk/runtime/…/EffectReplayPolicy.kt:33-39` — la tabla de reglas
