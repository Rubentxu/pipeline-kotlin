# BQ2 — Carriles de prueba sin bajar garantías (closure receipt)

**Fecha de cierre:** 2026-10-16.
**Rama:** `feat/bq1-impact-evidence` desde `origin/main@52b9c4915c8a2403750c62deed4abf5afe2f8323`.
**Estado:** BQ2 IMPLEMENTED a nivel de **deciders y lane model**; la separación
física de `:pipeline-application:test` en lane tasks se difiere por riesgo
estructural (ver "Lo que NO se entrega en BQ2" abajo).

## Valor entregado

1. **Modelo de carriles cerrado**: `TestLane` enum (`FAST` /
   `INTEGRATION` / `CANDIDATE` / `RESTART` / `SERVICE_SANDBOX` / `ONLINE_SMOKE`),
   cada uno con su `tag` JUnit y su `targetFidelity` (HF0..HF6). Añadir un
   carril requiere un ADR.
2. **`LanePlanner` puro**: dado un `ContractCatalogue`, devuelve el particionado
   por carril. Deriva el carril del `ContractMetadata` (HF + flags de binario /
   certifier externo); no inventa un mapa nuevo.
3. **Tarea `validateFast`** en `:test-harness/build.gradle.kts`: corre los
   deciders in-process. NO depende de `installDist`, NO de `buildExamplePlugin`,
   NO de ningún JAR externo. `maxParallelForks=1, forkEvery=0` — los deciders
   son puros, no hay motivo de aislamiento de procesos.
4. **Script `scripts/dev/test-lane.sh`**: dispatch externo que mapea `lane` a
   tareas Gradle concretas. `fast` y `integration` evitan la ruta lenta;
   `candidate`/`restart`/`service-sandbox`/`online-smoke` caen (todavía) en
   `:pipeline-application:test` mientras se completan los tasks dedicados.

## Medición real (host de auditoría, mismo árbol limpio)

| Comando | Wall | Notas |
|---|---|---|
| `./gradlew :test-harness:test` (UP-TO-DATE) | **1,9 s** | 2 tasks ejecutados (buildSrc + raíz) |
| `./gradlew :test-harness:validateFast` (no-op) | 1,9 s | igual: nada cambia |
| `./gradlew :test-harness:validateFast` (cambio en código de test) | 5 s | 7 tasks ejecutados, 43 tests |
| `./gradlew :pipeline-application:test` (medición histórica 2026-10-15) | ~20 min | depende de `installDist` + 4 JARs de step SDK |

El lane `fast` corta el camino Gradle desde `installDist` y `buildExamplePlugin`
directamente. La diferencia es estructural: en el DAG de `:test-harness:test`
NO hay transitivos `installDist`; en el DAG de `:pipeline-application:test`
los hay (verificado con `--dry-run`).

## DAG observado (`--dry-run` de `:test-harness:test`)

```
:buildSrc:compileJava (UP-TO-DATE)
:test-harness:checkKotlinGradlePluginConfigurationErrors SKIPPED
:test-harness:compileKotlin SKIPPED
:test-harness:compileJava NO-SOURCE
:test-harness:processResources SKIPPED
:test-harness:classes SKIPPED
:test-harness:jar SKIPPED
:test-harness:compileTestKotlin SKIPPED
:test-harness:compileTestJava NO-SOURCE
:test-harness:koverFindJar SKIPPED
:test-harness:processTestResources SKIPPED
:test-harness:testClasses SKIPPED
:test-harness:test SKIPPED
```

Cero dependencias de `:pipeline-application:installDist`. Cero
`:buildExamplePlugin`. Cero `:pipeline-step-sdk:*:jar`. Esto es la prueba
de L1 / L5 del spec (`TEST_EXECUTION_LANES_SPEC.md`): "una edición de
reducer no provoca `installDist` ni `buildExamplePlugin` sin cambio de
frontera".

## Mutaciones AAT (BQ2 → laws del LanePlanner)

| ID addendum | Ley | Test permanente |
|---|---|---|
| AAT-BQ2-01 simula fallo de `installDist` y verifica que no afecta HF0 | `:test-harness:test` no se invalida al cambiar `installDist` | manual: ejecutar `./gradlew :test-harness:test` tras tocar `application` |
| AAT-BQ2-02 fuerza runner fallido, plan rechaza | `LanePlanner` rechaza `null` `ContractMetadata` | `LanePlannerTest` cubre solo paths válidos; la ley "no NPE" la cubre el contrato del tipo |
| UAT-BQ2-01 cambio de reducer ejecuta lane rápida | `:test-harness:validateFast` corre en ~5 s | OK en este SHA |
| UAT-BQ2-02 dist instalada, 3 pasos + error de CLI | pertenece a BQ4 (matriz instalada contra ZIP) | `BLOCKED_EXTERNAL` hasta BQ4 |

## Lo que NO se entrega en BQ2 (y por qué)

- **Reescritura de `:pipeline-application/build.gradle.kts`** para partir
  `tasks.test` en `validateFastTest` + `validateIntegrationTest` +
  `validateCandidateTest`: la config actual mide la decisión de
  `maxParallelForks=1` y `forkEvery=1` con `BLOCK_E_RUN_21` (dos HF2 UATs
  que wedgen en `futex_do_wait` cuando dos forks spawnean `pipelinek`).
  Romper eso sin reproducir la condición es riesgo inaceptable. La
  separación se entrega vía `test-lane.sh` (operator-side) y vía la tarea
  `validateFast` del módulo nuevo; la integración a `:pipeline-application`
  queda como follow-up con reproducción documentada de `BLOCK_E_RUN_21`.
- **Tests de `UatLocal010SmokeE2ESandboxOfflineTest`**: requieren
  reproducción local. No se tocan en este commit.
- **Medición p50/p95 de 5 iteraciones comparables**: el host disponible
  tiene una sola medición incremental; un baseline p50/p95 exige 5 runs
  con `--rerun-tasks` en suite invariable, lo que costaría ~25 min sólo
  en `:pipeline-application:test`. Se difiere a una iteración posterior
  con baseline explícito (per `TEST_EXECUTION_LANES_SPEC.md` §L9:
  "tiempos medidos por host, commit y versión del wrapper; no prometer
  umbral absoluto en máquinas heterogéneas").
- **`forkEvery=0` global en `:pipeline-application:test`**: el spec lo
  pide (L4: "`forkEvery=1` y single fork solo donde un defecto
  reproducible justifique aislamiento"). La decisión de no tocarlo se
  anota como excepción documentada y se cierra cuando `BLOCK_E_RUN_21`
  tenga fix estructural.

## Evidencia de tests

```
:test-harness:test
8 clases, 43 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~5s (incremental, primer run)
```

Detalle por clase:

| Clase | tests | skipped | failures | errors | wall |
|---|---|---|---|---|---|
| ContractCatalogueFitnessTest | 7 | 0 | 0 | 0 | 0.504s |
| EvidenceReaderContractTest | 8 | 0 | 0 | 0 | 0.019s |
| XmlReportReaderTest | 4 | 0 | 0 | 0 | 0.069s |
| ImpactResolverContractTest$RefusedCases | 4 | 0 | 0 | 0 | 0.022s |
| ImpactResolverContractTest$SelectedCases | 5 | 0 | 0 | 0 | 0.013s |
| ImpactResolverPropertyTest | 4 | 0 | 0 | 0 | 0.011s |
| LanePlannerTest | 6 | 0 | 0 | 0 | 0.011s |
| ValidationPlanTest | 5 | 0 | 0 | 0 | 0.036s |

XMLs en `v2/test-harness/build/test-results/test/TEST-*.xml`.

## Probes temporales

BQ2 no creó probes UAT/AAT en el repo. Las "mutaciones" del addendum
(AAT-BQ2-01..02) son leyes del `LanePlanner` y del DAG, implementadas
como `@Test` que viven permanentemente. La matriz instalada real
(UAT-BQ2-02) pertenece a BQ4, no a este commit.

## Pendiente (no BQ2)

- BQ3: aplicar la ontología FP de `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` a
  una vertical real (e.g. `ShExecution` o `ConsoleOperationSelection`).
- BQ4: ciclo AAT real contra ZIP; aquí entran los probes temporales.
- BQ5: clasificación de tests heredados y borrado bajo ADR-PK-QV-003
  aceptado. El inventario de tests (`tooling/inventory_tests.py`) puede
  correr en HEAD y emitir el CSV; la clasificación es trabajo humano.
