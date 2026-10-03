# S4-R-REC — Recovery obligatorio pero inobservable: lo que el sustrato no observado hace con la fila

**Estado:** `MEASURED` — hipótesis de diseño **sostenida**, no implementada
**Spike:** `S4RRecIndeterminateEffectSpikeTest` (9 tests, 7 filas discriminantes + 1 fila de colapso + 1 tabla)
**Producción tocada:** ninguna. Cero líneas de `src/main` en el estado entregado.
**Base:** `aa219e5b96ad1feeff4eacd9c03fd8633508fddc`, rama `s4-a1b-scripted-shell-spine`
**Alcance:** medición. **No** cierra el STOP de seguridad ni habilita R1-E por sí sola.

---

## 0. Veredicto en una línea

`ExternalSubprocess + RUNNING + controlDirRoot == null` **no cae al replay kernel por una
razón de diseño; cae porque el port no tiene forma de decir lo que pasó.** Y la fila `RUNNING`
que describe no se terminaliza: se reejecuta y se convierte en `SUCCEEDED`, de modo que un
efecto externo de estado desconocido queda **indistinguible** de un éxito nuevo.

---

## 1. El defecto, exacto

`RunningSubprocessRecovery.kt:65` — una sola línea con tres hechos distintos:

```kotlin
if (recoveryPolicy != RecoveryPolicy.ExternalSubprocess
    || journaled?.status != OperationStatus.RUNNING
    || controlDirRoot == null) {
    return RunningCanonicalShellRecovery.NotRunningShell
}
```

| # | Hecho | ¿Legítimo caer al replay kernel? |
|---|---|---|
| 1 | recovery no aplica (política ≠ `ExternalSubprocess`) | **Sí** — semántica genérica |
| 2 | la fila no está `RUNNING` | **Sí** — no hay nada que reenganchar |
| 3 | recovery **SÍ** aplica y el sustrato **no** es observable | **No** — es "me exigen recuperar y no puedo mirar" |

El KDoc del port (`RunningSubprocessRecovery.kt:36-38`) **consagra** el colapso:

> *"Returns `NotRunningShell` when there is nothing to recover — including when … no control
> root is configured. The decision core must not have to know which of those applied."*

Eso convierte una limitación del adaptador en principio arquitectónico, y traslada la decisión
del **cuándo** desde el componente cuyo trabajo es decidir.

### 1.1 El resolver documenta una precondición que no aplica

`DurableInvocationResolver.kt:68` — el resolver afirma:

> *"it triggers only when the operation declares `ExternalSubprocess` **AND** the journal is
> RUNNING **AND** a control dir exists"*

La tercera conjunción la comprueba el **adaptador**, y su fallo se señala con el mismo valor
que el fallo de la primera. El resolver no puede honrar su propia precondición documentada: no
tiene forma de distinguir "no aplica" de "aplica y no puedo observar".

**La precondición documentada y la precondición implementada son reglas distintas.**

---

## 2. La distinción que NO se debe perder

```text
controlDirRoot == null                               →  NO OBSERVADO NADA        (fila 3)
controlDirRoot presente, <root>/<operationId> ausente →  OBSERVADO, nada allí     (fila 4)
```

La fila 4 **ya** es correcta y hoy ya produce `LOST`: `StepReconcilerL1.classifyControlDir`
cae en su Check 3 (log ausente o rancio) y devuelve `Classification.Lost`. Ahí se buscó y no
había evidencia recuperable; `LOST` es el terminal honesto.

La fila 3 es lo contrario: **nunca se llegó a mirar.** Convertirla en `LOST` terminalizaría una
fila que una ejecución posterior, bien configurada, todavía podría reconciliar. "No puedo
observar el proceso" ≠ "el proceso está perdido".

---

## 3. La matriz medida

Salida literal de `S4RRecIndeterminateEffectSpikeTest` (stdout del test, run `4b7521ac`):

```text
S4-R-REC measured matrix:
  row 1  no journal                                     Execute                      handler runs  -
  row 2  None + RUNNING                                 Execute                      handler runs  recovery genuinely not applicable
  row 3  ExternalSubprocess + RUNNING + root null       Execute                      handler runs  REQUIRED BUT UNOBSERVABLE — the defect
  row 4  ExternalSubprocess + RUNNING + op dir absent   RecoverRunning(LOST)         no handler    observed, no evidence
  row 5  ExternalSubprocess + RUNNING + result.txt      RecoverRunning(SUCCEEDED)    no handler    observed, terminal evidence
  row 6  ExternalSubprocess + RUNNING + fresh heartbeat RecoverRunning via Reattach  no handler    observed, still alive
  row 7  ExternalSubprocess + RUNNING + timeout.flag    RecoverRunning(FAILED_TIMEOUT) no handler  observed, watchdog killed it
```

**Una sola fila es el defecto**, y es la única en la que nobody miró.

---

## 4. Qué cruza el harness, y qué no

Cada fila conductual conduce la **columna vertebral productiva real**:

```text
CanonicalDurableRunCoordinator
  → DurableInvocationResolver.reconcileInvocation      (deterministicGate → recoverRunningShell → replayResolution)
    → ExternalSubprocessRecovery.recover
      → StepReconcilerL1.classify / classifyControlDir
    → buildDefaultExecutionBoundary → registry → StepHandler
```

- El `StepDefinition` del probe es **nativo del registry** y su `StepDescriptor` declara los
  `effects` / `replayPolicy` / `recoveryPolicy` reales. El metadata llega al resolver por
  `RegistryStepMetadataResolver.composite(stepRegistry)`, la misma ruta que un plugin.
- **Nada del algoritmo está reimplementado en el test.** El único instrumento es un contador en
  el handler del probe, que es lo más cercano al process launcher que este rig tiene.
- La fila durable previa se **clona de lo que producción escribió** (id, fingerprint, input,
  attempt) y sólo se le cambia el STATUS. Reconstruir el fingerprint aquí probaría la aritmética
  del propio spike en vez de la identidad de producción.
- `run()` se invoca dos veces sobre el **mismo** coordinador y el **mismo** journal: así queda
  modelado un reanudar tras reinicio.

**Sin assertions temporales en ninguna fila.** La fila 6 se mide en la CLASIFICACIÓN (`Reattach`),
no esperando los 60 s del poll de reattach: esperar un minuto para probar "no hay segundo launch"
sería una assertion temporal disfrazada de conductual.

---

## 5. Mutaciones discriminantes

Un spike que sólo afirma el comportamiento actual es una tautología. Estas dos mutaciones son
lo que lo convierte en evidencia: cada una ataca **una** de las dos decisiones de diseño en
disputa y debe producir un RED **de purpose**.

### M-REC-1 — hacer fail-closed lo inobservable (la dirección del arreglo)

Se separa el tercer hecho del sentinel: `controlDirRoot == null` con política y estado que sí
exigen recovery devuelve un `Recovered(FAILURE, FAILED)` en vez de `NotRunningShell`.

```text
log      s4rrec-mut1.log
sha256   bff99f489c66392c548b0fe6225c859fd60902cf60045d0539854fdd4c3aaa36
EXIT=1   9 tests, 2 failures
```

| fila | resultado | por qué |
|---|---|---|
| **3** | **RED** | ya no reejecuta → el contador de handler no sube y la fila no queda `SUCCEEDED` |
| **3b** | **RED** | lo inobservable ya no **es** el mismo valor que lo no aplicable |
| 1, 2, 4, 5, 6, 7 | verde | la mutación está condicionada a `controlDirRoot == null` |

Testigos: **2**, atribución 1:1 sobre las dos que la mutación toca.

### M-REC-2 — lumping de "op dir ausente" con "inobservable"

La tentación con cualquier concepto de "unavailable" es agruparlo con la fila 3. Esta mutación
lo hace, y la fila 4 debe detectarlo.

```text
log      s4rrec-mut2.log
sha256   4c16b6c487cbc77cd49f35253f7b2fc5a3bd6304091cdd0857782b0812d1772f
EXIT=1   9 tests, 1 failure
```

| fila | resultado | por qué |
|---|---|---|
| **4** | **RED** | `LOST` pasa a `FAILED`; dejamos de observar para terminalizar |
| 3 | verde | la mutación **no** toca la fila 3 |
| resto | verde | inafectada |

Testigos: **1**, atribución 1:1.

### Lo que las dos mutaciones demuestran juntas

- Arreglar la fila 3 **no** arregla la fila 4, y lumping la 4 en la 3 es una regresión
  **detectable por separado**. Son dos hechos distintos y el harness los mantiene distintos.
- Las dos filas son el par de control entre sí.

### Restauración

```text
sha256 original  5906f17bd7162a16443fba08e968a7979fb69bb612c71ca2b181cc85ea808245
sha256sum -c     La suma coincide        (tras M-REC-1 y tras M-REC-2)
git diff         vacío
run post         s4rrec-spike-rerun.log  sha256 0eacf90e3dab8c39cda72bf6c01b38dfc8b27960194759c92df6e96750f3adf6  9/0/0/0
```

---

## 6. Evidencia completa

### 6.1 Gate de bloque limpio (previo, sobre el árbol sin el spike)

```text
cmd      cd v2 && ./gradlew :pipeline-step-sdk:runtime:test :pipeline-domain:test \
                        :pipeline-application:test :pipeline-architecture-tests:test --rerun-tasks
log      s4bac-block2.log
sha256   945a01a2ad6546252590f16f61e8f5b97704ada259c32258be2ac3e9e1e73163
EXIT=0   BUILD SUCCESSFUL in 32m 5s
         197 tareas · 0 líneas "^e: "
```

| módulo | clases | tests | fallos | errores | skips |
|---|---|---|---|---|---|
| `pipeline-step-sdk/runtime` | 20 | 201 | 0 | 0 | 0 |
| `pipeline-domain` | 133 | 687 | 0 | 0 | 0 |
| `pipeline-architecture-tests` | 87 | 432 | 0 | 0 | 10 |
| `pipeline-application` | 297 | 2231 | 0 | 0 | 121 |
| **TOTAL** | | **3551** | **0** | **0** | **131** |

Los 2 fallos del gate anterior (`HttpInstalledUatTest > H8-10` y `WalkParallelFrameConcurrencyTest`)
quedan resueltos: el segundo por HAR-PAR-001 (`3f6a80a6`), el primero por ser ruido de carga
(aislada verde).

> **Nota de perímetro:** este gate **no** incluye el spike. El fichero fuente del spike se
> escribió a las 00:37, después de que `:pipeline-application:testClasses` hubiera compilado a
> las 00:23. El gate certifica por tanto el árbol comiteado en `aa219e5b`, que es exactamente
> lo que se quería.

### 6.2 Corridas del spike

| run | log | sha256 | EXIT | resultado |
|---|---|---|---|---|
| baseline | `s4rrec-spike.log` | `e4a0dd63cb6bf1aaa00fefd763c36b98937f11d2c28d73103e963401e9025901` | 0 | 9/0/0/0 |
| post-restauración | `s4rrec-spike-rerun.log` | `0eacf90e3dab8c39cda72bf6c01b38dfc8b27960194759c92df6e96750f3adf6` | 0 | 9/0/0/0 |
| final (post-renombrado) | `s4rrec-spike-final.log` | `4b7521accac4d26d9adbabb6c93c33736ae52674f9e86e0dc7848ef96a18629f` | 0 | 9/0/0/0 |

---

## 7. Disciplina de proceso: el tercer testigo del compilador, otra vez

La primera versión del spike se escribió **a ciegas durante el gate** que ocupaba el checkout,
por la instrucción de no editar fuentes mientras se certifica un árbol. No se compiló. Al
lanzarlo tras el gate:

```text
BUILD FAILED in 3s
38 líneas "^e: "
```

Paquetes equivocados (`Effect`, `StepContract`), `rig.pipeline()` sobre un `val`, un
`copy()` sobre una interfaz, un constructor de coordinator con parámetros inexistentes. El
tercero de esta serie: si `compileTestKotlin` falla, Gradle ejecuta la clase vieja compilada y
reporta un PASS falso. **Siempre leer `^e: `.**

El arreglo fue leer la API real —`A4_1DescriptorRecoveryCharacterizationTest` para el patrón de
`StepDefinition`, `S4RKernelSpikeTest` para el patrón de `CanonicalDurableRunCoordinator`,
`seedKernelPrior` para el clonado de fila— y reescribir contra ella. Segunda lección del
mismo episodio: `--rerun-tasks` sobre `:pipeline-application:test` dispara los 2231 tests del
módulo; para una clase filtrada no hace falta y cuesta 28 minutos.

---

## 8. Lo que este spike NO dice

- **No dice que el arreglo sea trivial.** Dice que la fila 3 es el defecto y que las filas 4-7
  deben sobrevivir intactas.
- **No cierra el STOP de seguridad.** `ExternalSubprocess + RUNNING + recovery no observable`
  sigue pudiendo alcanzar `Execute`. Lo que este spike hace es **acotar exactamente** el camino
  y demostrar que el arreglo será observable cuando llegue.
- **No implementa la hipótesis.** No se añadió `Unavailable`, ni se tocó `RunningCanonicalShellRecovery`,
  ni se cambió ningún ADT. `src/main` está byte-idéntico a `aa219e5b`.

### 8.1 Mutación pre-registrada para R1-E

Cuando R1-E introduzca el caso `Unavailable` en el port del observador, queda **pre-registrada**
esta mutación, con sus testigos esperados:

```text
M-REC-3   colapsar Unavailable en NotRunningShell
esperado  row 3  RED  (handler count 0, fila sigue RUNNING, no terminalizada)
          row 3b RED  (los dos hechos vuelven a ser el mismo valor)
```

Si esa mutación no produce ambos RED, el arreglo de R1-E **no** ha convertido el carrier en el
punto de la decisión y la fila 3 seguiría siendo indistinguible de la fila 2.

---

## 9. Decisión que el spike deja abierta para R1-E

La arquitectura objetivo que este spike **sostiene** (no refuta):

```text
DurableInvocationResolver      decide CUÁNDO recovery es obligatorio
  └─ RunningSubprocessRecovery observa QUÉ encuentra en el sustrato
       RecoveredSuccess | RecoveredFailure | TimedOut | Lost | Unavailable
```

- `NotApplicable` **desaparece del adaptador** o queda exclusivamente en el resolver, que es
  quien puede ver la política.
- `Unavailable` significa literalmente *"recovery era obligatorio pero no tengo sustrato
  suficiente para decidir"*, y produce reconciliación **fail-closed** con handler = 0.
- `Unavailable` **NO** se convierte en `LOST`. Terminalizar la fila destruiría información que
  una ejecución posterior bien configurada podría reconciliar.
- La autoridad **decide; no ejecuta**: no lanza procesos, no lee codecs, no crea capabilities,
  no escribe eventos.

Las cinco responsabilidades siguen separadas: construcción de identidad/fingerprint ·
reconciliación durable pura · observación de recovery · ejecución/admisión · restauración tipada
de output. El spike no propone un objeto que las cinco haga.

---

## 10. Ficheros

| Fichero | Papel |
|---|---|
| `v2/pipeline-application/src/test/kotlin/…/spike/S4RRecIndeterminateEffectSpikeTest.kt` | el spike (nuevo, test-only) |
| `v2/pipeline-application/src/main/kotlin/…/durable/RunningSubprocessRecovery.kt` | **sin cambios** — línea 65 es el defecto medido |
| `v2/pipeline-application/src/main/kotlin/…/durable/DurableInvocationResolver.kt` | **sin cambios** — línea 68 es la precondición no aplicada |
| `v2/pipeline-step-sdk/runtime/src/main/kotlin/…/StepReconcilerL1.kt` | **sin cambios** — la autoridad que ya distingue LOST de Reattach |
