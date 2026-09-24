# ADR-0093 — opciones para structured runtime returns

**Estado de esta nota:** investigación no normativa. No modifica ADR-0093, ROADMAP, contratos públicos ni código.

**Fecha:** 2026-09-25  
**HEAD observado al investigar:** `839fe63f1cc39f7c036938dd09f26384492f4bec`  
**Rama:** `wu/rp-053-merge`

## Propósito y límite

Esta nota contrasta opciones de arquitectura para cerrar el hueco de runtime returns estructurados descrito por ADR-0093. Registra evidencia del código actual y una recomendación de trabajo para un primer spike. No constituye una nueva decisión normativa ni certifica que la capacidad ya funcione en la frontend estructurada.

La recomendación coincide con **B, suspend structured DSL**, ya aceptada en [ADR-0093](../04-adrs/ADR-0093-structured-dsl-runtime-return.md). Su alcance técnico debe quedar delimitado antes de cualquier implementación: ejecución `suspend` dentro de un segmento de stage, orden léxico de las invocaciones, salida tipada mediante el codec declarado por el Step, y efectos siempre detrás del coordinador durable canónico y del registry. La propuesta no introduce un `StepSpec` externo, un camino privilegiado para Steps core ni I/O ambiental.

## Hechos observados en la implementación actual

- `StageScope.steps(block)` **no existe**. `StageScope` expone únicamente el accessor `steps(): List<StepSpec>` en [`PipelineDsl.kt:1413`](../../v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt#L1413). Por tanto, no basta con cambiar una firma imaginaria de `steps(block)`.
- El `script {}` actual construye comandos en `ScriptScope` y los convierte en un único `StepSpec.Shell(..., isScriptBlock = true)` en [`PipelineDsl.kt:1398-1407`](../../v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt#L1398-L1407). Es una ruta Shell, no integración estructurada de valores tipados en el grafo eager.
- `Main` selecciona la forma scripted sólo cuando el mapper encuentra llamadas runtime-returning y la fuente **no** contiene `pipeline {}`. La condición excluyente está en [`Main.kt:699-735`](../../v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt#L699-L735). La ejecución posterior mantiene la misma autoridad durable y registry, pero la forma scripted sigue siendo generator-level en [`Main.kt:875-916`](../../v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt#L875-L916).
- `ScriptedSourceLowering` genera un entry point top-level con `override suspend fun execute(steps: ScriptedStepFacade)` en [`ScriptedSourceLowering.kt:154-169`](../../v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedSourceLowering.kt#L154-L169). Eso demuestra un seam scripted suspend, pero no demuestra integración con el cuerpo estructurado de `pipeline { stages { ... } }`.
- El lowering reescribe `readFile` y `fileExists` a llamadas con argumento vacío, y `sh(returnStdout = true)` a una forma generada, en [`ScriptedSourceLowering.kt:83-125`](../../v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedSourceLowering.kt#L83-L125). Esta adaptación sirve al frontend scripted existente. No equivale a una integración estructurada y no se resuelve cambiando sólo una firma.

## Opciones consideradas

### A — CPS / transformación de continuaciones

Ventaja: podría mantener una superficie eager aparente y transformar cada retorno en una continuación. Coste y riesgo: requiere transformar Kotlin general, incluyendo lambdas, `try/catch/finally`, loops, `when`, `break/continue` y retornos no locales. Una transformación parcial puede producir un pipeline incorrecto en lugar de un error de compilación. También reabre la decisión ya descartada de persistir o reconstruir continuaciones, complica call-site identity y mezcla una nueva autoridad de ejecución con el spine durable.

**Valoración:** rechazar como dirección de implementación. No es un ajuste local ni una ampliación segura del lowering actual.

### B — StepValue / referencias tipadas en dos fases

Ventaja: mantiene la construcción eager y podría representar una referencia tipada a una salida durable. Problema: el valor no es observable durante la construcción, por lo que no permite control de flujo real como `if (fileExists(...))` ni operaciones Kotlin sobre el resultado. Además, obliga a resolver referencias en inputs y a definir fingerprints sobre valores resueltos frente a envelopes que contienen referencias. Termina creando dos modelos de authoring y empuja los casos de decisión de vuelta a `script {}`.

**Valoración:** útil como insight de identidad `(runId, callSite, ordinal)`, pero no como API primaria para runtime returns estructurados.

### C — eager-first / direct I/O

Podría hacer que el builder obtuviera el valor de inmediato mediante `RuntimeConfig`, filesystem, proceso u otra capacidad ambiental. Es incompatible con durabilidad, replay, aislamiento y la separación DSL declarativa versus interpretación. También vuelve el resultado dependiente del proceso que compila el DSL y no del coordinador canónico.

**Valoración:** rechazar de forma explícita. No es una implementación válida del contrato.

### D — scripted standalone

Es la opción que el código ya soporta parcialmente: una fuente sin `pipeline {}` puede transformarse en un entry point `suspend` y ejecutar llamadas runtime-returning a través del seam scripted. Es útil como baseline y como evidencia de que el host puede compilar `suspend`, pero deja fuera la frontend estructurada y no debe presentarse como solución del hueco de ADR-0093.

**Valoración:** conservar como baseline de integración y caracterización, no como expansión del contrato estructurado.

### E — suspend stage-scoped compatible

La opción recomendada es hacer suspendible sólo el segmento de stage que consume runtime values. El cuerpo conserva orden léxico y control de flujo Kotlin sobre valores reales. Los Steps sin retorno pueden seguir bajando por la construcción declarativa existente; los Steps con retorno usan un seam genérico que recibe `StepKey`, input ya codificado, output codec y call-site identity. La interpretación ejecuta o reutiliza mediante el coordinador durable y el registry, no mediante acceso ambiental desde el DSL.

La forma concreta debe respetar que hoy existe `steps()` accessor, no `steps(block)`. El spike debe diseñar la frontera sobre las APIs reales y demostrar cómo conviven el segmento suspendido y la construcción eager, sin inventar una sobrecarga que el código no tiene.

**Valoración:** elegir B en términos de ADR-0093, con esta acotación stage-scoped compatible como arquitectura de integración. El primer entregable es un spike de integración, no una promesa de funcionamiento ya disponible.

## Recomendación de spike y límites

El spike inicial debe probar únicamente la integración mínima de un runtime return tipado dentro de un segmento de stage, con una operación representativa y un cuerpo que continúe en orden léxico después de recibir el valor. Debe observar la misma autoridad durable, replay, fingerprint, codec y registry que el camino canónico. Debe demostrar también el comportamiento de rechazo antes de efectos cuando falte la capability o el input sea inválido.

Quedan fuera del spike: CPS genérico, referencias `StepValue` como nueva API pública, I/O directo durante DSL construction, nuevos subtipos externos de `StepSpec`, rutas concretas por StepKey, persistencia de continuaciones y cualquier cambio que convierta `script {}` en una segunda autoridad.

El spike no debe marcar ADR-0093 como implementado o certificado. La distinción es importante: el código actual prueba un frontend scripted generator-level, no la integración estructurada stage-scoped descrita aquí.

## Gates y autorización

La ampliación a `pipeline {}` cambia semántica pública y superficie de authoring. INITIATIVE_LPR_001 §2.4 exige gate humano para cambiar semántica pública certificada, introducir una API pública incompatible, o añadir una excepción específica de plugin/coordinador. Por ello esta nota no autoriza implementación ni altera contratos.

Además, el roadmap exige RP-5 antes de expansión posterior. En el estado observado, RP-5 sigue bloqueado por el push y la verificación CI sobre los bytes exactos de la candidata. La investigación puede documentar opciones mientras permanece bloqueada, pero no debe adelantar una expansión funcional ni presentar resultados históricos como evidencia del HEAD actual.

## Referencias

- [ADR-0093 — Structured DSL Runtime Return](../04-adrs/ADR-0093-structured-dsl-runtime-return.md)
- [ROADMAP.md §7 — RP-5](ROADMAP.md#7-rp-5--gate-local-production-ready-de-main--candidata)
- [INITIATIVE_LPR_001.md §2.4](INITIATIVE_LPR_001.md#24-excepciones-que-do-require-a-human_gate)
- [`StageScope` y `steps()`](../../v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt#L1386-L1413)
- [`Main` frontend selection](../../v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt#L699-L735)
- [`Main` execution selection](../../v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt#L875-L916)
- [`ScriptedSourceLowering`](../../v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedSourceLowering.kt#L83-L169)

## Apéndice — patrones Jenkins DSL Groovy que motivan el hueco

Tres patrones reales del Jenkins DSL Groovy ejercitan exactamente el gap que ADR-0093 intenta cerrar. Se citan por su **contrato público**, no para copiar su CPS (Groovy CPS no existe en Kotlin y CPS genérico fue rechazado por ADR-0093 §3 / ADR-0006):

```groovy
// (a) pwd + sh(returnStdout=true) en control de flujo real
node {
    stage('build') {
        def work = pwd()
        def tag  = sh(script: 'git describe --always', returnStdout: true).trim()
        if (work.endsWith(tag)) {
            echo 'tag aligned'; sh 'make build'
        }
    }
}

// (b) readFile + if (fileExists(...))
stage('config') {
    if (fileExists('config/prod.yml')) {
        def cfg = readFile('config/prod.yml')
        echo "prod config: ${cfg.length()} bytes"
    }
}

// (c) withEnv + sh(returnStdout=true) — el cuerpo reabre Script
withEnv(['DEBUG=1']) {
    def v = sh(script: 'echo $DEBUG', returnStdout: true).trim()
    echo "debug=$v"
}
```

Por qué no son copiables literalmente: Groovy tiene CPS nativo (cada `step.call(...)` suspende la frame), mientras que Kotlin compila un cuerpo `suspend` ordinario y exige fijar manualmente el orden léxico eager/suspend (ADR-0093 §9, todavía abierto). Compararlos a nivel de **runtime mechanism** es inválido; compararlos a nivel de **contrato observable** (la firma Jenkins verbatim, `readFile`, `pwd`, `fileExists`, `sh(returnStdout=true)`) es exactamente lo que el catálogo Jenkins-familiar ya exige (`docs/v2/01-product/JENKINS_FAMILIARITY_CATALOG.md`, `JENKINS_REFERENCE_BASELINE.md §2`). El spike stage-scoped debe poder ejecutar los tres patrones sobre `pipeline { ... }` con el mismo fingerprint/journal/replay que el camino scripted generator-level.

## Conclusión

B es la dirección recomendada por ADR-0093, pero sólo como trabajo de integración stage-scoped suspend todavía no demostrado. El siguiente paso legítimo es un spike acotado después de RP-5 y de la autorización necesaria para el cambio semántico público.
