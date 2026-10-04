# R1-E — El observador dice QUÉ encontró; el resolver decide CUÁNDO

**Estado:** `IMPLEMENTED` · un solo epoch atómico
**Decisión rectora:** ADR-0103 RPL-2 / RPL-3 / D6
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Base:** `92151de835dba25b05cc77a5cf7dd0aa58eb638b`
**Alcance:** identidad de replay scripted + autoridad de recovery. **No** toca lowering, restore ni `Unstable`.

---

## 0. Veredicto

`ExternalSubprocess + RUNNING` ya **no puede alcanzar `Execute` sin una observación de recovery
concluyente**, y eso es ahora una propiedad estructural, no una convención. La restricción de
cierre que fijó el owner se cumple, y hay una prueba que la enumera combinatoriamente
`RF_E-3`.

El epoch hizo tres cosas acopladas que no podían separarse sin abrir una ventana:

```text
1  el port de recovery dejó de decidir      → el resolver posee el CUÁNDO
2  Unavailable became un brazo del ADT      → fail-closed que NO terminaliza
3  el fingerprint lee el descriptor         → y su versión de compatibilidad sube en el MISMO corte
```

La tercera es la que obliga a las otras dos a moverse juntas: cambiar la política que entra en el
hash invalida las filas `scripted.*` existentes, y hacerlo sin bumpear
`runtimeCompatibilityVersion` dejaría artefactos reutilizables por un runtime que ya no comparte su
ley de identidad.

---

## 1. El defecto que R1-E cierra, y por qué el Spike lo había medido

`S4-R-REC` (`S4_R_REC_RECOVERY_OBSERVABILITY_RECEIPT.md`) midió que
`ExternalSubprocess + RUNNING + controlDirRoot == null` alcanzaba `Execute`, reejecutaba un
subproceso cuyo efecto externo previo se desconocía, y **terminalizaba la fila como
`SUCCEEDED`**. Eso último es lo que hacía el efecto desconocido indistinguible de un éxito nuevo.

La causa era que el port hacía dos trabajos a la vez: observer y judge.

```kotlin
// ANTES — RunningSubprocessRecovery.kt:65
fun recover(recoveryPolicy: RecoveryPolicy, journaled: DurableOperation?, operationId: String) =
    if (recoveryPolicy != ExternalSubprocess || journaled?.status != RUNNING || controlDirRoot == null)
        NotRunningShell        // ← tres hechos, un sentinel
    else observeAndClassify()
```

El observador recibía la política y la fila, así que podía contestar una pregunta de decisión — y
la contestaba con un valor que no distinguía *"no aplica"* de *"me exigen recuperar y no puedo
mirar"*.

---

## 2. La autoridad, después

```text
DurableInvocationResolver
  ├─ recoveryRequirement(policy, journaled)     → NotApplicable | Required      ← el CUÁNDO
  └─ si Required:
       RunningSubprocessRecovery.observe(opId)  → Recovered | Unavailable        ← el QUÉ
            │
            ├─ Recovered(outcome, status)  → RecoveryRunning        (terminal, con su fila)
            └─ Unavailable(cause)         → RecoveryUnobservable    (fail-closed, SIN fila)
```

Tres detalles que hacen el cambio real y no cosmético:

### 2.1 El port ya no recibe política ni estado

`observe(operationId: String)`. Sin `RecoveryPolicy`, sin `DurableOperation`. No es que ya no los
consulte: es que **no está en posición de hacerlo**. La firma es la prueba estructural de que el
`WHEN` se movió, y por eso `CountingRecovery` del S4-R-KERNEL también tuvo que cambiar — su
firma es un segundo testigo.

### 2.2 `NotApplicable` sólo existe en el resolver

`RecoveryRequirement` se declara en `CanonicalStructuralDecisions.kt`, junto a
`InvocationReconciliation`, porque el resolver es el único que puede ver la política declarada. El
observador no tiene un caso que signifique "no aplica", y esa ausencia es la corrección: el
sentinel era lo que impedía expresar la diferencia.

### 2.3 `Unavailable` es fail-closed **y no terminaliza**

```kotlin
is InvocationReconciliation.RecoveryUnobservable -> RecoveryInterpretation.Settled(
    StepOutcome.Failure(PipelineFailure(FailureKind.INFRASTRUCTURE, "…", ))
)
```

Sin `journal.append`, sin eventos de ciclo de vida, sin avance de cursor. La fila `RUNNING` queda
**exactamente** como estaba.

`LOST` sería el terminal equivocado y por una razón que el Spike ya había separado: `LOST` dice
*"busqué y no hay nada recuperable"*; `Unavailable` dice *"no pude buscar"*. Terminalizar con
`LOST` destruiría la única evidencia que una ejecución posterior bien configurada necesita para
reconciliar. `Unavailable` no se convierte nunca en `LOST`.

---

## 3. RPL-3: el fingerprint, y un KDoc que mentía

`ScriptedRegistryInvoker` hasheaba `ReplayPolicy.MEMOIZED` fijo mientras el dispatcher canónico
hasheaba `metadata.replayPolicy`. Para un Step que declara otra política — `core.sh` declara
`RERUN`, `core.error` declara `NEVER` — las dos superficies persistían **identidades durables
distintas para el mismo Step con el mismo estado de journal**.

Peor: el KDoc de la clase afirmaba, en el momento de R1-A/C, que *"the `ReplayPolicy` literal in
the fingerprint call … are all gone"*. No lo estaba. Una afirmación de KDoc sobre un cambio que el
código no hizo es peor que no tener KDoc: hace que el siguiente lector deje de mirar. El comentario
ahora dice la verdad y explica por qué el literal ya no **puede** volver — la política se lee del
descriptor, así que no queda ningún valor ahí que hardcodear.

El orden cambió para que el hecho se sostenga: resolver metadata **antes** de hashear, no después.

### 3.1 D6: el cambio durable y su declaración

`ReplayPolicy` entra en el payload hasheado (`Fingerprint.compute` → `FingerprintPayload.replayPolicy`),
así que el valor cambia para todo Step que no declare la política por defecto. D6 ya lo gobierna:
las filas `scripted.*` existentes divergen y la puerta de divergencia responde
`REPLAY_COMPATIBILITY`, que es el resultado buscado — incompatibilidad explícita en vez de
reejecución silenciosa de un efecto que se creía fresco.

Ninguna fila se reescribe, rehashea ni migra. `ScriptedArtifactIdentity.runtimeCompatibilityVersion`
sube `r3-runtime-v1` → `r4-runtime-v1` **en el mismo corte**, porque es la dimensión que ya existe
para declarar un cambio de modelo durable; bumpearla aparte dejaría un artefacto pre-cambio
reutilizable por un runtime que ya no comparte su ley.

---

## 4. La restricción de cierre, probada combinatoriamente

El owner condicionó el cierre a que *ningún* camino `ExternalSubprocess + RUNNING` alcance
`Execute` sin observación concluyente. Eso es una afirmación sobre todos los caminos, así que
`S4RecoveryRequiredNeverExecutesTest` la enumera en vez de contarla:

| prueba | qué cubre |
|---|---|
| `no required recovery ever resolves to Execute, whatever the observer says` | 4 respuestas del observador × 3 replay policies × todos los `Effect` = 48 combinaciones |
| `recovery that genuinely does not apply still reaches the replay kernel` | impide el atajo opuesto: un resolver fail-closed que se negara a ejecutar nada también pasaría la primera |
| `the unobservable arm is reachable only for a RUNNING row under a subprocess policy` | `RecoveryUnobservable` significa exactamente una cosa, y el observador **no** se consulta para combinaciones que no le deben nada |

La tercera tiene además un contador de sondas: si el observador se consultara por una fila
terminal o por una política que no declara recovery, el test falla. Ser consultado de más sería el
mismo defecto un nivel más arriba.

Y la consecuencia end-to-end la mide `S4RecoveryUnobservableFailsClosedTest`, con `assertAll` para
que el RED reporte las cinco leyes a la vez:

```text
handler invocations   0
capability reads      0
cursor writes         0
journal row           sigue RUNNING
run outcome           Failure(INFRASTRUCTURE)
```

---

## 5. RED antes, GREEN después

| fase | log | sha256 | resultado |
|---|---|---|---|
| **RED** (pre-implementación) | `s4r1e-red3.log` | `633f3ac6a56228f3b0c39dd30d3dff4489fad8672de25224e71c487047e12566` | EXIT=1, 1 test, **5/5 leyes violadas**, 0 `^e: ` |
| **GREEN** focal | `s4r1e-fix3.log` | `6a558fcdd23c5822ce7fd6f8d939de6a8b0b94d8b4764dd4cda9013b5bea17c2` | EXIT=0, 32/32 en 6 clases, 0 `^e: ` |

El RED se escribió deliberadamente **contra la API vigente**, para que fallara por comportamiento y
no por compilación: un error de compilación no es un RED, es la clase vieja ejecutándose. La
primera versión del test de la ley sí tenía esa forma y por eso se recortó antes de correr.

### 5.1 M-REC-3 — la mutación pre-registrada, ejecutada

```text
log      s4r1e-mrec3b.log
sha256   c25e9ec008077a663e76c5d7137ad51abf18f33550e6403a1d91381d671a768e
EXIT=1   0 "^e: "
```

La mutación hace que el observador, sin poder mirar, responda **como si hubiera mirar y hubiera
encontrado un éxito** — el fantasma exacto del defecto original.

| fila | resultado |
|---|---|
| ley de fail-closed | **RED** (3 de 5 leyes: cursor movido, fila terminalizada, invocación reportada como éxito) |
| fila 3 del spike | **RED** (la fila deja de estar `RUNNING`) |
| fila 3b del spike | **RED** (el carrier: el observador dejó de decir `Unavailable`) |
| filas 1, 2, 4, 5, 6, 7 | verde |

Testigos: **3**, atribución 1:1 — exactamente los dos pre-registrados más la ley. Restaurada,
`sha256sum -c` sobre `3d4ec7396ac24ad2683a90a8fae67ef213d498b64c6c47c5f4a5722fc482a3cf`.

### 5.2 Gate focal del epoch

```text
cmd     cd v2 && ./gradlew :pipeline-step-sdk:runtime:test :pipeline-domain:test \
                       :pipeline-application:test :pipeline-architecture-tests:test --rerun-tasks
log     s4r1e-focal2.log
sha256  3cd9fe6f2fe9f2c7c964b6975187c7c157ecfa309041f69549995cadf9c97331
EXIT=0  BUILD SUCCESSFUL in 27m 28s
        197 tareas · 0 líneas "^e: "
```

| módulo | clases | tests | fallos | errores | skips |
|---|---|---|---|---|---|
| `pipeline-step-sdk/runtime` | 20 | 201 | 0 | 0 | 0 |
| `pipeline-domain` | 133 | 687 | 0 | 0 | 0 |
| `pipeline-architecture-tests` | 87 | 432 | 0 | 0 | 10 |
| `pipeline-application` | 300 | 2244 | 0 | 0 | 121 |
| **TOTAL** | | **3564** | **0** | **0** | **131** |

Los +13 tests sobre el gate limpio de `aa219e5b` (3551) son exactamente los que este epoch
introduce: 9 del spike S4-R-REC, 1 de la ley de fail-closed y 3 del contrato de la matriz.

---

## 6. Dos regresiones que el gate encontró, y por qué no eran ruido

El primer gate focal (`s4r1e-focal.log`, sha256 `32f2e32d9e90a7d9495eebb77d40c6fae645ee4c3ddfffbe42ede2fca1ac686f`,
EXIT=1, 3561 tests, 2 fallos) encontró dos consecuencias reales de este epoch. Ninguna se
disfrazó de ruido:

### 6.1 `SharedModelCompositionFitnessTest` — un comentario cambió el grafo de acoplamiento

```text
ReplayPolicy: ledger says [pipeline-application, pipeline-domain],
measured [pipeline-application, pipeline-domain, pipeline-scripting-kotlin24]
```

La causa **no** era una dependencia nueva. `consumersOf` es un buscador textual
(`Files.readString(it).contains(typeName)`), y el KDoc que escribí en `ScriptedSourceLowering.kt`
nombraba el tipo. Mi prosa había creado un módulo consumidor fantasma.

Actualizar el ledger para registrar `pipeline-scripting-kotlin24` habría sido **mentir**: declararía
un acoplamiento que el compilador no tiene y corrompería el modelo que la fitness existe para
proteger. El arreglo fue reescribir el comentario sin nombrar el tipo, con una nota que explica por
qué no debe nombrarse.

> **Hallazgo de tooling, no parcheado aquí:** `consumersOf` no resuelve símbolos, busca texto. Una
> frase puede cambiar el grafo de acoplamiento medido sin cambiar una línea de código ejecutable.

### 6.2 `S4RPolReplaySemanticsSpikeTest` fila 1 — un spike que afirmaba el defecto

```text
expected: <MEMOIZED> but was: <RERUN>
```

La fila 1 medía exactamente lo que RPL-3 prohíbe, y por eso se puso roja al arreglarlo. Es el mismo
patrón que las filas 3/4/7 y que K6 de S4-R-KERNEL ya se convirtieron en aserciones de convergencia
en R1-A/C: **una medición del defecto se vuelve una prueba de no-regresión cuando el defecto se
cierra.** La aserción ahora exige que las dos superficies coincidan, que es lo que la ley quiere.

Ambos fallos desaparecieron en `s4r1e-fix3.log` (32/32).

---

## 7. Incidente de entorno, registrado porque contaminó una medición

La primera medición de M-REC-3 dio dos RED **que no eran de la mutación**:

```text
java.io.IOException: Se ha excedido la cuota de disco     ← filas 5 y 6 del spike
```

Causa: 300 directorios `/tmp/tmp*` huérfanos (~33 GB, clones de `repo` de 220M cada uno) sobre un
tmpfs de 48 GB. No era culpa de la mutación ni del código; era un entorno que ya no podía
medir. Se recuperó trashando **sólo los huérfanos de más de 60 minutos** y preservando los 23
recientes, que estaban vivos: `/tmp` pasó de 9,5 GB libres a 36 GB, y la medición se repitió
obteniendo la atribución limpia de la tabla 5.1.

### 7.1 Y un defecto de mi propio harness, que también se corrigió

El Spike creaba sus raíces de control con `Files.createTempDirectory("...")` sin padre, es decir en
`java.io.tmpdir`, y **nunca las borraba**: un directorio por fila y por corrida. 35 directorios
vacíos acumulados, inmateriales en tamaño, pero la fuga era real.

Ahora se crean bajo un `@TempDir` que JUnit borra. No es una cuestión de orden: **un harness que
puede romper el aspecto de su propio sujeto no está midiendo a su sujeto**, y una medición que
falla por cuota de disco se convierte en un RED sin información que la siguiente persona depurará durante
una hora.

---

## 8. Ficheros

### Producción

| Fichero | Cambio |
|---|---|
| `…/durable/RunningSubprocessRecovery.kt` | el port es `observe(operationId)`; nuevo `RunningSubprocessObservation { Recovered, Unavailable(cause) }` y `UnobservableCause`; el adaptador no ve política ni estado |
| `…/durable/CanonicalStructuralDecisions.kt` | `RecoveryRequirement { NotApplicable, Required }`; `InvocationReconciliation.RecoveryUnobservable`; se retira `RunningCanonicalShellRecovery` |
| `…/durable/DurableInvocationResolver.kt` | `recoveryRequirement` decide el CUÁNDO; `recoverRunningShell` desaparece; `Unavailable` corta la decisión |
| `…/durable/RecoveryInterpretationEngine.kt` | brazo `RecoveryUnobservable` → `Settled(Failure(INFRASTRUCTURE))` sin journal, sin cursor, sin eventos |
| `…/scripted/ScriptedRegistryInvoker.kt` | metadata resuelta **antes** del hash; fingerprint sobre `metadata.replayPolicy`; quinto brazo de la decisión; KDoc corregido |
| `…/scripting/ScriptedSourceLowering.kt` | `RUNTIME_COMPATIBILITY_VERSION` `r3-runtime-v1` → `r4-runtime-v1` |

### Test

| Fichero | Cambio |
|---|---|
| `…/durable/S4RecoveryUnobservableFailsClosedTest.kt` | **nuevo** — las cinco leyes de fail-closed sobre la columna real, con `assertAll` |
| `…/durable/S4RecoveryRequiredNeverExecutesTest.kt` | **nuevo** — matriz de 48 combinaciones + el atajo opuesto + sonda del observador |
| `…/spike/S4RRecIndeterminateEffectSpikeTest.kt` | filas 3 y 3b y la matriz invierten a la ley; temporales bajo `@TempDir` |
| `…/spike/S4RKernelSpikeTest.kt` | K6 pasa a convergencia; `CountingRecovery` pierde la firma de política |
| `…/spike/S4RPolReplaySemanticsSpikeTest.kt` | fila 1 pasa a convergencia; KDoc honesto sobre por qué la recuperación por fuerza bruta sigue |

---

## 9. Lo que R1-E NO hace

- **No** toca lowering, restore ni `StepOutcome.Unstable`. Siguen abiertos como slices propios del
  owner.
- **No** migra ni rehashea ninguna fila. D6 lo prohíbe y es lo correcto: la divergencia es
  información.
- **No** introduce un segundo `EffectReplayPolicy` ni una segunda tabla de statuses. Sólo se
  eliminó una tabla (`ScriptedRegistryInvoker` ya no tenía `when (status)`) y se añadió un brazo a
  un ADT existente, no un ADT paralelo.
- **No** repara el `KDoc` de `ScriptedRegistryInvoker` como si el literal nunca hubiera existido.
  El comentario dice que existió, cuándo se quitó y por qué.

Las cinco responsabilidades siguen separadas: construcción de identidad/fingerprint ·
reconciliación durable pura · observación de recovery · ejecución/admisión · restauración tipada
de output. Ningún objeto hace más de una.

---

## 10. Lo que queda abierto

- **PRODUCT-GATE** sigue `BLOCKED_EXTERNAL` desde `754ddda0`: no hay superficie de CI, y eso no lo
  arregla este epoch.
- El defecto de tooling de `sddk-align` (el override `--work-item` es inalcanzable cuando
  `sddk_current_work_item` devuelve vacío) sigue sin parchear, por decisión del owner.
- `consumersOf` como buscador textual sigue siendo una fuente de falsos positivos potenciales en el
  grafo de acoplamiento. No se ha tocado.
- Los slices S4 que el owner quiere después: `restore` con `raw as JsonPrimitive`,
  `StepOutcome.Unstable` perdido como `SUCCEEDED`, `ScriptedSourceLowering` sin reescribir el
  `sh(...)` normal, `readFile`/`fileExists` introduciendo `""`, y el `continue` en vez de
  fail-closed ante fallo de localización de source range.
- La ley de AGENTS.md contra harnesses autoimplementados, pendiente.
