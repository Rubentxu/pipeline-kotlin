# RP035-C — Ruta dirigida por handler sobre la spine durable existente

Estado: **EJECUTADO**, gate completo verde sobre el árbol de este slice
Cycle SDDK: `p-1f3622e11c093341/rp-035-handler-continuation`
WorkItem: `ed95116d-a86b-4d3a-abcc-886ece9a209b`
Baseline: `9521d256` (BUILD SUCCESSFUL in 20m 26s)

## Lo que cambia

Un Step que declara `BodyExecutionOwner.HANDLER_CONTINUATION` ejecuta su handler registrado
sobre la spine durable de registro y alcanza su cuerpo únicamente a través de la
continuación ligada. Un Step con cualquier otro owner conserva exactamente el camino
anterior.

## Decisión de diseño: dispatchBody no se toca

La continuación es una lambda que invoca `dispatchBody`. No se extrajo nada, no se
duplicó nada:

```kotlin
BodyContinuation { bodyContext ->
    val attemptSegment = bodyContext.attempt?.let {
        listOf(BlockSegment(it.index, it.key))
    } ?: emptyList()
    val contextForCall = applyPatchToContext(executionContext, bodyContext.patch)
    BodyOutcome.Completed(
        dispatchBody(
            block = step, runId = runId, stageName = stageName,
            stageIndex = stageIndex, stepIndex = stepIndex,
            stageShOptions = stageShOptions,
            parentBodyPath = bodyPath + attemptSegment,
            executionContext = contextForCall,
        ),
    )
}
```

De ahí salen tres propiedades por construcción, no por argumento:

- `CANONICAL_ENGINE` es código **literalmente sin modificar**. Bit-idéntico no por
  análisis sino porque no se lo tocó.
- El motor sigue siendo el **único ejecutor** de los hijos. El handler nunca recibe un
  `StepNode`, nunca itera hijos, nunca ejecuta un cuerpo.
- No hay bracket `open`/`close`, ni segunda execution boundary, ni superficie de lifetime.
  La continuación nace con la invocación del handler y muere con ella.

El enrutado lee el owner **declarado** desde el registro abierto (`declaredBodyOwnerOf`),
nunca la clave. Una clave desconocida conserva el camino histórico, de modo que el
rechazo sigue viniendo del resolver de política exactamente como antes.

Un Step `HANDLER_CONTINUATION` **cae** (`falls through`) al camino de registro ya existente
en lugar de cortarse. De ahí salen, sin código nuevo: decode tipado, admisión fail-closed
de capabilities, `StepStarted`/`StepFinished`, output tipado, `FailureKind`, journal,
replay, cursor y una única execution boundary.

## El cuerpo forma parte de la identidad durable del padre

Una ruta dirigida por handler otorga al padre una operación, un fingerprint y un journal
que la forma dirigida por el motor nunca tuvo. Con el fingerprint calculado solo desde el
payload del padre, esto quedaba abierto:

```text
run 1: plugin(foo) { echo("A") }  ->  padre SUCCEEDED, output memoizado
run 2, mismo runId, mismo payload: plugin(foo) { echo("B") }
        -> fingerprint igual -> REUSE
        -> el handler no corre -> la continuación no se invoca
        -> el hijo cambiado ni siquiera llega a divergencia
```

`BodyStructureDigest` hashea, por hijo y en orden de declaración, el `id`, el
`pluginStepId`, la versión de esquema y el payload codificado. Se añade a los `params` del
`OperationInput` como `bodyStructure`.

**Alcance deliberado: solo la familia `HANDLER_CONTINUATION`.** Los bloques dirigidos por
el motor conservan su fingerprint actual, porque cambiar la identidad de Steps ya
publicados invalidaría las filas de journal de la candidata `bd166619…` entregada al
harness. Eso es una decisión de spine, no un efecto colateral de añadir una familia de
Steps.

## Evidencia

`ExternalHandlerContinuationProofTest` (4 filas, 0 fallos):

| Fila | Qué demuestra |
| --- | --- |
| `handler_is_invoked` | El handler corre exactamente una vez y ve la capability que declaró. Es el RED del slice A, ahora verde. |
| `the body runs only when the handler invokes it` | Un handler que no invoca produce **cero** efectos hijos. Antes producía uno. |
| `invoking the continuation twice does not duplicate effects` | Los ids de operación hijos son deterministas, así que una segunda invocación reutiliza la fila del journal en vez de duplicar el efecto. |
| `a changed body is not silently reused` | Mismo payload, cuerpo distinto, mismo runId: no vuelve como reutilización silenciosa. |

## Incidente de fitness: el ADT cerrado atrapó una future incompatibilidad

Durante la evolución de `BodyExecutionOwner` en el slice B escribí la comprobación de
coherencia owner/capability con un `else ->`. El fitness de arquitectura lo rechazó:

```text
Lfc2BodyExecutionPolicyFitnessTest >
  the policy vocabulary has no else branch hiding an unhandled case
  Every when over the closed policy family must be exhaustive; an else branch would
  silently absorb a future case ==> expected: <true> but was: <false>
```

La forma correcta enumera cada caso, con `null` como rama propia porque "no hay body
declarado" es un hecho distinto de cualquier owner:

```kotlin
val coherent = when (owner) {
    BodyExecutionOwner.HANDLER_CONTINUATION -> declaresContinuation
    BodyExecutionOwner.CANONICAL_ENGINE -> !declaresContinuation
    BodyExecutionOwner.LEGACY_LINEAR -> !declaresContinuation
    null -> true
}
```

Se registra aquí a propósito: no es ruido de un test verde final, es el fitness
**demostrando utilidad durante la evolución real del ADT**. Un `else` habría absorbido en
silencio el cuarto owner cuando aparezca, y ese `else` es exactamente el sitio donde una
decisión de semántica debe ser explícita.

## Lo que este slice NO cubre

- La teeth test de una continuación **retenida** e invocada tras cerrarse el scope. Con
  este diseño el objeto es inalcanzable después del handler, así que la propiedad se
  cumple por construcción; queda pendiente demostrar que una implementación futura que
  reintroduzca el bracket `open`/`close` no la rompe.
- El FAILURE de `times = 0`, `times = 3` y las leyes de identidad durable del plugin
  externo: son del slice D, sobre el JAR real.
- La ejecución contra la distribución instalada: slice E.
