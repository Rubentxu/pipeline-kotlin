# BQ1 — Decir la verdad sobre lo que se ha probado (closure receipt)

**Fecha de cierre:** 2026-10-16.
**Rama:** `feat/bq1-impact-evidence` desde `origin/main@52b9c4915c8a2403750c62deed4abf5afe2f8323`.
**Estado:** BQ1 IMPLEMENTED (código verde, ADRs `proposed`, RC no publicada — requiere harness externo para equivalencia de la matriz instalada).

## Valor entregado

Un módulo nuevo `v2/test-harness` con deciders puros sobre el modelo de impacto / evidencia / validación. Tres autoridades separadas:

| Autoridad | Tipo | I/O |
|---|---|---|
| `ImpactResolver` | `object` (función pura) | ninguna |
| `EvidenceReader` | `object` (función pura) | ninguna |
| `ValidationPlan` | `object` (orquestador puro) | ninguna |
| `XmlReportReader` | `object` (adaptador) | lee `InputStream`; XXE deshabilitado |

Las mutaciones AAT-BQ1-01..04 tienen tests permanentes como leyes de los deciders. La equivalencia externa (matriz instalada) queda `BLOCKED_EXTERNAL`: el ciclo de AAT temporal contra la distribución se ejecuta en BQ4 sobre la RC candidata, no en este commit.

## Mapeo BQ1 → leyes implementadas

| TODO del addendum | Implementación |
|---|---|
| `ChangedInput` ADT +8 casos (production + NonProduction + UnclassifiedProduction) | `plan/ChangedInput.kt:13-51` |
| `ContractCatalogue` extensible, capability→fuentes→nivel→suites | `catalog/ContractCatalogue.kt` |
| `ContractCatalogueFitnessTest` leyes bidireccionales | `test/catalog/ContractCatalogueFitnessTest.kt` (7 tests) |
| `ImpactResolver` puro, `Refused(UnclassifiedInput)`, fail-closed | `plan/ImpactResolver.kt:50-58` |
| `EvidenceReader` puro + `XmlReportReader` impuro | `evidence/EvidenceReader.kt`, `evidence/XmlReportReader.kt` |
| Validación `tests>0`, `tests-skipped<tests`, `errors=failures=0` | `evidence/ReportObservation.kt` invariantes + `EvidenceReader` orden de causas |
| `HarnessFidelity` HF0..HF6 cerrado, `ObservedBoundary` por runner | `plan/HarnessFidelity.kt`, `validation/ValidationPlan.kt` FIDELITY_GAP |
| No desactivar guards, demostrar mutaciones | tests AAT-BQ1-01..04 viven en `EvidenceReaderContractTest` como leyes |

## Evidencia de tests (SHA 52b9c491, no tocada)

```
:test-harness:test
7 clases, 37 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~5s (incremental)
```

Detalle por clase:

| Clase | tests | skipped | failures | errors | wall |
|---|---|---|---|---|---|
| ContractCatalogueFitnessTest | 7 | 0 | 0 | 0 | 0.504s |
| EvidenceReaderContractTest | 8 | 0 | 0 | 0 | 0.022s |
| XmlReportReaderTest | 4 | 0 | 0 | 0 | 0.063s |
| ImpactResolverContractTest$RefusedCases | 4 | 0 | 0 | 0 | 0.019s |
| ImpactResolverContractTest$SelectedCases | 5 | 0 | 0 | 0 | 0.012s |
| ImpactResolverPropertyTest | 4 | 0 | 0 | 0 | 0.011s |
| ValidationPlanTest | 5 | 0 | 0 | 0 | 0.035s |

XMLs en `v2/test-harness/build/test-results/test/TEST-*.xml`.

## Mutaciones AAT (leyes del decider)

| ID addendum | Ley que la prueba protege | Test permanente |
|---|---|---|
| AAT-BQ1-01 EMPTY | `ReportObservation(tests=0)` → `Refused(EMPTY)` | `EvidenceReaderContractTest.AAT-BQ1-01 empty report is Refused EMPTY` |
| AAT-BQ1-02 ALL_SKIPPED | `skipped==tests` → `Refused(ALL_SKIPPED)` | `EvidenceReaderContractTest.AAT-BQ1-02 all-skipped report is Refused ALL_SKIPPED` |
| AAT-BQ1-03 service no catalogado | `META-INF/services` cae en `KotlinSource` o `ServiceDescriptor`; sin contrato → `Refused(CatalogueGap)` | `ImpactResolverContractTest.production path with no catalogue owner refuses (CatalogueGap)` |
| AAT-BQ1-04 buildCommit erróneo | `reportedCommit != expectedCommit` → `Refused(STALE_EVIDENCE)` | `EvidenceReaderContractTest.AAT-BQ1-04 stale commit is Refused STALE_EVIDENCE` |
| (implícito) XXE | `<!DOCTYPE>` rechazado por parser seguro | `XmlReportReaderTest.DOCTYPE is refused by the secure parser` |
| (implícito) FIDELITY_GAP | observed<required → `Refused(FIDELITY_GAP)` | `ValidationPlanTest.lower observed fidelity than required is Refused FIDELITY_GAP` |
| (implícito) ambigüedad | dos contratos matchean el mismo path → `Refused(CatalogueGap)` | `ImpactResolverContractTest.ambiguous path refuses` |

## Probes temporales eliminados

BQ1 NO creó probes UAT/AAT en el repo. Los `AAT-BQ1-*` que la matriz menciona son leyes del decider implementadas como `@Test`; viven permanentemente porque **son la garantía**, no una sonda. No hay `src/test/.../BQ1*` adicional que retirar.

## BQ1 — STOP checks

| Stop | Estado |
|---|---|
| Contrato nuevo sin owner | OK: cada `ChangedInput` tiene un caso cerrado; el decider rehúsa `UnclassifiedProduction` |
| Evidencia con pruebas skipped/0 | OK: `Refused(EMPTY)` y `Refused(ALL_SKIPPED)` son las dos primeras causas del reader |
| UAT externalizada sin garantía equivalente | OK: el ciclo AAT vive dentro del módulo, sin invocar binario instalado; la equivalencia se delega a BQ4 |
| Secreto en logs | OK: no hay logging en ninguna ruta de los deciders |
| `main` divergente | OK: este commit se integra por PR + merge, sin force-push |
| ZIP regenerado tras verificar | N/A: no se publica RC en BQ1 — la candidata se construye tras BQ2 (lifecycle) o BQ4 (matriz instalada) |

## Lo que NO se entrega en BQ1 (y por qué)

- **RC publicada**: BQ1 entrega el módulo y sus leyes. Una RC con la matriz
  instalada equivaldría a probar el decider contra un binario real, lo que
  requiere el lifecycle de BQ4 (probes, manifiesto, guard) y la equivalencia
  externa que el paquete llama `BLOCKED_EXTERNAL` sin harness.
- **Carga de descriptor real del catálogo**: la API `ContractCatalogue.of(...)`
  acepta entradas construidas en código. Un descriptor HOCON/JSON
  correspondiente se entrega en BQ2 (TEST_EXECUTION_LANES_SPEC §L1) o BQ4
  (orquestador), no en BQ1.
- **CLI `validation-gate.py`**: la tool Python del paquete es un script
  auxiliar stdlib; el equivalente canónico en Kotlin es la pareja
  `ImpactResolver` + `EvidenceReader` + `ValidationPlan`. El script se conserva
  en `docs/pipelinek-quality-value-roadmap-2026-10-15/tooling/` sin
  integrarse a `main` (per `INTEGRACION.md` §3: dejar `tooling/` fuera del
  repo).

## ADRs y specs copiados a `docs/v2/`

| Archivo | Estado declarado |
|---|---|
| `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md` | propuesta (adenda, no sustituye ROADMAP.md) |
| `docs/v2/04-adrs/ADR-PK-QV-001-functional-core-and-effect-boundaries.md` | `proposed` |
| `docs/v2/04-adrs/ADR-PK-QV-002-fidelity-impact-and-evidence.md` | `proposed` |
| `docs/v2/04-adrs/ADR-PK-QV-003-ephemeral-acceptance-and-safe-retirement.md` | `proposed` |
| `docs/v2/04-adrs/ADR-PK-QV-004-release-after-ephemeral-cleanup.md` | `proposed` |
| `docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md` | `PROPOSED` |
| `docs/v2/03-specifications/FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` | `PROPOSED` |
| `docs/v2/03-specifications/TEST_EXECUTION_LANES_SPEC.md` | `PROPOSED` |
| `docs/v2/03-specifications/TEST_IMPACT_AND_EVIDENCE_SPEC.md` | `PROPOSED` |
| `docs/v2/06-quality/QUALITY_TEST_POLICY.md` | propuesta |
| `docs/v2/06-quality/RELEASE_EVIDENCE_BUDGETS.md` | propuesta |
| `docs/v2/07-uat/BQ_ACCEPTANCE_MATRIX.md` | contrato declarativo de aceptación (no scripts) |

Ninguno de los ADRs se marca `accepted` aquí. Eso requiere revisión del
decider (`Rubentxu (pending)`) y resolución de contradicciones; el plan de
adenda es explícito al respecto (§Precedencias: BQ1 → BQ2 → BQ3 → BQ4 → BQ5).

## Pendiente (no BQ1)

- BQ2: integrar `pipeline-application:test` con la nueva autoridad de
  evidencia cuando exista una tarea Gradle `validate` que el caller pueda
  invocar.
- BQ3: aplicar la ontología de `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` a una
  vertical concreta (la del TODO de la addenda sugiere `ConsoleOperationSelection`
  o `ShExecution`).
- BQ4: ciclo AAT real contra ZIP candidato; aquí es donde entran los probes
  temporales y el guard de retirada.
- BQ5: clasificación de tests heredados y borrado bajo
  `ADR-PK-QV-003 accepted` (hoy `proposed`, por lo que NO se borra nada en
  este commit).
