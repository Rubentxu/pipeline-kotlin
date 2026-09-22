# WU-RP-021 — Receipt: Catálogo de Rutas de Ejecución (RP-2)

**Fecha:** 2026-09-22 · **Base SHA:** 59a576e5 · **Head:** 9deab17f
**Tipo:** caracterización, test-side puro (cero cambios de producción)
**Suite:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ExecutionPathsCharacterisationTest.kt`

## Propósito (charter ROADMAP RP-2)

Catalogar las rutas de ejecución soportadas compilando fixtures representativos al
IR canónico (`CompiledPipeline`) y fijar la estructura observada como línea base
de caracterización. Cualquier test en rojo tras un cambio de producción indica que
este catálogo ha quedado obsoleto y debe reemitirse.

## Catálogo observado (8 rutas)

| # | Ruta | Forma IR observada | Canonical? |
|---|---|---|---|
| P1 | Declarativa lineal (echo+sh) | `StageBody.Steps`, nodos `OpaqueStepNode`, ids `build/echo-0`, `build/sh-0`, schema `dsl-v1` | SÍ |
| P2 | Multi-stage lineal | Un `StageNode` por stage DSL, en orden; **ids con prefijo del nombre de stage en minúsculas** (`a/echo-0`, `b/sh-0`, `c/echo-0`) | SÍ |
| P3 | `script { }` | **EXCEPCIÓN CARACTERIZADA:** UN único `OpaqueStepNode` `core.sh` con heredoc shell (`buildShellScript`, `set +e`). Los pasos internos NO se proyectan como hijos IR; el flujo de control Kotlin se evalúa en construcción del DSL | SÍ (vía core.sh) |
| P4 | `timeout > retry > sh` | Cadena anidada `BlockStepNode`: `core.timeout` → `core.retry` → `core.sh`×2 | SÍ |
| P5 | `dir(path) { sh }` | `BlockStepNode` `core.dir` con hijo `core.sh` | SÍ |
| P6 | `parallel { branch }` | `StageBody.Parallel` con `StageNode` por branch (aserción dual: acepta también BlockStepNode, forma observada = Parallel) | SÍ |
| P7 | Step no canónico | `analyzeCanonicalDurableExecution()` lo marca; `supportsCanonicalDurableExecution()` = false, fail-closed | RECHAZADA |
| P8 | Metadatos de stage | `agent`/`environment`/`options` proyectan a `StageNode.agent`, `.environment.values`, `.options` | SÍ |

## Excepciones/legacy registradas

1. **`script {}` → heredoc `core.sh`** (`buildShellScript`, DslCompiledPipelineCompiler L553):
   los pasos estructurados internos no legibles como shell se rechazan con
   `IllegalStateException` (fail-closed, FIND-DV-DUPL-01). El heredoc usa `set +e`
   (continúa en error) con propagación de código de salida. Es la degradación
   legacy documentada; NO proyecta IR tipado de los pasos internos.
2. **Prefijo de step id en minúsculas**: `Stage("A")` produce `a/echo-0`. Convención
   observada, no contractual; fijada aquí para detectar cambios accidentales.
3. **`canonicalBodyStepIds` deriva de `StepDescriptorRegistry.standard()
   .bodyStepIds(BodyExecutionOwner.CANONICAL_ENGINE)`** — confirmado que no existe
   lista hardcodeada de StepKeys en el gate (revisado en HEAD).

## Cobertura de cancelación/restart (nota de alcance)

Cancelación y restart se ejercitan a nivel de coordinator/journal en suites
existentes (CanonicalDurableRunCoordinatorTest, RetryAcceptanceMatrixTest,
WULpr302RetryEngineTest, WindowCRetryRecoveryProductionWiringTest). Este WU fija
la forma IR de admisión, no la semántica runtime de restart, que ya tiene
caracterización propia. No se duplica.

## Evidencia

- L1: 8/8 PASS (primera pasada 6/8, 2 fallos de caracterización que FIJAN
  comportamiento observado, no bugs: P2 prefijo minúsculas, P3 heredoc).
- L4: `:pipeline-application:test` BUILD SUCCESSFUL 17m 19s; 1716 tests,
  0 fallos, 0 errores (115 skipped preexistentes).
- Commit: 9deab17f.

## Criterio de salida del charter

- [x] Rutas declarative/scripted/durable/in-memory catalogadas (P1-P6, P8; in-memory
  vs durable comparten el MISMO spine LF-0208, sólo cambia el almacenaje, verificado
  en Main.kt L381-445).
- [x] Excepciones/legacy registradas (script{}→heredoc; catchError/warnError LEGACY_LINEAR).
- [x] Fail-closed admission verificada (P7).
- [x] Test-side puro, cero cambios de producción.

## Cierre

```text
Reference implementation consulted:  Jenkins declarative/scripted (baseline doc, no código copiado)
Behaviour adopted:                   catálogo de formas IR observadas, no modificadas
Intentional deviations:              ninguna (solo observación)
Security implications reviewed:      n/a (test-side)
Tests demonstrating the contract:    ExecutionPathsCharacterisationTest (8 tests)
```
