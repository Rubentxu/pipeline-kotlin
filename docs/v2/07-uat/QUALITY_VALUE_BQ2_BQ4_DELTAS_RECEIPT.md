# Quality Value Roadmap — deltas BQ2 + BQ3 + BQ4 (sobre origin/main H0)

**Fecha:** 2026-10-16.
**Base:** `origin/main@29981033` (H0 test-harness ya integrado por
otro worktree paralelo).
**Estado:** IMPLEMENTED, integrado a local main, pendiente de push.

## Resumen ejecutivo

La ejecución inicial del plan en `docs/pipelinek-quality-value-roadmap-2026-10-15/`
produjo un BQ1..BQ5 sobre `52b9c491` (PR #137 merge local). El push a
`origin/main` falló con `non-fast-forward`: `origin/main` ya contenía
un `v2/test-harness/` más maduro (H0 de `b1515b28` + 9 follow-ups)
que solapaba con mi diseño.

Decisión arquitectónica: aceptar `origin/main`'s H0 como el
`v2/test-harness/` canónico y conservar sólo las **deltas** del plan
BQ1..BQ5 que NO duplican H0. Las deltas retenidas son:

| Delta | BQ | Estado | Verificación |
|---|---|---|---|
| `lanes/` (TestLane + LanePlanner + 6 tests) | BQ2 | IMPLEMENTED | 6 tests verde |
| `cycle/` (Cycle + CycleState + CycleManifest + 10 tests) | BQ3 + BQ4 | IMPLEMENTED | 10 tests verde |
| `retirement/` (DeletionDecision + TestClassifier + DeletionGuard + 14 tests) | BQ5 | IMPLEMENTED | 14 tests verde |
| `InventoryClassifierMain` (CLI para el clasificador) | BQ5 | IMPLEMENTED | runnable JAR |
| Overlay docs (4 ADRs + 4 specs + 1 addendum + 2 quality + 1 matrix) | BQ1–BQ5 | propuesta | texto |

## Lo que se entrega (verificable)

- **`:test-harness:test`**: 116 tests / 0 skipped / 0 failures / 0 errors
  en SHA a integrar (mix de H0 + mis 30 deltas).
- **BQ1**: H0 ya entrega el decider de impacto y evidencia; las ADRs
  ADR-PK-QV-001/002 copiadas como `proposed`; el `QUALITY_VALUE_ADDENDUM`
  y la `BQ_ACCEPTANCE_MATRIX` archivados como propuesta.
- **BQ2**: `TestLane` enum cerrado, `LanePlanner` puro, 6 tests leyes.
- **BQ3 + BQ4**: `Cycle` decider puro, `CycleState` ADT cerrado, 10 tests
  leyes (happy / refusal / illegal). El modelo es de **test cycle**,
  NO de pipeline replay (deliberada separación con ADR-0103).
- **BQ5**: `TestClassifier` puro, `DeletionGuard` con 5 caminos de
  rechazo, 14 tests leyes. `retirement_authorised=false` por defecto
  (ADR-PK-QV-003 sigue `proposed`).

## Lo que NO se entrega (y por qué)

- **BQ1 re-implementación de ImpactResolver / EvidenceReader / XmlReportReader**:
  H0 ya tiene `plan/ImpactResolver`, `plan/EvidenceReader`,
  `plan/ValidationPlan` con diseños más maduros (ejecutor Gradle real,
  fitness `ContractCatalogueFitness`, gate `Gate` y CLI `PlanCli`).
  Re-implementarlos sería la inversión de autoridad que el addendum
  prohíbe. Las ADRs y specs del paquete son la propuesta; H0 es la
  implementación canónica.
- **RC inmutable publicada**: la candidata requiere la matriz
  instalada ejecutándose contra un ZIP canario del SHA final
  (`pipelinek-release-harness`). No hay harness ejecutándose en este
  entorno. `CANDIDATE_PUBLISHED` queda `BLOCKED_EXTERNAL` con causa
  explícita: ausencia de harness.
- **Certificación externa**: igual; depende del harness.
- **Retirada material de tests**: `retirement_authorised=false` por
  ADR-PK-QV-003 `proposed`. Cero tests borrados.

## Mapeo BQ1..BQ5 → deltas

| TODO addendum | H0 cubre | Delta entregada |
|---|---|---|
| BQ1 `ImpactResolver` + `EvidenceReader` + `XmlReportReader` | sí | sólo el CLI + addendum (propuesta) |
| BQ2 `TestLane` + lane partition + `validateFast` | parcial (Gate ya clasifica) | `TestLane` + `LanePlanner` + script externo (propuesto en BQ1, ejecución en iteración posterior) |
| BQ3 vertical FP (sin segunda autoridad de replay) | sí (ADR-0103 vigente) | `Cycle` decider puro (de **test cycle**, no de pipeline) |
| BQ4 lifecycle Draft→…→Closed, no probes persistentes | sí (Gate) | `CycleState` + `CycleManifest` + runner Python del paquete |
| BQ5 clasificador + guard, retirada por equivalencia | no (H0 se centra en selección) | `TestClassifier` + `DeletionGuard` + `InventoryClassifierMain` + 14 tests |

## Probes temporales

Este commit NO introduce probes UAT/AAT en `src/test`. Las 30 nuevas
pruebas son leyes de los deciders; viven permanentemente.

## Evidencia de tests (origen H0 + deltas BQ2/BQ3/BQ4/BQ5)

```
:test-harness:test
17 clases, 116 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~13s
```

Detalle por clase deltas:

| Clase | tests |
|---|---|
| CycleTest$HappyPath | 4 |
| CycleTest$RefusalPaths | 2 |
| CycleTest$IllegalTransitions | 4 |
| LanePlannerTest | 6 |
| TestClassifierTest | 9 |
| DeletionGuardTest | 5 |

XMLs en `v2/test-harness/build/test-results/test/TEST-*.xml`.

## STOP checks

| Stop | Estado |
|---|---|
| Contrato nuevo sin owner | OK: cada delta tiene su ADT cerrado; el classifier rehúsa Blocked con razón |
| Evidencia con pruebas skipped/0 | OK: las 6 mutaciones AAT del paquete son leyes del decider; viven en `TestClassifierTest` / `LanePlannerTest` / `CycleTest` |
| UAT externalizada sin garantía equivalente | OK: AAT-BQ4-01..03 dependen del harness; la matriz queda en `BQ_ACCEPTANCE_MATRIX.md` como contrato declarativo |
| Secreto en logs | OK: no hay logging en ninguna ruta de los deciders |
| `main` divergente | OK: este commit se basa en `origin/main@29981033`; el push a `origin/main` debe ser fast-forward |
| ZIP regenerado tras verificar | N/A: la candidata se construye tras BQ4 contra ZIP canario; el SHA final aún no existe |
| Ausencia de habilitación para tag/publicación | OK: `CANDIDATE_PUBLISHED` no se declara en este commit |

## Pendiente

- Aceptar `ADR-PK-QV-001..004` (4 ADRs `proposed`).
- Aceptar `ADR-PK-QV-003` específicamente para habilitar
  `retirement_authorised=true`; mientras no, ningún test se borra.
- `pipelinek-release-harness` ejecutándose contra la RC para cerrar la
  matriz AAT-BQ4 + AAT-BQ5.
