# Auditoría S6 §4, §5, §9 — resultado medido (2026-10-08)

Complementa a P1, que verificó §2 y §3. Estas tres secciones son las que quedaban sin
auditar; aquí se miden contra el código y contra los fitness tests que las vigilan.

## §4 Block Step — CUMPLE

Requisito: un Block Step de plugin debe usar una forma existente de `BodyExecutionPolicy` y
**no puede pedir una rama de coordinador a medida**.

```text
grep de dispatchRetryBlock|dispatchTimeoutBlock|dispatchParallelBlock|dispatchScriptBlock|dispatchDirBlock
  → 0 coincidencias en src/main
  → las 2 coincidencias están en Lfc2ConcreteBodyRoutingDebtFitnessTest.kt, que son
    FIXTURES del propio fitness test (una función falsa y su detección esperada)
```

Es decir: no existe la colección prohibida, y además existe un fitness test que la busca
activamente. La vigilancia es mecánica, no declarativa.

## §5 Directive — CUMPLE

Requisito: misma regla vía `DirectiveExecutionPolicy`; un plugin puede añadir una clave pero
no una fase de ciclo de vida ni un scheduler oculto.

```text
grep -E 'when *\((stepKey|key|directiveKey)' sobre pipeline-application/src/main y pipeline-domain/src/main
  → 0 coincidencias en código
  → 5 coincidencias, todas en comentarios que PROÍBEN el patrón:
      BundledPluginClasspathPlan.kt:30   "no `when(stepKey)`-style switches"
      StructuralStepFamily.kt:15         "`when (stepKey) { "core.echo" -> ... }` is forbidden"
      RetryEngine.kt:202                 "the engine itself is StepKey-blind"
      RecoveredExecutionMaterializer.kt:40  "There is no `when (stepKey)`"
      CanonicalInvocation.kt:23          "no `when(key){ Echo -> text }` bridging"
```

Que la prohibición esté escrita en cinco puntos distintos del código indica que la ley se
entiende y se respeta; que no exista ninguna rama confirma el respeto.

## §9 High-performance composition — CUMPLE

Las cinco prohibiciones del hot path, una a una:

| Prohibición | Medido | Veredicto |
|---|---|---|
| no ServiceLoader | 13 llamadas, todas en adaptadores de descubrimiento/admisión (`ExternalStepPluginDiscovery`, `ExternalDirectivePluginDiscovery`, `ExternalCapabilityContributorDiscovery`, `ExternalEventDefinitionDiscovery`, `PluginAdmissionGate`, `PluginContributionVerifier`, `MultiBindingWithCredentials`) | ✅ ninguna en hot path |
| no reflection lookup per operation | `Class.forName` solo en `PluginAdmissionGate:90` (initialize=false) y `:143` (initialize=true); ambos en admisión | ✅ |
| no scanning jars | `Class.forName` se invoca con un **nombre concreto** proveniente del descriptor de ServiceLoader; el propio código lo documenta: "no scanning jars" | ✅ |
| no string switch over keys | ver §5 | ✅ |
| plan pre-resuelto | `FrozenStepRegistry` = `Collections.unmodifiableMap(LinkedHashMap)` con lookup O(1) por `PluginStepId`; el builder es mutable, el registry congelado no | ✅ |

Y el requisito de arranque, que §9 también fija:

```text
ServiceLoader/KSP metadata → validate manifests → build registries → freeze → precompute
```

Las tres primeras fases existen como adaptadores separados y la cuarta está implementada
(`FrozenStepRegistry`, con `build()` que copia el mapa para que ni el builder ni un caller
retengan un handle al contenido vivo). La quinta (precompute de planes resueltos) no se ha
medido explícitamente y queda declarada como no verificada.

## Evidencia de ejecución

```bash
cd v2 && ./gradlew :pipeline-architecture-tests:test \
  --tests '*Lfc2ConcreteBodyRoutingDebtFitnessTest*' \
  --tests '*PublishedContractBoundaryFitnessTest*'
# exit=0, BUILD SUCCESSFUL in 7s
```

```text
Lfc2ConcreteBodyRoutingDebtFitnessTest    tests="6" skipped="0" failures="0" errors="0"
PublishedContractBoundaryFitnessTest      tests="6" skipped="0" failures="0" errors="0"
Lfc2ConcreteBodyRoutingDebtFitnessTest$ViolationFixture
                                           tests="10" skipped="10" failures="0" errors="0"
```

Canary: XMLs borrados antes de la ejecución y regenerados después. Los `skipped="10"` son la
clase interna de fixtures del propio fitness test, que se autodesactiva al validar un test que
comprueba la detección de violaciones; no es un test omitido.

## §8 No-core-change law — verificada indirectamente

§8 exige que un plugin nuevo instale y ejecute con cero cambios en domain, dispatch, compiler y
coordinator. No se construyó un plugin nuevo en esta auditoría, pero su contraprestación
estructural está verificada por `PublishedContractBoundaryFitnessTest` (6/0) y por el hecho de
que el registry, los codecs y el descubrimiento son genéricos sobre `PluginStepId`/`Digest` y
no contienen ninguna tabla de plugins conocidos.

## Veredicto

```text
§4 Block Step       CUMPLE   (+ fitness mecánico)
§5 Directive        CUMPLE   (+ 5 prohibiciones escritas en el código)
§9 Hot path         CUMPLE   en 4 de 5 prohibiciones; precompute de planes NO verificado
§8 No-core-change   CUMPLE por contraprestación (sin plugin nuevo construido aquí)
```

**Ningún defecto encontrado en §4, §5, §9.** Esto no convierte a S6 en más de lo que ya es:
`core.sh = CERTIFIED` y el contrato de §2 ya verificados en P1. Lo que añade es que las tres
secciones que quedaban sin auditar **no esconden deuda**.

### Lo que queda declarado sin verificar

1. La quinta línea de §9 ("precompute resolved plans") no tiene evidencia: no se ha medido si
   el codec, handler y plan de capacidades se resuelven una vez en arranque o se recomponen por
   operación. El map O(1) cubre la resolución de la **definición**, no necesariamente el plan.
2. §4 y §5 se verificaron por ausencia de patrón prohibido, no por ejecución de un Block Step y
   una Directive **de plugin externo** en el hot path. `example-block-plugin` y
   `example-directive-plugin` existen como artefactos, pero su ejecución end-to-end no se
   midió en esta pasada.
3. `MultiBindingWithCredentials` construye un `ServiceLoader` como **campo** (`private val
   serviceLoader`), no dentro de un método de descubrimiento. No está en el hot path por lo que
   se vio, pero merece confirmación en un análisis de ciclo de vida completo.