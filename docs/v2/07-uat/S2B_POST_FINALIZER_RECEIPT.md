# S2-B — `post` real: recibo de slice

- **Ciclo**: TRAIN S2 (directivas de stage → semántica real)
- **Predecesor**: S2-A `when` (`e0e32aba`, en `origin/main`)
- **Estado**: implementado y verificado (unidad, eventos, integración in-process, mutación, BCV, detekt, manifiesto S0)
- **Base**: `e0e32aba`
- **Nota SDDK**: sin ciclo activo en `sddk cycle status` (almacén con ciclos rancios); trabajo autorizado explícitamente por el operador ("continuamos"). Estado SDDK registrado honestamente como divergente.

## Qué se certifica

S1/S2-A dejaron `post` como rechazo fail-closed en construcción (WU-RP-032/DSL-008).
S2-B lo convierte en **finalizadores reales**: un `post { always/success/failure/
unstable/aborted/unsuccessful/cleanup { } }` compila a IR tipado, se selecciona por el
outcome real del stage y sus nodos se ejecutan por el mismo spine canónico que los
pasos del stage.

## Decisiones de diseño

### 1. Nodos reales, no referencias

El plan de post lleva `Map<PostCondition, List<StepNode>>` — los nodos mismos, no
`BodyRef`. No existe un registro `BodyRef -> nodo` al que resolver; una referencia sin
binding sería exactamente el camino de fake-reference que la Step Constitution
prohíbe. `PostSpec.toPostPlan()` es proyección pura sin argumentos.

### 2. Outcomes de stage como ADT cerrado, mapeo total fail-closed

`StageOutcome` = Succeeded | Unstable | Failed | Aborted | Skipped. El puente desde el
vocabulario de eventos (`StageFinished.outcome`) es `PostCondition.outcomeOf(String)`,
TOTAL y cerrado: un string desconocido devuelve `null` y el coordinador falla con
`PipelineFailure(INFRASTRUCTURE)` antes de seleccionar finalizador alguno. Un outcome
ilegible nunca se lee como success.

### 3. Orden versionado, no orden de hash

`PostCondition.EXECUTION_ORDER` = ALWAYS → SUCCESS → FAILURE → UNSTABLE → ABORTED →
UNSUCCESSFUL → CLEANUP. El planner camina esa lista: la misma declaración produce la
misma secuencia de finalizers en cada run, en cada máquina. CLEANUP es último sin
excepción; ALWAYS es primero.

### 4. UNSUCCESSFUL excluye Skipped

`UNSUCCESSFUL` = Failed ‖ Unstable. Un stage saltado no fue "unsuccessful": no
ocurrió. ALWAYS/CLEANUP disparan para todo outcome, incluido skipped.

### 5. Dispatch canónico con identidad durable propia

Cada nodo de post se despacha por `dispatch()` con `bodyPath =
BlockSegment("post:<CONDITION>:<index>")` y `stepIndex = POST_BASE_STEP_INDEX (1000) +
n`. El DSL limita el cuerpo de un stage a 512 pasos, así que no hay colisión de OpId
con los pasos normales del stage.

### 6. Un fallo de finalizador aborta el run (typed USER)

Si un finalizador seleccionado no termina en Success, el run aborta con
`PipelineFailure(USER, "post <COND> finalizer of stage '<stage>' failed")` y los
finalizadores restantes no continúan (sin StageFinished en ese camino).

## Evento nuevo: PostConditionSelected

`PostConditionSelected(stageIndex, stageName, stageOutcome, selectedConditions,
skippedConditions)` — la decisión pura hecha observable. Se emite UNA vez por stage
con post no-inerte, ANTES de `StageFinished`, y sus listas salen de la misma
proyección del planner que gobierna la ejecución: el evento y los pasos no pueden
discrepar.

Ceremonia completa: `DomainEvent.kt`, `EventJsonWriter` (+ helper `jsonStringList`),
`JsonEventLog` (bracket-scanner `stringListField`), `InMemoryEventStore`,
`SqliteEventStore`, `EnvelopeProjector`, `SequenceAssigner`. Los dos invariantes de
variantes (FArchL7DomainEventExhaustivityTest, DomainEventRoundTripTest) suben 54→55.

## Defectos REALES cazados en este slice (no fueron parte del plan)

1. **Lector de eventos escape-ciego (pre-existente).** `EventJsonFields.stringField`
   devolvía el lexeme crudo SIN desescapar (`\"`, `\\`, `\n`, `\r`, `\t`): cualquier
   comilla en cualquier campo de cualquier evento corrompía el round-trip del journal.
   Además `stringListField` envolvía el elemento en una clave sintética SIN comillas
   (`"x:$raw"`), con lo que `stringField` nunca encontraba la clave y TODA lista
   decodificaba a `["", "", ...]`. El round-trip dedicado del evento nuevo destapó
   ambos: `unescape()` en el punto único de retorno, clave sintética `"k"` entrecomillada.
   pipeline-events: 215/215.

2. **`post { failure {} }` era código muerto.** Las ramas `Abort` del coordinator
   (lineal y paralela) retornaban sin pasar por `runPostBlock`: el caso de uso EXACTO
   de `post { failure { ... } }` (un paso del stage falla) no ejecutaba finalizador
   alguno. Ambas ramas ahora corren el post con outcome "failed" antes de abortar; el
   motivo original de abort gana salvo que el finalizador falle él también. Cazado por
   el test de integración `stage failure selects FAILURE and UNSUCCESSFUL but not
   SUCCESS` (NoSuchElement: cero eventos PostConditionSelected).

3. **La ruta skip perdía el entorno del stage.** El camino skip pasaba `shOptions`
   crudo a `runPostBlock`; un stage con `environment { }` saltado entregaba a sus
   finalizers un entorno sin el env del stage. Ahora: `stage.projectShellOptions(shOptions)`
   (sin workspace creado, que es correcto: el stage nunca corrió).

## Gate Teeth (mutación de la capa cuya autoridad se certifica)

Sobre `PostPlan.kt` (autoridad pura), cada mutante aplicado → corrida → restaurado:

| Mutación | REDs |
|---|---|
| `ALWAYS` deja de disparar incondicionalmente | 4 |
| `CLEANUP` promovido a primero (reorden) | 4 |
| `UNSUCCESSFUL` se traga `Skipped` (`!= Succeeded`) | 5 |
| Outcome desconocido falla ABIERTO (→ Succeeded) | 6 |
| `selectedConditions` lista condiciones sin cuerpo declarado | 7 |

Restaurado: cero residuo, 12/12 verdes (`PostPlannerTest`, S2B-POST-001..012).

## Suites ejecutadas

| Suite | Resultado |
|---|---|
| `pipeline-domain` PostPlannerTest (S2B-POST-001..012) | 12/12 |
| `pipeline-events` módulo completo | 215/215 (incl. `PostConditionSelectedEventRoundTripTest` nuevo, 3 tests: escapes, igualdad exacta, lista vacía) |
| `pipeline-scripting-api` módulo completo | 83/83 (incl. `PostDslCompilesToSpecTest` nuevo, 3 tests; sustituye a `PostDslFailClosedTest`, obsoleto por diseño) |
| `S2BPostCoordinatorIntegrationTest` (nuevo, in-process, coordinator REAL) | 4/4: dispatch por el spine + orden evento<StageFinished; abort USER tipado; selección FAILURE/UNSUCCESSFUL en fallo de stage; ALWAYS con ShOptions reales |
| UatEvt001, UatEvt002, ErrorHandlingTest | 12/12 |
| Lfc2WaitUntilCanonicalReentryFitnessTest, UatDsl003ParallelTest | 13/13 |
| `FArchS0SurfaceManifestTest` | verde (fila post actualizada) |
| detekt (domain, events, application, scripting-api) | verde |
| apiCheck domain + events (dumps regenerados) | verde |
| gate de ronda `check` (L5, incremental, 19m41s) | 3170 tests, 3 fallos en 3 clases: `PostDslFailClosedTest` (obsoleto por este slice: esperaba el rechazo fail-closed que S2-B elimina; sustituido por `PostDslCompilesToSpecTest`, re-ejecutado verde) y `Lfc0GlobalStateFitnessTest`/`FArchM3CanonicalTaskRuntimeTest` (ambos contra `SourceProvenanceProbe.kt`, intacto desde `555a2818` P3, ajeno a este slice y ya roto en HEAD antes del cambio) |

## Manifiesto S0

`docs/v2/surface/DSL_SURFACE_MANIFEST.md` línea 53: `post` pasa de
UNSUPPORTED_FAIL_CLOSED (IllegalStateException en toStageBuilder) a
DECLARATIVE_DIRECTIVE / STABLE con la trazabilidad completa del lowering. El
machine-check I5 ya no fija `post` como stub (quedó fuera en S2-A); sigue fijando
`agent`.

## Fronteira de responsabilidad

Las UAT de binario instalado para `post` pertenecen al harness externo (regla 1 de la
frontera). Este slice entrega la implementación + integración in-process + recibos; el
`@Disabled(migrated-pending)` correspondiente se registra cuando el catálogo de
movimientos se confirme con el operador.

## Referencia externa

- **Referencia consultada**: Jenkins `post` section (Declarative Pipeline), comportamientos ALWAYS/SUCCESS/FAILURE/UNSTABLE/ABORTED/UNSUCCESSFUL/CLEANUP, orden de ejecución documentado (cleanup last).
- **Comportamiento adoptado**: condiciones cerradas + ALWAYS primero + CLEANUP último + UNSUCCESSFUL = Failed‖Unstable.
- **Desviaciones intencionales**: fallo de un finalizador aborta el run (Jenkins marca el build fallido de forma equivalente); Unverifiable no aplica aquí; `aborted` como outcome de stage (Jenkins lo da a nivel build; aquí el stage lo puede emitir).
- **Seguridad**: sin nuevas capacidades; los finalizers usan las mismas capacidades declaradas de sus Steps.
- **Tests que demuestran el contrato**: `PostPlannerTest`, `PostConditionSelectedEventRoundTripTest`, `S2BPostCoordinatorIntegrationTest`.

## Siguiente

S2-C/S2-D según ROADMAP. El gate de ronda ejecutado para este slice dejó dos fallos
pre-existentes (arch fitness contra `pipeline-release`, ajeno al slice) documentados
arriba; no se toca `pipeline-release` desde aquí (no V1 repair on the V2 critical
path) y la corrección pertenece a su propio WU.
