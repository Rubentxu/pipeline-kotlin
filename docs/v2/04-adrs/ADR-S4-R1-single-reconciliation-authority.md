# ADR-S4-R1 — Una única autoridad durable de reconciliación

**Estado:** `ACCEPTED` — decisión de ownership tomada. `implementation conformance: SATISFIED`
(cierre en `4429e4ca`; antes `PARTIAL`, con dos manifestaciones)
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Sustituye a:** el alcance de replay que `ADR-0103` dejó explícitamente fuera (§ Out of scope)
**Une:** el spike `S4-R-REC` y el epoch `R1-E`, que son la misma decisión arquitectónica
**Base de código:** `cdcf68c6867b567da427c542e88def6a30cca787`

> La decisión arquitectónica se toma **antes** del código. `ACCEPTED` afirma que el reparto
> observer · authority · interpreter es el correcto; no afirma que ya esté implementado en todas
> partes. El ADR decide; los recibos de implementación demuestran conformidad.
>
> `conformance: SATISFIED`. Hasta `4429e4ca` leía `PARTIAL`, y no señalaba una lista de tareas
> sueltas: señalaba **dos manifestaciones de la misma ley** — una frontera pierde información antes
> de alcanzar a su autoridad legítima. Las dos están cerradas:
>
> | # | manifestación | estrecho | cerrado en |
> |---|---|---|---|
> | 1 | **execution carrier narrowing** | `CommonExecutionResult` → `StepOutcome` dentro del spine durable | `4429e4ca` (F1-C3, R14) |
> | 2 | **recovery evidence narrowing** | hechos del terminal (`exitCode`, salida observada) → terminal semántico Step-specific, en el observer | `4429e4ca` (F1-C1 + F1-C2) |
>
> La segunda se descubrió **midiendo**, después de que este ADR se aceptara, y es la razón por la que
> `conformance` enumeraba dos filas y no una: el observer clasificaba `exitCode != 0` como
> `Failed(SCRIPT)` sin saber que `sh(returnStatus = true)` con salida 42 es `Status(42) · Success`.
> Un `exitCode` observado **no** es un valor fabricado; perderlo sí es perder información.
>
> Ambos cierres son afirmaciones de comportamiento, así que ambos llevan una mutación que los mata
> (`M-F1-C1`, `M-F1-C2`, `M-F1-C3`) y un fitness que defiende la forma después
> (`RecoveredValueSpineFitnessTest`). La que se ganó su sitio es `M-F1-C3`: **sobrevivió** a los 50
> tests del radio de impacto, porque la matriz de verdad lee el journal y el estrechamiento sólo
> afecta al consumidor. R14 sólo podía certificarse en la frontera del consumidor, y hasta que esa
> fila existió la afirmación estaba implementada y sin probar.

**Este documento ya no hace cero cambios de código.** Se escribió como decisión pura sobre esa base, y
después la implementaron:

| commit | qué satisface |
|---|---|
| `b1540033` · `39e8ff03` | §2.2, §2.3 — la rama de reattach expirada pasa a ser observable y se **mide** el colapso |
| `9966b997` | §0.2, §2.4, §2.6 — el observer da el hecho, la autoridad el significado, el intérprete proyecta |
| `5c021496` | §4 — D-4 **cerrada por eliminación**; 357 líneas de segunda autoridad fuera de `src/main`, cinco sitios de test migrados a la autoridad canónica, y `SingleDurableAuthorityFitnessTest` dejando la ley defendida (§4.2) |
| `4429e4ca` | §2.3, §2.4, §2.7 — **conformance `SATISFIED`**: el observer da hechos, la materialización es del Step, y el carrier llega entero al consumidor |

Evidencia: `S4_R1_3B_REATTACH_WINDOW_EXPIRED_RECEIPT.md`,
`S4_R1_3C_OBSERVER_FACT_AUTHORITY_MEANING_RECEIPT.md`,
`S4_R1_F1B_SINGLE_DURABLE_AUTHORITY_RECEIPT.md` y
`S4_R1_F1C_RECOVERED_VALUE_SPINE_RECEIPT.md`. §0.2, §0.3 y §2.6 se añadieron después, con la
medición ya hecha: el texto normativo ya no se adelanta a la evidencia.

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

### 0.2 Lo que «única autoridad» NO significa

Una autoridad única **no** significa un vocabulario idéntico en todas las capas.

```text
observador    conserva los hechos observados, sin anticipar su interpretación
autoridad     asigna significado y aplica política
intérprete    materializa esa decisión en estado durable y efectos
```

Cada frontera conserva **exactamente la información de la que es responsable**, y ni una más. El
observer no necesita saber cómo se persiste un terminal; el intérprete no necesita saber por qué se
eligió; la autoridad no necesita saber qué había en el disco.

La consecuencia práctica es que una diferencia entre dos caminos puede desaparecer del resultado
observable **sin** que las dos rutas sean equivalentes — véase §2.6. Y la consecuencia de diseño es que
**ninguna** de las tres capas debe ser ensanchada para “coincidir” con las otras.

### 0.3 `fail-closed` es una propiedad, no un caso

Este ADR usa `FailClosed` en la lista de la decisión de §0 y en la tabla de §3. El código llama al
caso concreto `RecoveryUnobservable`. La divergencia es deliberada y son dos niveles distintos:

```text
fail-closed              propiedad arquitectónica: ante un fallo, no se ejecuta de más
                               │
                               └── RecoveryUnobservable   la decisión concreta que hoy la satisface
                                    recovery obligatorio + sustrato inobservable
                                    → no Execute, sin terminalización inventada,
                                      fila RUNNING intacta
```

`fail-closed` **no** necesita existir como caso de producción mientras haya una sola razón concreta.
Si en el futuro aparecen varias decisiones fail-closed distintas, entonces habrá evidencia para
plantear una familia algebraica; nombrarla ahora sería abstracción anticipada. Y mientras siga siendo
una, el nombre del código es el más preciso: `RecoveryUnobservable` dice *el sustrato fue inobservable*,
mientras que un `FailClosed` genérico podría ser cualquier cosa.

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

### 2.3 `ReattachWindowExpired` — política de compatibilidad, no una mentira en la observación

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

### 2.6 Ley de convergencia observable

> **Dos caminos internos pueden converger intencionadamente al mismo resultado durable observable sin
> ser epistemológicamente equivalentes. La igualdad del estado terminal observable no implica la
> igualdad de los hechos que condujeron a él.**

Ésta es la consecuencia directa de §0.2, y es la razón por la que el sistema puede tener una sola
autoridad sin que todas las capas coincidan. La forma general, dentro de este ADR:

```text
observación  ──sin──────────────┐
                               ├──→  misma decisión  ──→  mismo estado durable
observación' ──política────────┘
```

**Consecuencias obligatorias:**

- un test end-to-end sólo debe afirmar propiedades observables **de su nivel**;
- no debe acoplarse a diferencias internas que quedan deliberadamente ocultas por una convergencia
  posterior;
- la discriminación semántica de esas diferencias pertenece a las pruebas de la frontera donde se
  producen o se deciden.

**Evidencia empírica.** La sonda de mutación `M-3c-3` (§ recibo `S4_R1_3C`) midió exactamente esto.
Al mutar el observer para que devolviera `Lost` en vez de `ReattachWindowExpired`:

```text
fila 8/9  (frontera del observer)  →  RED    pérdida epistémica detectada
fila 10  (extremo a extremo)       →  GREEN  convergencia posterior las hace indistinguibles
```

Las dos rutas convergen en la misma política de compatibilidad `RecoveredTerminal.Lost`, así que el
terminal durable resultante es `LOST` en ambas. La fila 10 **no es un test débil**: es un test cuyo
nivel de abstracción está por debajo de la diferencia que ya no existe aguas abajo. Forzarla a
ponerse roja filtraría detalles de la observación hacia una prueba que no debería conocerlos.

**Corolario para el carrier, y la conexión con R14.** Una frontera puede perder información **sólo**
mediante una proyección explícita y justificada, nunca por estrechamiento accidental del carrier. El
mismo error estructural aparece en recovery y en ejecución:

```text
recovery    observación  →  decisión  →  interpretación
execution   resultado tipado  →  transporte durable  →  proyección del consumidor
```

En ambos, el fallo es el mismo: perder información antes de llegar a la autoridad que legítimamente
puede interpretarla. Ése es el error que R14 describe en el spine de ejecución, y es la razón por la
que `ReattachWindowExpired` —un `data object` sin terminal— no es un detalle de implementação sino la
forma que hace la ley observable en el tipo.

### 2.7 Quién materializa el terminal recuperado — la cuarta frontera

Al aceptar este ADR, la medición de F1 encontró una cuarta frontera que el reparto observer ·
authority · interpreter **no cubría**, y que es donde nace la manifestación 2 de `conformance`.

El observer sabe que el proceso terminó con `exitCode = 42`. La autoridad sabe que hay que
recuperar. **Ninguna de las dos sabe qué significa 42 para `core.sh`**, y el resolver **no debe
saberlo**: su pregunta es `Execute · Reuse · Recover · …`, no «¿este shell tuvo éxito?».

```text
  RunningSubprocessRecovery      hechos:  exitCode · stdout observado · timeout · lost · ventana
            ↓
  DurableInvocationResolver      decisión: Recover(hechos)
            ↓
  StepRegistry                   SÍ sabe: qué significa exitCode 42 bajo este contrato
            ↓
  materializador de recovery     contrato Step-owned, sin handler y sin capabilities de ejecución
            ↓
  CommonExecutionResult          el MISMO carrier que produce la ejecución fresh
```

> **La reconciliación decide que hay que recuperar. El Step materializa el resultado recuperado.**

Cuatro consecuencias, y las cuatro son leyes, no sugerencias de implementación:

**1. El materializador no es una quinta autoridad.** Es un **adapter genérico**: resuelve la
definición en el `StepRegistry`, decodifica el input con el `inputCodec` **del Step**, invoca la
proyección de recovery **que el Step posee**, y vuelve a codificar con el `outputCodec` del mismo
`StepContract`. No decide reconciliation, no decide status, no tiene tabla de.exit code. Todo lo que
reutiliza.

**2. La capacidad es aditiva, no una ruptura de `StepContract`.** Se expresa como una interfaz que
una definición *puede* implementar (`RecoveredStepProjection<I, O>`), y el engine **pregunta por la
capacidad**, nunca por `"core.sh"`. Un Step que declara un terminal recuperable y no ofrece
proyección capaz de materializarlo **falla cerrado**. No hay fallback, no hay valor por defecto, no
hay rama que degrade con elegancia.

**3. La proyección recibe el input tipado, no `returnMode`.** Determinar `returnMode` desde
`OperationInput` es responsabilidad del codec del Step, y por eso el seam es limpio: el resolver ya
tiene el input canónico del journal. Añadir `returnMode` al resolver —o un
`if (stepKey == "core.sh")`— sería meter semántica de Step en la autoridad de reconciliación, que es
justo lo que este ADR prohíbe.

**4. La proyección se ejecuta sin capabilities de ejecución ni handler.** Materializar un
resultado recovery **no es ejecutar el Step**: el proceso ya terminó fuera de esta JVM. Por eso la
materialización **no** pasa por la preparación de ejecución, que admitiría capabilities que un
recovery no necesita —la que lanza procesos, por ejemplo—. Sólo definición, codecs y proyección
pura.

**El caso que motivó la ley.** El contrato de `core.sh` ya tiene una autoridad única declarada:

```text
DurableTaskTerminal → classifyShellTerminal(terminal, returnMode)
                    → ShellInvocationResult → ShellStepOutcomeClassifier → StepOutcome
```

y sus únicos llamadores de producción eran **fresh**. Un `exitCode = 42` observado en recovery ya
era, por tanto, toda la evidencia necesaria: `sh(returnStatus = true)` con salida 42 es
`Status(42) · Success`. Clasificarlo en el observer como `Failed(SCRIPT)` **no era fail-closed
correcto**: era perder `returnMode` antes de llegar a la autoridad que sabe interpretarlo.

> Un `exitCode` observado **no** es un valor fabricado. Fabricado sería `0`, `""` o `Unit` cuando los
> hechos **no** lo dicen. La frontera entre las dos cosas es exactamente si el hecho estaba en el
> substrate, y esa es la pregunta que `STDOUT` con `output.txt` ausente vuelve afilada: presente y
> vacío es un hecho; **ausente es ausencia de evidencia**, y ante ausencia de evidencia se falla
> cerrado.

---

## 3. La autoridad, en el estado medido

`DurableInvocationResolver.reconcileInvocation` devuelve un ADT cerrado de seis casos, consumido por
las **dos** superficies:

| caso | significado | efectos |
|---|---|---|
| `Execute` | no es un caso de recovery: es su ausencia | el llamador procede al executor |
| `ReuseCompleted` | el resultado journaled es reutilizable | SIN evento de ciclo de vida, SIN escritura de journal |
| `RecoverRunning(terminal)` | una shell RUNNING se recuperó a un terminal sin re-invocarla | escribe el terminal, avanza el cursor |
| `FailClosed` | recovery obligatorio y sustrato inobservable — en el código, `RecoveryUnobservable` (ver §0.3) | **efecto nulo**, fila `RUNNING` intacta |
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

## 4. Segunda autoridad en el código — CERRADA por eliminación

> **Estado de esta sección: cerrado por `F1-B`.** Se conserva íntegra porque su medición es la
> evidencia de por qué la clase podía eliminarse sin pérdida, y porque un ADR que borra la
> excepción sin conservar el razonamiento deja de poder ser auditado.

`JournaledScriptedOperationRuntime` era una **segunda autoridad durable** que vivía en `src/main`:

```text
Fingerprint.compute(input, SCRIPTED_SHELL_STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT)
existing.toReplayResult(...)          tabla de replay propia
ShellInvocationResult.toOperationStatus()   status propio
```

**Alcance medido:** se construía **sólo desde tests** (`ScriptedScopeTest`, 5 sitios), **cero** desde
producción. El runner real compone `DurableInvocationResolver` + `DurableStepExecutor` +
`RecoveryInterpretationEngine` + `RegistryExecutionBoundary`.

**Desbloqueo medido antes de tocar código (F1-B):** `pipeline-application` **no** publica
(`build.gradle.kts` sin `maven-publish`; sólo lo publican `pipeline-domain` y
`pipeline-scripting-api`), y los cuatro módulos que dependen de él son internos del repositorio. La
clase **no era API/ABI publicada**, luego la regla aplicable era eliminar, no convertir en shim.

**Qué se eliminó** — 357 líneas de `src/main`:

| pieza | líneas | por qué |
|---|---|---|
| `JournaledScriptedOperationRuntime.kt` | 270 | segunda autoridad durable: fingerprint hardcodeado, tabla de replay propia, status propio |
| `DurableScriptedOperationReconciler.kt` | 87 | segunda ruta de recovery scripted |
| puerto `RunningScriptedOperationReconciler` + `ScriptedRunningResolution` | — | quedaban con **cero** consumidores de producción: eran el gancho por el que una segunda autoridad podía volver a enchufarse |

Los cinco sitios de `ScriptedScopeTest` se **migraron** a la autoridad canónica, no se relajaron. El
caso de *procedencia* quedó **más fuerte**: antes leía un lector JSON que existía sólo dentro de la
clase eliminada; ahora lee `durableFailure` del **cable durable**, decodificado por el `outputCodec`
del `StepContract` tomado de `registry.definition(...)`.

**Falsedad que la medición desmintió.** El primer intento fue capar el número de call sites de
`Fingerprint.compute` a ≤3, como si el constructor puro fuera una señal de autoritáa duplicada. Falló con 6, y los
6 son legítimos: es un valor constructor y ninguno **decide**. Un umbral normativo inventado habría
sido un defecto disfrazado de ley. Las leyes reales que sí se impusieron están en
`SingleDurableAuthorityFitnessTest` (§4.2).

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

> Con la clase eliminada, esa limitación deja de ser alcanzable por el camino legacy. La limitación
> de fondo —que `Unstable` no tiene terminal semántico durable propio— sigue viva y **pertenece a
> D-2** (§5), no a D-4.

### 4.2 La ley que F1-B dejó defendida

`SingleDurableAuthorityFitnessTest` (4 tests) no comprueba que una clase no exista por su nombre: eso
será un test dePresence frágil. Comprueba cuatro propiedades que un refactor podría violar sin
borrar nada:

| # | ley | por qué no es un nombre |
|---|---|---|
| 1 | ninguna de las cuatro piezas retiradas se declara en producción | cierra la puerta a reintroducirlas con otro nombre **o** el mismo |
| 2 | sólo la autoridad de reconciliación pide una decisión de replay | `effectReplayPolicy.decide(` aparece en **exactamente 1** fichero productivo; una segunda tabla no podría aparecer sin delatarse |
| 3 | la superficie scripted nunca **lee** un status durable para decidir | en `/application/scripted/`, ninguna forma de comparación de status; sólo se **escribe** `PENDING` |
| 4 | ninguna superficie scripted hardcodea una `ReplayPolicy` | el `MEMOIZED` embebido era la firma de la autoridad duplicada |

Los cuatro leen **código sin comentarios** (helper `codeOnly()` que elimina KDoc y comentarios
respetando literales de cadena). Sin esa stripping, el propio KDoc de la pieza retirada satisfaría
la ley 1 y el fitness no distinguiría nada.

---

## 5. DEFERRED — explícitamente fuera, y por qué

Ninguna de estas se resuelve aquí. Se enumeran porque un ADR que dice «single authority» sin decir
qué queda abierto es una promesa que la implementación no puede cumplir.

| # | diferido | por qué no aquí | consequence |
|---|---|---|---|
| **D-1** | `ReattachWindowExpired` → reconciliación no terminal (§2.3) | es semántica de reconciliación nueva, y cambia el resultado observable de un caso certificado | deja la fila `RUNNING` sin terminal inventado |
| **D-2** | terminal semántico durable para `Unstable` | `Unstable` se persiste como `OperationStatus.FAILED`, luego es indistinguible de un fallo real | **un Step `Unstable` es incacheable** y la segunda invocación re-ejecuta |
| **D-3** | `OperationStatus` como dos ejes (estado operacional vs resultado semántico) | cambiarlo toca esquema, serialización, journal, lectores, reconciliadores, matriz de replay, migración | punto de decisión propio |
| **D-4** | `JournaledScriptedOperationRuntime` (§4) | ~~alcanzable sólo desde tests~~ | **CERRADA en F1-B** por eliminación, no por excepción declarada |
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
