# RP035-A — RED discriminante de handler-driven body re-entry

Estado: **EJECUTADO** sobre `9521d256`
Cycle SDDK: `p-1f3622e11c093341/rp-035-handler-continuation`
WorkItem: `ed95116d-a86b-4d3a-abcc-886ece9a209b`

## Por qué existe esta WU

RP-3 cierra con la afirmación de que un Step externo **con y sin cuerpo** accede al mismo
registro genérico. Lo que la prueba de RP-033
(`ExternalStepWithBodyRegistryProofTest`) demuestra de hecho es otra cosa, y más débil:

    external key
       -> BlockStepNode
       -> resolucion generica de BodyExecutionPolicy
       -> el coordinator ejecuta los hijos

Es decir, **routing estructural genérico**. No es ejecución open-world: el registro se usa
para resolver política y para rechazar claves desconocidas, nunca para ejecutar el Step
externo.

## El defecto, localizado

`CanonicalDurableRunCoordinator.dispatchOne` (línea 975 en `9521d256`):

    if (step is BlockStepNode) {
        val body = dispatchBody(step, runId, stageName, stageIndex, stepIndex,
                                 stageShOptions, bodyPath, executionContext)
        return Dispatched(body, executionContext)
    }

`dispatchBody` (línea 1590) hace exactamente tres cosas: resolver la política declarada desde
el registro con rechazo fail-closed, proyectar el scope desde el input decodificado, y llamar
a `invokeBodyChildren`. **El handler registrado del bloque no se invoca en ningún punto del
camino.**

Consecuencias, todas verificadas:

1. El input tipado del Step nunca llega a un handler, y su output tipado nunca se produce.
2. Una capability declarada por el plugin nunca se observa. En la prueba de RP-033,
   `requiredCapabilities = emptySet()` funciona precisamente porque el handler es código
   muerto en esa ruta.
3. El engine sustituye silenciosamente su propia semántica de cuerpo por la del plugin. Un
   Step de plugin no puede decidir **si** ejecutar su cuerpo; el engine lo ejecuta siempre.

El propio código lo admite: el adaptador de body-reentry está descrito como
"dormant until a registry-driven handler invokes it", y lo está porque un handler de registro
nunca se ejecuta y, aunque se ejecutara, no existe forma pública de obtener su `BodyRef`.

## RED-1 — el handler debe invocarse

Prueba: `ExternalHandlerContinuationProofTest.handler_is_invoked`, con un Step externo que
declara `BODY_INVOKER_CAPABILITY` y un handler que cuenta invocaciones.

Comando:

    cd v2 && ./gradlew :pipeline-application:test \
      --tests "*ExternalHandlerContinuationProofTest*"

Resultado sobre `9521d256` (BUILD FAILED in 20s, 2 tests completed, 1 failed):

    ExternalHandlerContinuationProofTest > handler_is_invoked() FAILED
    expected: <1> but was: <0>

Mensaje de aserción completo:

    an external body Step must run its registered handler exactly once; observed 0 and
    capabilities []. On 9521d256 the block node is routed straight to dispatchBody, so the
    handler is never reached and the engine substitutes its own body semantics for the
    plugin's.

El `capabilities []` es la confirmación independiente: el handler no llegó a ver siquiera el
conjunto de capabilities.

**El RED no se commitea.** Dejaría `main` en rojo, y la ley del repo exige verde en cada
commit. Un RED es evidencia, no estado final: la evidencia vive en este recibo y es
reproducible con el comando anterior; la prueba aterriza en RP035-C cuando el fix la hace
pasar. La prueba histórica de RP-033 se deja intacta: su significado es válido, lo que estaba
sobredimensionado es el recibo que le atribuía.

## Caracterización — la identidad durable que RP035-C tiene que decidir

`ExternalBodyStepDurableIdentityTest` (2 tests, 0 fallos, BUILD SUCCESSFUL in 20s) fija dos
hechos de `9521d256`:

1. Los hijos del cuerpo se journalizan con normalidad bajo el body path del run.
2. **El Step de bloque no aporta fila propia.** No hay operación, ni fingerprint, ni attempt,
   ni comportamiento de replay/divergence para el padre.

Eso importa porque RP035-C enruta un cuerpo `HANDLER_CONTINUATION` por la spine durable
registro, que le **otorga** una operación donde hoy no tiene ninguna. Y el punto marcado como
crítico es exactamente este: si el fingerprint del padre se calcula solo desde su payload,
entonces

    run 1: plugin(foo) { echo("A") }  ->  padre SUCCEEDED
    run 2, mismo runId: plugin(foo) { echo("B") }  ->  padre REUSE
                                                 ->  handler no se ejecuta
                                                 ->  la continuación no se invoca
                                                 ->  el hijo cambiado ni llega a divergencia

Por tanto el cuerpo debe formar parte de la identidad durable observable del Step dirigido
por handler. La caracterización deja el estado actual registrado, no supuesto; la elección
mecanismo (digest de estructura de cuerpo en el `OperationInput`, identidad existente, o
reutilizar el fingerprint estructural del `BlockStepNode`) se decide en RP035-C con la
caracterización a la vista.

## RED-2 y RED-3: por qué no se escriben aún

No son escribibles hoy, y escribirlos sería codificar el defecto como contrato:

- **"un handler que no invoca su cuerpo produce cero efectos"** necesita que exista el owner
  `HANDLER_CONTINUATION`. Hoy el único owner aplicable es `CANONICAL_ENGINE`, para el que la
  prueba preservada de RP-033 afirma justamente lo contrario (los hijos SÍ se ejecutan). Las
  dos afirmaciones serían contradictorias sobre la misma declaración.
- **"un cuerpo cambiado no puede reutilizarse en silencio"** necesita la ruta dirigida por
  handler, porque hoy el Step de bloque no tiene operación propia que fingerprintar.

Ambos se escriben en RP035-B (que crea el owner) y RP035-C (que crea la ruta), donde son
expresables y fallan por la razón correcta.

## Lo que este slice NO toca

- `CanonicalBodyInvokerAdapter` se queda: es la implementación engine-side. Lo que cambia es
  qué recibe el handler.
- La ruta `CANONICAL_ENGINE` queda bit-idéntica. El corte de la línea 975 se mantiene para
  ella.
- No se crea una segunda boundary de ejecución de bloque. La ruta dirigida por handler
  reutiliza la spine existente: `RegistryExecutionPreparation` -> admisión de capabilities ->
  decode tipado -> `DurableStepExecutor` -> `RegistryExecutionBoundary` -> handler.
- El handler nunca recibe `StepNode`, nunca itera hijos y nunca ejecuta el cuerpo. Solo recibe
  una continuación.

## Baseline

`9521d256` con `./gradlew check` **BUILD SUCCESSFUL in 20m 26s**. Ese es el baseline de
desarrollo de RP-035.

## Siguiente

RP035-B: enmendar ADR-0081 para que `BodyRef` sea identidad interna del engine y el plugin
reciba una continuación ya ligada; añadir `BodyExecutionOwner.HANDLER_CONTINUATION`, el puerto
`BodyContinuation` y su capability; `apiDump`. Sin comportamiento de runtime todavía.
