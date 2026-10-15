# Escalado: orden de `RunCorrelated` respecto a `CompilationStarted`

**Estado:** DECISIÓN PENDIENTE del responsable de la API de eventos. No se ha tocado código.
**Origen:** nota diagnóstica del `releaseGate` de `v0.61.0-rc2`, sección "Divergencia del contrato de eventos".
**Base verificada:** `c3c77fa0` (main).

Este documento existe para que la decisión se tome sobre la evidencia, no sobre la lectura de tres
tests que fallan. No propone un cambio de código ni lo aplica.

---

## 1. El hecho, medido

Tres tests afirman que el primer evento del timeline es `CompilationStarted`:

| Test | Aserción | Conteo esperado | Conteo observado |
|---|---|---|---|
| `UatEvt001ReplayTest:76` | `events[0] is CompilationStarted` | 9 | 10 |
| `UatEvt002MultiStepReplayTest:124` | `events[0] is CompilationStarted (durable spine)` | 18 | 19 |
| `UatDsl005TimeoutGrammarTest:200` | `events.first() is CompilationStarted` | — | — |

El evento observado en `events[0]` es `RunCorrelated`. La diferencia de un evento por run es
exactamente ese evento extra, y por eso los conteos no cuadran.

**Reproducido en `c3c77fa0`, no heredado de la nota.** Con el canario borrado y XML fresco:

```text
UatDsl005TimeoutGrammarTest   7 tests, 1 failure, 0 errors, 2026-10-15T12:36:36Z
  timeout-retry script produces complete event timeline
  First event must be CompilationStarted (durable spine: script compiles before the run starts)
  ==> expected: <true> but was: <false>

UatEvt001ReplayTest           3 tests, 1 failure, 0 errors, 2026-10-15T12:37:37Z
  re-parsed timeline equals original with correct kinds
  Expected 9 events for hello DSL pipeline: {"schema":"pipelinek.launch/1", ...}

UatEvt002MultiStepReplayTest  2 tests, 1 failure, 0 errors, 2026-10-15T12:38:38Z
  multi-step script compiles successfully
  Expected 18 events from multi-step fixture: {"schema":"pipelinek.launch/1", ...}
```

El fallo de conteo y el de orden son el mismo hecho visto desde dos aserciones distintas: hay un evento
más, y ese evento ocupa la posición cero. Los tres tests reproducen en `c3c77fa0`; ninguno es un
artefacto del árbol sobre el que corrió el `releaseGate` de la nota.

## 2. Lo que el producto hace, con líneas

En la rama durable (`Main.kt`):

```text
:703   leaseStore  →  ya se tiene la propiedad de la ejecución
:761   emitCorrelationAndReceipt(...)   → escribe RunCorrelated y el registro last-run
:779   host.compile(definition)        → el hosting emite CompilationStarted
```

La rama in-memory tiene la misma forma: la correlación se emite en `:436`, la compilación en `:454`.
Ambas ramas escriben sobre el **mismo** `eventStore`, así que no son dos sumideros que "parezcan"
ordenados: es un solo orden, observado en los dos caminos.

## 3. Lo que el contrato promete

`PipelineIds.kt:33-34`:

```text
- The [RunCorrelated] event (before the first [RunStarted]).
```

`docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md:46-47` dice lo mismo.

**El contrato no menciona `CompilationStarted`.** No existe, en ningún documento norms accepted,
una promesa sobre el orden relativo entre `RunCorrelated` y `CompilationStarted`.

Contra lo que el contrato sí dice, el producto es **correcto**: `RunCorrelated` precede a
`RunStarted`.

## 4. Por qué mover la correlación sería un retroceso

Aunque los tests esperan lo contrario, invertir el orden rompería una propiedad que ya está probada
y pagada:

`emitCorrelationAndReceipt` no solo emite el evento: también escribe el registro `last-run` del control
dir. Es lo que permite que una invocación posterior con `--resume` encuentre la ejecución, y es
justamente el registro que `UatS2R0RunOwnershipCliTest` consume para arbitrar la propiedad de la
ejecución. Si la correlación se moviera después de `host.compile`, **un script que no compila no
dejaría traza de correlación**: el run desaparecería del registro sin que nada lo explicara, y el
`--resume` de esa invocación no tendría qué reanudar.

Es decir: el orden actual no es un descuido de ordenación. Es la razón por la que un fallo temprano
sigue siendo observable.

## 5. Lo que sí es un defecto real, y no es el que reportaba la nota

Los tres tests no son simplemente viejos. Los tres contienen la cadena `"durable spine"` en el mensaje
de aserción:

```kotlin
// UatEvt002MultiStepReplayTest:124
assertTrue(events[0] is CompilationStarted, "events[0] must be CompilationStarted (durable spine)")
// UatDsl005TimeoutGrammarTest:200
assertTrue(events.first() is CompilationStarted, "First event must be CompilationStarted (durable spine: script compiles before the run starts)")
```

Eso documenta una **intención que nunca se escribió en ningún contrato**: que en la espina durable el
script se compila antes de que el run empiece. Esa intención, escrita como aserción, es más fuerte
que lo que el ADR dice, y el producto no la cumple desde que existe la rama durable.

Además, corregir solo `events[0]` **no** basta: los tests tienen aserciones indexadas posteriores
(`events[2]`..`events[8]` en `UatEvt001`), y `RunCorrelated` desplaza todos los índices en uno. Un
parche que arregle la primera aserción y deje las demás como están deja el test rojo igualmente, y esa
es la forma de un arreglo que no se detectó en la revisión que lo autorizó.

## 6. Decisión que se pide

Dos opciones, con sus consecuencias. La primera es la recomendada, y la razón es que el producto no
está mal.

**Opción A — el contrato gana: `RunCorrelated` precede a la compilación (recomendada).**

- Se actualiza `PipelineIds.kt:33-34` y `ADR-PK1-IDENTITY.md:46-47` para nombrar el punto de anclaje
  real ("before compilation, and therefore before `RunStarted`") en vez de dejar el orden del timeline
  sin prometer.
- Se corrigen los tres tests: `events[0]` pasa a `RunCorrelated`, los índices se desplazan, los conteos
  esperados suben en uno, y el mensaje `"durable spine"` se reescribe para que no afirme una intención
  que el contrato no sostiene.
- Se añade una fila al contrato de eventos: `RunCorrelated` es el primer evento del run.
- Coste: tres archivos de test y dos de contrato. Ninguna línea de runtime.

**Opción B — la intención gana: la compilación precede a la correlación.**

- Requiere un ADR que reescriba `ADR-PK1-IDENTITY` y acepte explícitamente la pérdida de §4: una
  invocación que no compila no deja registro de correlación.
- Requiere decidir qué ocurre con `--resume` en ese caso, que es una pregunta de producto y no de
  orden de eventos.
- Coste: ADR + cambio de runtime + decisión de comportamiento de `resume` + los mismos tres tests.

## 7. Por qué esto no se ha corregido aquí

El mandato del lote era corregir el harness sin tocar producción ni contratos. Ambas opciones tocan un
contrato: A modifica dos documentos normativos aceptados, B modifica el runtime y su semántica de
reanudación. Ninguna es una corrección de test, que es lo que el lote autorizaba.

Escalar es la salida correcta, no una postersgación: la alternativa —elegir una opción por ser la que
"toca menos"— produciría un test verde que documenta un contrato distinto del que el producto
sostiene, que es exactamente el defecto que este repositorio ya ha pagado dos veces.