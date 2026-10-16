# v0.61.0-rc3 — Quality Value Roadmap deltas (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `06e58fc61abf334a725ef97422048c5260a5a8bc`.
**Tag:** `v0.61.0-rc3` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.61.0.zip` (88.7 MB, SHA-256
`feacc0f27bc62fc155164325f37e1871c3896ca1e94c4514643c7a33d0e49cf8`).
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que este RC entrega sobre `0.61.0-rc2`

Este RC NO incrementa la versión del producto: sigue siendo 0.61.0
(per WU-LPR-071, la versión es la única autoridad y se conserva
mientras no haya bump explícito). Lo que se añade al SHA final son
**deltas puras** sobre el `v2/test-harness/` canónico de `origin/main`:

- `lanes/` — `TestLane` enum cerrado (FAST/INTEGRATION/CANDIDATE/RESTART/SERVICE_SANDBOX/ONLINE_SMOKE) + `LanePlanner` (decider puro de partición) + `LaneSpec` (record autocontenido) + 6 contract tests.
- `cycle/` — `CycleState` ADT (7 casos: Draft/Running/Observed/Promoted/Refused/BlockedExternal), `CycleEvent` ADT (6 casos), `Cycle.decide` puro total, `CycleManifest` + `ProbeEntry` para `retirement_guard.py`. 10 contract tests.
- `retirement/` — `DeletionDecision` ADT (7 casos), `TestClassifier` puro conservador (nunca `DELETE_READY` desde heurística), `DeletionGuard` (rechaza mientras `retirementAuthorised=false`), `InventoryClassifierMain` CLI. 14 contract tests.
- `overlay/docs/` — 4 ADRs `ADR-PK-QV-001..004`, 4 specs `TEST_IMPACT_AND_EVIDENCE` / `TEST_EXECUTION_LANES` / `FUNCTIONAL_CORE_ONTOLOGY` / `EPHEMERAL_CYCLE_LIFECYCLE`, el addendum `QUALITY_VALUE_ADDENDUM`, dos quality policy y la `BQ_ACCEPTANCE_MATRIX`. Todos `proposed` / `PROPOSED`.

## Lo que NO entrega (y por qué)

- **BQ1 re-implementación de ImpactResolver / EvidenceReader / XmlReportReader**: `origin/main` ya entrega el `v2/test-harness/` H0 (commits `b1515b28` + 9 follow-ups) con `plan/ImpactResolver`, `plan/EvidenceReader`, `plan/ValidationPlan`, `plan/Gate`, `plan/PlanCli`, `plan/Dispatch`, `plan/InstalledDistribution`, `catalog/ContractCatalogue` y `ContractCatalogueFitness`. Re-implementarlo sería la inversión de autoridad que el addendum prohíbe. H0 cubre BQ1.
- **Retirada material de tests**: `retirementAuthorised=false` por defecto (ADR-PK-QV-003 sigue `proposed`). Cero tests borrados en este RC. 913 tests clasificados por el inventory: 657 keep / 118 migrate_external / 63 legacy_v1 / 57 fixture / 18 blocked / 0 redundant.
- **CANDIDATE_PUBLISHED → CERTIFIED**: el certifier externo no emite veredicto; permanece en este repo como `BLOCKED_EXTERNAL` con causa explícita.
- **STABLE_PROMOTED**: no aplica. El harness externo es el dueño de la promoción estable; este repo sólo entrega la candidata.

## Decisiones arquitectónicas

- **H0 es la implementación canónica del test-harness.** El paquete del
  plan proponía re-implementar `ImpactResolver` / `EvidenceReader` /
  `XmlReportReader`; al integrar, esos diseños ya existían en
  `origin/main` con más madurez (ejecutor Gradle real, gate con
  evidencia, fitness). Se acepta H0; se conservan sólo las deltas que
  NO duplican.
- **Deltas autocontenidas.** Mis módulos (`lanes/`, `cycle/`,
  `retirement/`) NO importan de `catalog/` o `plan/` de H0, para
  evitar acoplamiento bidireccional. Usan sólo `kotlin-stdlib`.
- **Cycle de test ≠ Cycle de pipeline.** El `Cycle` que entrego
  modela el ciclo de aceptación de un test efímero (`Draft → ... →
  Closed`). NO duplica el modelo de replay del pipeline, que vive en
  `:pipeline-domain:durable` y está gated por ADR-0103. El
  `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` lo dice explícitamente.
- **Classifier conservador.** `TestClassifier` NUNCA dice `DELETE_READY`
  por nombre de archivo. La promoción a retire es de `DeletionGuard`,
  que exige `retirementAuthorised=true` (gated por ADR-PK-QV-003).

## Evidencia local (pre-publicación)

```
:test-harness:test
17 clases, 116 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~13s (incremental)
```

Detalle por módulo delta:

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
| Evidencia con pruebas skipped/0 | OK: las 6 mutaciones AAT del paquete son leyes del decider; viven como `@Test` permanentes |
| UAT externalizada sin garantía equivalente | OK: AAT-BQ4-01..03 dependen del harness; quedan en `BQ_ACCEPTANCE_MATRIX.md` como contrato declarativo |
| Secreto en logs | OK: no hay logging en los deciders |
| `main` divergente | OK: este SHA es fast-forward sobre `origin/main@29981033` |
| ZIP regenerado tras verificar | OK: el SHA del ZIP (`feacc0f…`) está en `SHA256SUMS` y se calculó una vez |
| Ausencia de habilitación para tag/publicación | OK: este commit se publica como CANDIDATE_PUBLISHED, no como STABLE_PROMOTED |

## Pendiente

- Aceptar `ADR-PK-QV-001..004` (4 ADRs `proposed`).
- Aceptar `ADR-PK-QV-003` específicamente para habilitar
  `retirementAuthorised=true`; mientras no, ningún test se borra.
- `pipelinek-release-harness` ejecutándose contra este ZIP para cerrar
  la matriz AAT-BQ4-01..03 y AAT-BQ5-01.
- Bump de versión a `0.62.0` cuando el roadmap lo justifique (no en
  este RC).
