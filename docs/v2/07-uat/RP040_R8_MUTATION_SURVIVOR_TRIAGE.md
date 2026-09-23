# WU-RP-040 R8 — Mutation Testing Surviving-Mutant Triage

**Fecha:** 2026-09-23
**Base SHA:** `6f7c445f` (HEAD al inicio del slice; evidencia de mutants generada en R4 sobre la misma base)
**Alcance:** `:pipeline-domain` (target `dev.rubentxu.pipeline.v2.domain.durable.*`) y `:pipeline-step-sdk:runtime` (codecs/policies selectivos).

## Totales observados

| Módulo | Mutantes | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT |
|---|---|---|---|---|---|
| pipeline-domain | 762 | 320 | 118 | 322 | 2 |
| pipeline-step-sdk:runtime | 20 | 10 | 10 | 0 | 0 |
| **Total** | **782** | **330** | **128** | **322** | **2** |

Mutation score (killed/(killed+survived), excluyendo NO_COVERAGE): domain 73,1 %; runtime 50 %.

## Triage de los 128 supervivientes

### Categoría A — data-class equals/hashCode estructurales: 44 (pipeline-domain)

`FailureRecord`, `OperationInput(+$Companion)`, `RetryChildRowSnapshot`, `RetryControlIdentity`,
`WaitUntilControlRowSnapshot`, `WaitUntilControlIdentity`, `WaitUntilReconciliationInput`,
`OperationOutput`, `ParallelAggregateId`, `TaskSpec$ExecTask`.

`RemoveConditionalMutator_EQUAL_ELSE` sobre ramas de `equals`/`hashCode` generadas por el
compilador para data classes. Clasificación: **deuda de test menor, no defecto**. Cubrir cada
rama de equals de 10 data classes aporta poco valor de detección real; el coste/beneficio es
desfavorable. Decisión: documentar y NO escribir tests de rimado de equals campo a campo.
Reevaluar solo si una data class gana semántica de igualdad no trivial.

### Categoría B — reconcilers de control (lógica dura): 45 (pipeline-domain)

`RetryReconciler.reconcile` (19) + `reconcileLegacyOrFresh` (8), `ParallelReconciler.reconcile`
(8) + `reconstructBranch` (8), `WaitUntilReconciler.reconcile` (5) + `computeNextBackoff` (1).

`RemoveConditionalMutator` sobre guardas de reconciliación. Estos flujos están cubiertos por
UAT de reinicio/reanudación (R1–R6, HF3) que ejecutan procesos reales, NO por los unit tests
que pitest usa como target. Clasificación: **gap de test unitario conocido y aceptado**: la
evidencia de corrección vive en receipts R1–R6 (commits caa0b497, aae1acb1), no en el corpus
unitario. Decisión: no duplicar la batería UAT como unit tests para cazar mutantes;
documentar como deuda D-005 (P3) si se quiere subir el score unitario-local.

### Categoría C — política de replay: 10 (runtime)

`DefaultEffectReplayPolicy.decide`, todas `RemoveConditionalMutator_EQUAL_ELSE`.

Contrastado con la fuente (`EffectReplayPolicy.kt` L71–L110): las ramas son
`replayPolicy == ReplayPolicy.RERUN/NEVER/MEMOIZED`, membership de `Effect.ABORTS_PIPELINE`/
`READ_ONLY` y checks de `OperationStatus.SUCCEEDED`. La matriz E-EM-11 NEVER-1 está cubierta
(y mata sus mutantes); los supervivientes corresponden a combinaciones MEMOIZED×efectos no
ejercitadas (p. ej. MEMOIZED con journal entry NO_SUCCEEDED y effects mixtos). Clasificación:
**gap real de test, acotado y barato de cerrar**. Acción: ampliar la tabla de tests de
`DefaultEffectReplayPolicy` con las combinaciones MEMOIZED restantes. Prioridad P2. Se deja
como WU de seguimiento (no bloquea R5/R6/R7).

### Categoría D — serialización/fingerprint/helpers: 29 (pipeline-domain)

`Fingerprint$Companion.compute` (5, incl. VoidMethodCall sobre orden de feeds del digest),
serializers (`FailureRecord$$serializer.deserialize`, `FingerprintPayload$$serializer.serialize`),
`RecordingDurableTaskRuntime` (3), `OperationStatus.transition`/`isPollFailure`, getters triviales
(`getQuiet`, `getExitCode`, `getReason`), `DivergenceException.buildErrorMessage`, constructores
de validación (`RetryReconciliationInput`, `TaskExecutionResult`).

Clasificación heterogénea: mayoritariamente **mutantes equivalentes o de bajo valor** (void calls
en fingerprint con digest que ya se testea por igualdad de valor; getters que devuelven campo
final). Los de validación de constructores (4) son de valor medio. Decisión: sin acción
inmediata; se registra la categoría para un futuro burn-down selectivo.

## Resumen de decisiones

| Cat | N | Decisión |
|---|---|---|
| A | 44 | Aceptado como deuda documentada (equals/hashCode de data classes) |
| B | 45 | Cubierto por UAT R1–R6 con procesos reales; no duplicar como unit tests |
| C | 10 | Gap real, P2: tabla MEMOIZED de DefaultEffectReplayPolicy (WU siguiente) |
| D | 29 | Equivalentes/bajo valor; sin acción; reevaluar en burn-down futuro |

Ningún superviviente indica un defecto de producción abierto. Los 322 NO_COVERAGE del dominio
son clases fuera del corpus unitario actual (también cubiertas por UAT); no se contabilizan
como fallos.

## Referencias

- Receipt del WU: `docs/v2/07-uat/WU_RP_040_RECEIPT.md` (R4 generó los informes pitest sobre los que se basa este triaje)
- Bug upstream Kover #798 (contexto de infra, no de mutantes)
- E-EM-11: `docs/v2/07-uat/E_EM_11_CLOSURE_RECEIPT.md`
