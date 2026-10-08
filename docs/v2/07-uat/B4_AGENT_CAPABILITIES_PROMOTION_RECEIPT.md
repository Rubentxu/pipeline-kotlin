# B4 — `agentWithCapabilities` promovido a STABLE: el trabajo que su propia condición exigía

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `01439c84`
**Qué cambia:** el conjunto de capacidades concedidas llega al resolver de targets desde la composición real del run, y el constructo deja de ser un rechazo permanente.

---

## 1. El estado del que se partía

El manifiesto de superficie llevaba `agentWithCapabilities` como **PARTIAL**, y su fila explicaba por qué con precisión: carrier, codec, resolver y rechazo fail-closed existían y estaban probados, **pero el conjunto concedido estaba vacío en la composición**, así que ningún camino de producción podía tener éxito. La condición de promoción que la fila fijaba era explícita: componer de forma genérica el conjunto realmente concedido, y **rechazar** una tabla estática de "capacidades que PipelineK puede proveer" como sustituto.

## 2. Lo que se hizo, y por qué no es un cambio de etiqueta

```text
CanonicalDurableRunCoordinator (sitio de construcción del motor BEFORE_STAGE)
  ANTES  BeforeStageDirectiveEngine(eventSink, gateContext, gateEvaluator)
         -> el resolver conserva su default: grantedCapabilities = emptySet()
  AHORA  targetResolver = LocalExecutionTargetResolver(
             grantedCapabilities = capabilityContributor.capabilities().keys)
         -> las CLAVES del RuntimeCapabilityContributor compuesto que este run ya suministra,
            la misma autoridad que consulta la frontera de ejecución
```

No se añadió ninguna lista: el conjunto genérico **ya era un concepto del run**, y lo que faltaba era que el coordinador lo pasara. Un cambio, un sitio, cuatro líneas útiles.

## 3. El testigo, y por qué sus dos filas se leen juntas

`AgentCapabilitiesTargetWitnessTest`, HF1, cruzando el **coordinador de producción** (no el motor aislado, porque el defecto vivía justo en su construcción):

```text
positiva  la composición SUMINISTRA la capacidad
          -> RunOutcome.Success · ExecutionTargetResolved presente · StageStarted presente
          -> y el MARCADOR DEL CUERPO escrito: la stage no sólo resolvió target, EJECUTÓ
negativa  la composición NO la suministra
          -> RunOutcome.Failure · ningún ExecutionTargetResolved · DirectiveDenied presente
          -> ninguna StageStarted y el cuerpo NO se ejecutó
```

La única entrada que difiere entre las dos filas es el conjunto que la composición aporta. Eso importa: la positiva sola podría pasar con un resolver degenerado en "conceder siempre"; la negativa sola podría pasar porque el constructo siguiera rechazándolo todo, que es exactamente el estado PARTIAL que se retira. Juntas no se pueden satisfacer con el defecto de la otra.

## 4. Mutación: XML fresco, no `UP-TO-DATE`

```text
M  se quita el cableado (el motor vuelve a construir el resolver por defecto)
   RED: 1 fallo de 2  "a capability the composition supplies must be granted; outcome=Failure(...)"
   la fila NEGATIVA sigue verde
   restauración verificada: sha256 de CanonicalDurableRunCoordinator.kt == b43a47fd… (idéntico)
```

Que la mutación mate **sólo** la positiva es la prueba de que el par mide el cableado y no un resolver que concede todo.

## 5. Verificación ejecutada (XML fresco, canario borrado antes)

```text
cd v2 && ./gradlew :pipeline-application:test --tests '*AgentCapabilitiesTargetWitnessTest*' \
        --tests '*S3R1*' --tests '*LocalExecutionTargetResolverTest*' --rerun-tasks
  EXIT 0    AgentCapabilitiesTargetWitnessTest 2/0 · LocalExecutionTargetResolverTest 14/0
            S3R1DirectiveDecodeBoundaryTest 6/0 · S3R1ResourceMultiplicityTest 6/0
            S3R1StageTimeoutBoundaryTest 11/0                      TOTAL 39 / 0 fallos

cd v2 && ./gradlew :pipeline-architecture-tests:test --tests '*SurfaceManifest*' \
        :pipeline-application:detekt --rerun-tasks
  EXIT 0    FArchS0SurfaceManifestTest 11/0     (la promoción es legal dentro del conjunto cerrado)
            detekt limpio
```

## 6. Contratos: qué se mantiene y qué se promueve

```text
- No se modifica ningún contrato publicado. El cambio está en un sitio de construcción del
  coordinador y usa una API existente del resolver.
- Lo que cambia es una CLASIFICACIÓN del manifiesto de superficie: PARTIAL -> STABLE, y sólo
  después de que existan la evidencia y la mutación que la sostienen.
- El resto de superficies no se toca. `agent`/`agentAny` ya eran STABLE y siguen verificadas.
- Nada de lo que antes funcionaba puede romperse: el cambio sólo puede CONCEDER donde antes se
  rechazaba, y el rechazo sigue probado por la fila negativa.
```

## 7. Lo que este recibo NO dice

```text
- No dice que `Remote` funcione: sigue CARGA Y DECODIFICA pero RECHAZA, porque no hay allocator
  remoto hasta RP-8. Eso es diseño y no cambia aquí.
- No promueve el resto del manifiesto: los otros estados no se han revisado en esta rebanada.
- No sustituye la certificación externa ni el PRODUCT-GATE (ADR-0105), que siguen donde estaban.
- No cubre el caso de VARIAS capacidades requeridas contra un conjunto parcialmente concedido
  más allá de lo que el resolver ya decidía; el testigo usa una.
```
