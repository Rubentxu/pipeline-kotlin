# WU-091 `core.lock` — Informe de verificación (RP6-A, ciclo `rp6a-lock`)

> status: VERIFIED sobre el árbol `659e1caa`
> receipt de implementación: `docs/v2/07-uat/WU091_LOCK_RECEIPT.md`
> artefactos SDDK: specification `art-f2a583b5e381-ce22c3ae`,
> design `art-5d0e7e7716a1-bd3b9134`, implementation-plan `art-b3e5ca8c6aa2-6006abdc`,
> implementation-receipt `art-d2c4b797d407-02e5776f`

## 1. Qué se verificó

`core.lock` como Step del catálogo Jenkins, nacido detrás del registry, con
recorrido completo desde el fichero `.pipeline.kts` hasta el `LockCoordinator`,
y con sus nueve leyes de exclusión probadas contra la distribución real.

## 2. Evidencia de tests

```text
v2/gradlew :pipeline-scripting-api:test :pipeline-events:test
            :pipeline-application:test :pipeline-architecture-tests:test

2021 tests, 0 failures, 0 errors, 121 skipped
BUILD SUCCESSFUL in 21m 7s
```

| Suite | Contenido | Resultado |
| --- | --- | --- |
| `UatLockBlockDurableTest` | WL-L1..WL-L9 contra el CLI real (HF2) | 9 / 0 fallos |
| `CoreLockStepContractTest` | contrato, superficie, coherence, cruce de presupuesto | 21 / 0 |
| `FileLockCoordinatorTest` | registro de hold, POSIX, cancelación, muerte de proceso | 28 / 0 |
| `DslCompiledPipelineCompilerTest` | lowering y autoridad de wire (4 casos nuevos) | 0 fallos |
| `Lfc2LockWireAuthorityFitnessTest` | el compilador no escribe el wire de lock | 3 filas verdes |
| `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` | `Lock` en la familia cerrada de bloques | verde |
| `FArchS0SurfaceManifestTest` | gobernanza de superficie DSL | verde |
| `FArchL7DomainEventExhaustivityTest` | 61 variantes de evento | verde |
| `DomainEventRoundTripTest` | round-trip de los 5 eventos lock por el log durable | verde |
| `CoordinatorGrowthGuardrailTest` | ratchet del coordinador en 552 | verde, sin tocar |

## 3. Mutaciones (la ley es load-bearing)

| ID | Mutación | Medición |
| --- | --- | --- |
| M4..M8 | decodificación, denegación, owner dentro de `HoldKey` | RED (characterization) |
| M9 | owner por run en vez de `ExecutionLaneId` | 6 / 28 RED |
| M10 | espera bloqueante en vez de cooperativa | 1 RED (51ms contra 50ms) |
| M-§4 | replay RERUN → SKIP | **9 tests / 1 failed, exactamente WL-L9** |

## 4. Cumplimiento de política arquitectónica

```text
runtime no depende del StepSpec del DSL .................. sí
ningún switch por StepKey en el coordinador (552 líneas) . sí, ratchet intacto
KSP sin when(stepName) ................................... sí
ningún retorno placeholder declarativo .................. sí
sin mutación global de cwd/env ........................... sí
capacidad declarada == capacidad usada ................... sí (5 declaradas, 5 usadas)
un plugin externo añade un Step sin tocar core ........... sí (sin cambios en core)
Step completado sólo tras LEGACY_REMOVED ................. N/A: nació en Registry
```

## 5. Clasificación G3.6

`SOURCE_ADDITIVE_WITH_EXHAUSTIVE_WHEN_RISK`. Tres consumidores exhaustivos
encontrados y arreglados: `BlockStepFlattener` (no compilaba),
`PipelineDslSealedHierarchyTest` (pin 31→32) y `FArchS0SurfaceManifestTest`
(fila de manifiesto). `pipeline-scripting-api` está fuera de `apiCheck`, luego no
cambia ningún dump `.api` baselined.

## 6. Defecto encontrado y corregido durante la verificación

El deadline de un bloque se hace cumplir **sólo** por el watchdog del shell hijo,
de modo que un Step que se suspende sin lanzar un proceso lo escapaba. Medido con
el CLI real: `timeout(2, "SECONDS") { lock("db") { … } }` esperaba
indefinidamente, adquiría el recurso al soltarlo el holder y ejecutaba su cuerpo
**después** del deadline. Corregido con `EXECUTION_BUDGET_CAPABILITY` y un cruce
puro `LockIntent.withBudget`, sin tocar el motor ni el ratchet.

## 7. Deuda registrada, no resuelta aquí

`DEBT-WIRE-AUTHORITY` — backlog `bl-bl-01M3YGH3FE000387X13PG607G0`, prioridad P2,
estado Triaged. `StepSpec.Dir`, `WithEnv`, `TimeoutBlock`, `RetryBlock` y compañía
siguen autorando su wire a mano en `DslCompiledPipelineCompiler`. Es otro
evolutivo horizontal, posterior a RP6-A.

## 8. Lo que este informe NO afirma

- No se ha ejecutado CI remoto para estos SHAs; la certificación es local sobre
  el árbol `659e1caa`.
- No se ha modificado journal, replay ni semántica del spine.
- `core.lock` no cubre el catálogo de recursos ni la cola ordenada de Jenkins
  (`label`, `quantity`, `variable`): son requisitos de RP-8 y están declarados
  fuera de alcance en SPEC_WU091_LOCK.md §1.
