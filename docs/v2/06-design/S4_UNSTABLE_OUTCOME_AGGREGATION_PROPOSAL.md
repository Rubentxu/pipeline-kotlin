# S4-UNSTABLE — Propuesta de agregación de `StepOutcome.Unstable` en el frontend scripted

**Estado:** `PROPOSAL` · decisión de ownership pendiente del owner · **cero cambios de código**
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Base:** `bba315eb342cdd0c7c8b3d06800a155ce4a21163`
**Petición que responde:** *«redacto la propuesta del acumulador y tú decides la forma antes de que toque código»*

---

## 0. La respuesta corta

**No hay un acumulador que diseñar.** El agregador ya existe, es puro, tiene la precedencia
definida y está probado: `RunOutcomeReducer.reduce(List<StepOutcome>): RunOutcome`.

Escribir un acumulador nuevo sería crear un **cuarto** mecanismo de agregación en un sistema que
ya tiene tres, y la ley que dice que debe haber uno está escrita en el propio dominio. La pregunta
correcta no es «qué forma le doy al acumulador» sino **«cuál de los tres mecanismos existentes es la
autoridad, y qué se hace con la tercera capa que nadie había nombrado»**.

Al investigar para responder, apareció algo que **cambia la decisión**, así que lo pongo delante
en lugar de al final (§3).

---

## 1. El agregador que ya existe

`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RunOutcomeReducer.kt`

```kotlin
object RunOutcomeReducer {
    fun reduce(steps: List<StepOutcome>): RunOutcome { … }
}
```

- **Puro**: sin I/O, sin reloj, sin logging. El fitness ya lo exige
  (`FArchM1CanonicalOutcomesTest:120-125` prohíbe `Instant.now`, `println`, etc. dentro del cuerpo).
- **Precedencia declarada**: `Failure` (la primera) > `Unstable` (el primero) > `Success`.
  Vacía → `Success`. `Aborted` **nunca** se deriva de pasos; lo fija el orquestador.
- **Probado**: `RunOutcomeReducerTest` con 10 casos, incluido orden inverso, colapsado de múltiples
  `Unstable` y determinismo.

Y su KDoc no deja lugar a interpretación:

> This is the **single** authority that produces a `RunOutcome`. No other site in the codebase is
> allowed to fabricate a `RunOutcome` literal … (`CANONICAL_CONTRACTS_SPEC.md §Outcomes`:
> «`RunOutcome` se obtiene mediante un único reducer»).

El acumulador es, por tanto, un `List<StepOutcome>`, y el plegado ya está hecho.

---

## 2. Hay tres mecanismos de agregación, no uno

| # | Mecanismo | Forma | Dónde | Precedencia |
|---|---|---|---|---|
| 1 | `RunOutcomeReducer.reduce` | lista → `RunOutcome` | dominio; lo usan `ConcurrentStepDispatcher`, `ExecutionUnit`, `InMemoryRunCoordinator`, `InMemoryCompiledRunCoordinator`, `RunCoordinator` | **declarada y testeada** |
| 2 | `RunLifecycleEngine.fold` | `outcome = value` (**sobrescribe**) | `CanonicalDurableRunCoordinator`, `StageExecutionEngine` | en el control flow del caller |
| 3 | `when` a mano | 1 agregado → `RunOutcome` | `MainScriptedSupport.kt:55-61` | **reimplementada a mano** |

El mecanismo 2 sobrescribe sin mirar el valor previo: la precedencia la garantiza que el
coordinador sólo llame a `fold(Failure)` cuando sabe que debe ganar. Funciona, pero la decisión
vive en el caller, no en el agregador.

El mecanismo 3 es el frontend scripted, y es el que la ley prohíbe por nombre: **fabrica
literales de `RunOutcome`**. Y su rama `Unstable` es hoy **inalcanzable**, porque
`ScriptedFrontendRunner.runBody` devuelve `StepOutcome.Success` incondicional
(`ScriptedFrontendRunner.kt:147`).

El camino scripted es, por tanto, el único de los tres que no tiene resultado por llamada y el
único que reimplementa el mapeo a mano.

### 2.1 El gap de fitness que sale de aquí

`FArchM1CanonicalOutcomesTest` fija **dónde se declaran** los tipos (`allowlist` exacto) y que el
reducer es puro. **No fija que nadie fabrique un `RunOutcome` fuera del reducer.** Por eso hoy
pasan sinested nada:

- `MainScriptedSupport.kt:58-60` — tres literales fabricados a mano.
- `RunLifecycleEngine.kt:37` — `private var outcome: RunOutcome = RunOutcome.Success`, un literal
  de arranque fuera de la autoridad.

Es un gap real y pequeño de cerrar, pero es **fitness**, y decidir si la ley se hace mecánica o
se queda en KDoc es del owner (quedaba ya como pregunta abierta desde `fa1cfb20`).

---

## 3. El hallazgo que cambia la decisión: `Unstable` no tiene carrier durable

La pérdida de `Unstable` no ocurre en un sitio. Ocurre en **tres capas**, y la tercera no está en
la columna scripted.

```text
CAPA 1 — el invoker descarta el marcador
  ScriptedRegistryResult = { Success, Failed }            ScriptedRegistryInvoker.kt:39-45
  el `else` de execute() absorbe Unstable → Success       ScriptedRegistryInvoker.kt:419-433
  └── lo caracterizó S4A0ScriptedUnstableOutcomeCharacterizationTest

CAPA 2 — el runner nunca agrega
  runBody devuelve StepOutcome.Success literal            ScriptedFrontendRunner.kt:147
  nadie consulta resultados por llamada
  └── MainScriptedSupport.kt:59 queda INALCANZABLE  (ésta es la respuesta a S4-C)

CAPA 3 — el Journal no puede representarlo            ← NADIE LA HABÍA NOMBRADO
  internal fun StepOutcome.toOperationStatus() = when (this) {
      StepOutcome.Success   -> OperationStatus.SUCCEEDED
      StepOutcome.Unstable  -> OperationStatus.FAILED     ← aquí
      is StepOutcome.Failure -> if (TIMEOUT) FAILED_TIMEOUT else FAILED
  }                                    CanonicalStructuralDecisions.kt:297-305
  OperationStatus no tiene miembro UNSTABLE.
```

**Consecuencia que hay que decidir antes de escribir el acumulador.** Un run **fresco** ve el
`StepOutcome.Unstable` en memoria. Un run **reanudado** lee el journal, ve `FAILED`, y no puede
distinguir «falló» de «inestable». O sea:

```text
con sólo las capas 1+2   run fresco  → Unstable
                          run reanudado → Failure   ← asimetría NUEVA
hoy (capa 1 sola)        run fresco  → Success
                          run reanudado → Failure   ← asimetría vieja, pero uniforme
```

Arreglar 1+2 sin 3 **introduce una divergencia fresco/reanudado donde antes no la había**, y
justo la clase de divergencia que R1-E (`d0077253`) pasó su vida eliminando. Eso no es un
argumento para no hacerlo; es un argumento para que sea una decisión explícita y no un efecto
colateral de un arreglo deaggregación.

### 3.1 Detalle que refuerza la lectura de «decisión, no descuido»

`toOperationStatus()` **no tiene KDoc**. El `when` es exhaustivo y sin `else`, lo que significa
que alguien loPensó como match cerrado; pero la rama `Unstable → FAILED` no dice por qué. No
sé si es una decisión meditada o un marcador pendiente. **No lo he tocado** precisamente porque
no sé cuál de las dos cosas es, y presuponerlo sería fabricar continuidad.

---

## 4. El fork: tres caminos

### Opción A — capas 1+2, corrección en proceso

Arregla la pérdida en memoria y hace que el agregado sea real. **La asimetría de §3 se acepta y se
documenta** como frontera conocida: `Unstable` es correcto para el run que lo observa, y el journal
sigue sin poder transportarlo.

- Superficie: `ScriptedRegistryResult` (1 caso nuevo), `ScriptedRegistryInvoker.execute` (match
  exhaustivo), `ScriptedFrontendRunner.runBody`, `RuntimeScriptedStepFacade` (sumidero), y el
  `when` de `MainScriptedSupport` que **desaparece**.
- No toca formato durable, ni `r4-runtime-v1`, ni la columna de reconciliación de `d0077253`.
- Riesgo: baja. Es la que hace desaparecer la rama inalcanzable sin tocar el journal.

### Opción B — las tres capas

Añade `OperationStatus.UNSTABLE` para que el marcador cruce el journal, y `Unstable` sobrevive
al resume. Elimina la asimetría de raíz.

- **Es un cambio de formato durable.** Y eso es literalmente uno de tus STOP: *«cambio incompatible
  de formato durable»*. Entra con ADR propio, bumpeo de `RUNTIME_COMPATIBILITY_VERSION` (`r5`),
  y toca la autoridad de reconciliación canónica que `d0077253` acaba de certificar.
- Además hay que decidir qué política de replay tiene una fila `UNSTABLE`: `MEMOIZED` no tiene
  sentido (hay output), y `RERUN` re-ejecutaría un `warnError` que ya emitió su aviso.

### Opción C — declarar `Unstable` no soportado en scripted

Se documenta la limitación y se deja el marcador donde está. Honesto, pero **reduce capacidad** y
deja la rama inalcanzable en `MainScriptedSupport` como código muerto que dice lo contrario de lo
que ocurre.

**No elijo entre A, B y C.** B es un STOP condition de autoridad tuya. A y C son decisión de
producto/semántica, también tuya. Lo que sí hago constar es mi lectura: **A sin la asimetría de §3
documentada y aceptada explícitamente es un semi-arreglo**, y el semi-arreglo que más se parece a
un bug silencioso es ése.

---

## 5. Si eliges A, la forma concreta sería ésta

La dejo escrita para que la apruebes o la corrijas, no para que la ejecute.

**5.1 El caso nuevo tiene que llevar payload.** No puede ser `data object`: un `warnError` que
marcó inestable **igual entrega un valor usable** (su stdout, su output tipado). Un `data object`
no puede portar eso.

```kotlin
sealed interface ScriptedRegistryResult {
    data class Success(val encodedOutput: EncodedStepValue) : ScriptedRegistryResult
    data class Unstable(val encodedOutput: EncodedStepValue) : ScriptedRegistryResult
    data class Failed(val failure: PipelineFailure) : ScriptedRegistryResult
}
```

**5.2 El `else` desaparece y con él el olvido.** En `execute()`, el match sobre
`execution.outcome` pasa a ser **exhaustivo sobre `StepOutcome`**: `Failure → Failed`,
`Unstable → Unstable(encoded)`, `Success → Success(encoded)`, cada uno con su propia aridad sobre
`encodedOutput`. El compilador deja de permitir que el próximo caso del ADT se pierda en un `else`.
Eso es la garantía estructural, y es la misma forma que ya se usó en S4-C4 (match exhaustivo sobre
`JsonElement`).

**5.2b El compilador señala los dos sitios, no uno.** `invokeTyped` tiene su **propio** `when`
sobre `ScriptedRegistryResult` (`ScriptedRegistryInvoker.kt:248-261`), y también es exhaustivo
(`Success` decodifica, `Failed` lanza) — hoy no tiene `else` que esconder nada. Al añadir el caso
`Unstable` **el compilador obliga a decidir los dos matches**, porque los dos son exhaustivos sobre
el ADT que se ensancha. No hay forma de añadir el caso y olvidar uno de los dos: es un error de
compilación, no un bug silencioso. En el `when` de `invokeTyped` el caso nuevo decodifica igual que
`Success` (mismo `outputCodec`, mismo manejo de `IllegalArgumentException` → `REPLAY_COMPATIBILITY`),
porque `Unstable` también entrega un valor utilizable.

**5.3 El punto de registro es la fachada, y por una razón concreta.** `RuntimeScriptedStepFacade.call`
es el **único** sitio que ve todo resultado por llamada — `invokeTyped` devuelve `O` (el valor
decodificado), no un outcome, así que el invoker no puede agregarlo sin cambiar su contrato, que es
«encoded output, nada más». La fachada ya es el seam único que S4-A1 consolidó a propósito
(`CompiledScriptedEntryPoint.kt:39-52`).

**5.4 El sumidero es de ámbito de run, y por eso no puede ser un campo del façade.** Las fachadas
se re-crean por scope dinámico (`scoped()` en `CompiledScriptedEntryPoint.kt:72-77` pasa
`this` como scope, y crea `RuntimeScriptedStepFacade(this, registryInvoker)`). Un acumulador en la
instancia se perdería al salir del scope. El sumidero tiene que vivir en el **`ScriptedRuntime`**
(o en el `ScriptedScope`) y bajar por la llamada.

**5.5 `runBody` devuelve `RunOutcome`, no `StepOutcome`.** Es lo que hace desaparecer el `when`
fabricado de `MainScriptedSupport.kt:55-61` sin reemplazarlo por otro. El agregado se lee una vez,
se pliega con `RunOutcomeReducer.reduce` y se devuelve. La precedencia deja de estar escrita dos
veces y pasa a estar escrita una, en el sitio que dice que es el único.

---

## 6. Lo que este documento NO hace

- **No escribe código.** Ni una línea de producción ni un test. La petición era la propuesta primero.
- **No decide.** A / B / C son del owner, y B es un STOP condition explícito.
- **No toca `toOperationStatus()`** ni la columna certificada en `d0077253`.
- **No afirma que la opción A sea correcta.** Afirma que es la más pequeña, y que arrastra una
  asimetría que hay que decidir a knowingly.
- **No cierra el gap de fitness de §2.1.** Es una pregunta abierta desde `fa1cfb20`.

---

## 7. Ficheros que la decisión tocaría

| fichero | qué cambia | en A | en B |
|---|---|---|---|
| `…/scripted/ScriptedRegistryInvoker.kt:39-45, 419-433` | caso `Unstable` + match exhaustivo | sí | sí |
| `…/scripted/CompiledScriptedEntryPoint.kt:53-70` | sumidero de ámbito de run, cableado en `call` | sí | sí |
| `…/scripted/ScriptedRuntime.kt` / `ScriptedScope` | dueño del sumidero (§5.4) | sí | sí |
| `…/scripted/ScriptedFrontendRunner.kt:123-151` | `runBody` devuelve `RunOutcome` reducido | sí | sí |
| `…/MainScriptedSupport.kt:55-61` | el `when` fabricado **desaparece** | sí | sí |
| `…/durable/CanonicalStructuralDecisions.kt:297-305` | `Unstable → OperationStatus.UNSTABLE` | no | **sí** |
| `RUNTIME_COMPATIBILITY_VERSION` (`ScriptedSourceLowering.kt`) | `r4 → r5` | no | **sí** |
| `docs/v2/04-adrs/ADR-0103` | D7/D8 sin redactar; B necesita un ADR propio | no | **sí** |

## 8. Referencias

- `S4A0ScriptedUnstableOutcomeCharacterizationTest` — caracterización de las capas 1 y 3.
- `S4_SRANGE_FAIL_CLOSED_LOWERING.md` — el mismo patrón aplicado a otro sitio: el `continue` que
  se tragaba el caso que el ADT sí sabía expresar.
- `d0077253` (R1-E) — la columna de reconciliación que B tocaría.
- `RunOutcomeReducerTest` (10 casos) — la precedencia que A reutilizaría sin reescribirla.
