# S4-D2 — `Unstable` conserva su significado en scripted; la frontera de replay queda medida

**Estado:** `CERTIFIED` (step) con una frontera de diseño abierta y registrada
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `c38708028687aed7f29de881dc74b479b6cc083a`
**Decisión de ownership:** opción **(a)** — conservación semántica en FRESH + conservación del payload
tipado; la asimetría de replay se registra como frontera medida y deuda de diseño.

---

## 1. Qué se cambió

Cuatro pérdidas de propagación, dos ficheros de contrato nuevos, y un segundo mapping de precedencia
eliminado. Cero cambios de formato durable.

| fichero | cambio |
|---|---|
| `pipeline-domain/…/durable/TypedStepOutput.kt` | `outcomeOf(value)` — la **única** autoridad `typed output → StepOutcome` |
| `…/durable/RegistryExecutionBoundary.kt` | consume `outcomeOf` en vez de repetir la regla inline |
| `…/scripted/ScriptedRegistryInvoker.kt` | `ScriptedTypedResult<O>` con constructor **privado**; `ScriptedRegistryResult` KDoc reescrito |
| `…/scripted/ScriptedRuntime.kt` | `ScriptedOperationResult`, `ScriptedOutcomeCollector`, scope dueño del collector |
| `…/scripted/RegistryScriptedShellRuntime.kt` | deja de tirar el outcome al encoger a `ShellInvocationResult` |
| `…/scripted/JournaledScriptedOperationRuntime.kt` | adaptación de compatibilidad, reutiliza el clasificador único |
| `…/scripted/CompiledScriptedEntryPoint.kt` | la fachada registra el outcome y devuelve sólo el valor |
| `…/scripted/ScriptedFrontendRunner.kt` | `runBody` reduce con `RunOutcomeReducer`; `Outcome.Completed` lleva `RunOutcome` |
| `…/MainScriptedSupport.kt` | el `when` fabricado `Success/Unstable/Failure` **desaparece** |

### 1.1 Las dos pérdidas que se cierran

```text
1. RegistryExecutionBoundary  ->  CommonExecutionResult(outcome = Unstable, encodedOutput = …)
      -> ScriptedRegistryInvoker  colapsaba Unstable en Success(encoded)
2. runBody  descartaba el resultado del runtime y devolvía StepOutcome.Success fijo
      -> MainScriptedSupport  re-derivaba un RunOutcome con una segunda tabla
```

### 1.2 Por qué `ScriptedRegistryResult` NO ganan un caso `Unstable`

Ese ADT responde «¿la invocación durable entregó un valor utilizable?», no «¿era inestable?».
Ampliarlo habría **duplicado** un hecho semántico que el carrier tipado ya posee, creando una
segunda autoridad capaz de discrepar. Se estrecha el KDoc en su lugar:

- `Success` — produjo o restauró un valor codificable usable. **No** significa «el Step tuvo éxito».
- `Failed` — la invocación **no puede entregar un valor al programa**: fallo semántico,
  incompatibilidad de replay/protocolo, rechazo de admisión, o payload que el codec no lee.

### 1.3 El carrier transporta, no clasifica

`ScriptedTypedResult` tiene constructor privado y una sola entrada:

```kotlin
fun <O : Any> from(value: O): ScriptedTypedResult<O> = ScriptedTypedResult(value, outcomeOf(value))
```

El estado ilegal `ScriptedTypedResult(unstableValue, StepOutcome.Success)` **no es construible**.

No hay factory que compare `boundaryOutcome == outcomeOf(decoded)`, y el motivo está escrito en el
KDoc: el invoker **no usa** el outcome de la boundary. FRESH y REUSE llegan ambos como
`Success(encoded)`, los dos se decodifican con el `outputCodec` del Step, y los dos leen el outcome
del carrier. Esa única línea es la que hace que la proyección **no pueda discrepar** entre sí misma.

`ScriptedOperationResult` sí lleva el par, porque `ShellInvocationResult` no puede expresar
`Unstable` —el campo aporta información real—. Al no poder auto-validarse, la disciplina es
estructural: producción lo construye en **un solo sitio**, `RegistryScriptedShellRuntime`,
proyectando el `ScriptedTypedResult` que ya recibió.

---

## 2. Las leyes que D2 certifica

| ley | enunciado | dónde se prueba |
|---|---|---|
| **D2-L1** | FRESH conserva el valor Kotlin **y** `StepOutcome.Unstable`; el agregado es `RunOutcome.Unstable` | `S4D2ScriptedUnstablePreservationTest` |
| **D2-L2** | el output **persistido** conserva el outcome: `decode(persisted) → outcomeOf == Unstable` | mismo test, leyendo los bytes del journal |
| **D2-L3** | `Success` sigue `Success`, `Failure` sigue `Failure`, y no hay clasificador scripted paralelo | escaneo estructural |
| **D2-L4** | scopes padre/hijo comparten **un** collector | test del scope anidado |
| **D2-L5** | `PipelineStepException` registra `Failure` **exactamente una vez** | escaneo estructural |
| **D2-L6** | `RunOutcomeReducer` es la única autoridad de precedencia | escaneo estructural |

D2-L2 se prueba leyendo los **bytes del journal** y decodificándolos con el codec del Step, no
re-derivándolos del objeto vivo: eso es lo que demuestra que el registro durable conserva la
información.

### 2.1 Requisito RETIRADO

```text
aggregate(FRESH) == aggregate(REUSE)      RETIRADO
handler_count == 1 para Unstable          RETIRADO
```

No se retiran porque fueran falsos, sino porque **ese camino no es alcanzable** bajo la semántica
durable actual. Ver §3.

---

## 3. La frontera medida — el hallazgo de este slice

`Unstable` **es representable y persistido** en el typed output. Pero el durable status lo colapsa:

```text
CanonicalStructuralDecisions.kt:299    StepOutcome.Unstable -> OperationStatus.FAILED
EffectReplayPolicy.kt regla 4          «REUSE OF A SUCCEEDED ROW» — SUCCEEDED únicamente
EffectReplayPolicy.kt regla 7          MEMOIZED + READ_ONLY + not SUCCEEDED -> EXECUTE
```

Consecuencia medida: un Step `Unstable` persiste una fila `FAILED`, **ninguna `ReplayPolicy`
declarada reutiliza una fila no-`SUCCEEDED`**, y el segundo intento **re-ejecuta**. No es que el
reuse pierda el marcador: es que el reuse no ocurre.

```text
MEASURED CURRENT BEHAVIOUR — NOT PROMOTED AS DESIRED SEMANTICS
```

### 3.1 Lo que deliberadamente NO se hizo

**No** se reintrodujo `Unstable → SUCCEEDED` en scripted para conseguir un REUSE. Eso restauraría
un split-brain donde el frontend scripted y la proyección durable canónica discrepan sobre el mismo
Step declarado — exactamente la divergencia que esta convergencia elimina. Es la trampa más fácil
de reintroducir aquí, y está escrita en el KDoc del test de frontera.

### 3.2 `JournaledScriptedOperationRuntime` — tratamiento

Es una **segunda autoridad durable** en `src/main`: hashea su fingerprint con
`ReplayPolicy.MEMOIZED` literal, tiene su propia tabla de replay y su propio `toOperationStatus`.
Alcance medido: se construye **sólo desde tests**, cero desde producción.

La adaptación que exige el nuevo port no es una adaptación mecánica cualquiera: reutiliza
`ShellStepOutcomeClassifier.toStepOutcome()`, que es la autoridad única declarada de `core.sh`, y
`replayFailure` devuelve justo `ShellInvocationResult.Failed`, que ese clasificador mapea a
`StepOutcome.Failure`. Ningún camino queda mintiendo y no se crea una segunda implementación.

**Limitación documentada, no introducida aquí:** `toStepOutcome` no tiene brazo `Unstable`
(`Status` clasifica como `Success`), así que en ese camino legacy un shell UNSTABLE reproducido
sigue leyendo `Success`. Cerrarlo exigiría dar al clasificador un brazo `Unstable`, que es la
segunda implementación semántica que el owner descartó. No se amplía D2 para ello.

---

## 4. Mutaciones

Dos, ortogonales, cada una con **un** test discriminante distinto. Atribución 1:1.

| mutación | cambio | test que cae | el resto |
|---|---|---|---|
| **M-D2-1** | `ScriptedTypedResult.from` proyecta `StepOutcome.Success` fijo en vez de `outcomeOf(value)` | `D2-L1 and D2-L2` | 7 verdes |
| **M-D2-2** | `runBody` devuelve `RunOutcome.Success` fabricado en vez de reducir | `the frontend reduces with the canonical reducer…` | D2-L1/L2 **verde** |

Que M-D2-2 **no** mate D2-L1 es la prueba de que las dos fronteras —proyección y agregación— no se
solapan.

```text
log  md21.log  EXIT=1   restaurada: c5193a759dbc03ecfc7714755b0d0f815006076047567ac25e3d247659a66c03  sha256 -c OK
log  md22.log  EXIT=1   restaurada: 9940d21c60edfd3cffc342bf6d3b564dd7d3f37c6d51ec772d39f2c0fbf14859  sha256 -c OK
residuos de mutación en v2/: 0
```

### 4.1 Una mutación sin dientes, y cómo se cerró

`runBody` es privado y reducir a través de él exige un artefacto compilado, así que la suite
probaba `collector + reducer` pero **no** que `runBody` los invoque: M-D2-2 habría matado nada. Se
cerró con un escaneo estructural, el mismo patrón que ya usa
`S4A1ScriptedShellPathPrivilegeCanaryTest` en este paquete.

Ese escaneo **lee código con comentarios eliminados**, porque el KDoc del propio método que
protege nombra `StepOutcome.Success` para explicar que no debe contenerlo. Un escaneo que matchea
texto crudo puntuaría documentación.

---

## 5. Gate

```text
log      s4d2-focal.log
sha256   5014960beaa49898064e1243d444a8937800c2ef8b7e978c66eba84dc5307a6a
comando  v2/gradlew :pipeline-application:test --tests <8 clases D2 y adyacentes>
                  :pipeline-architecture-tests:test
EXIT=0   BUILD SUCCESSFUL in 2m 59s
         94 clases · 498 tests · 0 fallos · 0 errores
```

Desglose: `pipeline-application` 7 clases / 66 tests, `pipeline-architecture-tests` 87 / 432.
Cero `^e: `.

Procedencia: el `sha256` y el `BUILD SUCCESSFUL` **sí** son verificables del log. `EXIT=0` viene
de `$?` del shell y **no** está en el log. Los conteos por clase salen de los XML de JUnit, que
Gradle no imprime cuando la tarea pasa.

Alcance: gate **quirúrgico**, no el suite completo. D2 sigue siendo un WU interno del TRAIN y los
criterios de integración no exigen todavía el full gate; éste corresponde al cierre de
integración/release.

### 5.1 Caracterización previa: transición declarada

`S4A0ScriptedUnstableOutcomeCharacterizationTest` caracterizaba la pérdida. S4-D2 la cierra, y la
transición está escrita **en el mensaje de aserción y en el KDoc de la clase**, no reescrita en
silencio:

```text
ANTES DE D2
  Unstable era representable en los ADTs, pero se perdía en scripted.

RESUELTO POR D2
  la representación no cambió; se repararon las dos pérdidas de propagación.
```

Ese test sigue afirmando que `ScriptedRegistryResult` es exactamente `{Success, Failed}` — y ahora
eso es **correcto por diseño**, no por defecto.

---

## 6. Decision point abierto — NO decidido aquí

> **Durable semantic terminal outcome for `Unstable`**

¿Cómo preserva el protocolo durable que una operación terminó correctamente pero produjo
`StepOutcome.Unstable`, sin confundirla con `Failure` ni inventar un replay seguro?

Opciones que un ADR propio debe estudiar. **Ninguna se elige aquí.**

| | opción | qué toca |
|---|---|---|
| **A** | `OperationStatus.UNSTABLE` | esquema durable, serialización, SQLite/journal, lectores, reconciliadores, matriz de replay, migración, UAT de resume |
| **B** | `OperationStatus` terminal + `semanticOutcome` separado | dos ejes explícitos; el estado operacional deja de cargar el resultado semántico |
| **C** | restaurar el outcome semántico desde el typed output como **hecho de reconciliación** adicional | la autoridad de replay recibe un hecho nuevo; no cambia el formato |
| **D** | otra representación algebraica del terminal durable | por estudiar |

La lectura que este slice hace del hallazgo, sin cerrarla: `OperationStatus` parece estar cargando
simultáneamente dos conceptos —**estado operacional durable** y **resultado semántico del Step**—.
`Unstable` es terminal semánticamente pero hoy se representa como `FAILED` operacionalmente. Antes
de añadir otro valor de enum, conviene estudiar si lo correcto a largo plazo es separar ambos ejes.

**La opción (b) del pregunta anterior —«re-ejecutable sin efecto secundario»— se descarta
explícitamente:** no es una categoría durable suficientemente definida. Decidir que una operación
`Unstable` puede re-ejecutarse con seguridad exige razonar sobre `ReplayPolicy` + effect set +
idempotencia + outcome terminal + disponibilidad de output, y algunos Steps `Unstable` pueden
haber producido efectos reales. Sería un parche de `EffectReplayPolicy` dentro de D2.

---

## 7. Lo que este documento NO hace

- No cambia `EffectReplayPolicy`, ni el esquema durable, ni añade `OperationStatus.UNSTABLE`.
- No certifica el PRODUCT-GATE, que sigue `BLOCKED_EXTERNAL` desde `754ddda0`.
- No decide el decision point de §6.
- No amplía `JournaledScriptedOperationRuntime` más allá de la adaptación de compatibilidad.
- No es el suite completo; el gate es quirúrgico y su alcance está declarado en §5.

---

## 8. Referencias

- `docs/v2/06-design/S4_UNSTABLE_OUTCOME_AGGREGATION_PROPOSAL.md` — la propuesta, `5964fb7f`; §7
  subestimaba el alcance (no listaba `ScriptedOperationRuntime` ni sus 9 sitios de test)
- `v2/pipeline-application/…/durable/CanonicalStructuralDecisions.kt:299` — la proyección que colapsa
- `v2/pipeline-step-sdk/runtime/…/EffectReplayPolicy.kt:33-39` — la tabla de reglas
- `v2/pipeline-application/…/durable/ShellStepOutcomeClassifier.kt:30` — clasificador único de `core.sh`
- `docs/v2/07-uat/LB02_G3_A4_3_TYPED_OUTPUT_CARRIER.md` — por qué `TypedStepOutput` ya era autoridad
- `docs/v2/07-uat/S4_SAST_DISPATCH_LONGMETHOD_BASELINE_RECEIPT.md` — bloque 1, `c3870802`
