# SPEC-PK-QV-02 — Test lanes, Gradle y fidelidad mínima honesta

**Estado:** PROPOSED. Autoridad base: ADR-0072, `PIPELINE_TEST_HARNESS.md`, `TEST_STRATEGY.md`, `DISTRIBUTION_RELEASE_SPEC.md`.

## Contrato de tareas

| Lane | Propiedad | Ejecución | Dependencia obligatoria | Canal |
|---|---|---|---|---|
| `fast` HF0–HF1 | estados, ADTs, codecs, DSL/reducer in-process | tests Gradle afectados | compilación incremental + contratos + fitness afectados | durante desarrollo |
| `integration` HF1–HF2 | composition, ServiceLoader, ABI y CLI afectadas | solo módulos/fronteras impactadas | adapters + consumidor apropiado | antes del merge |
| `candidate` HF0–HF6 según contrato | byte identity, distribución/reinicio/seguridad, API | matrix + independiente según contrato | `distZip`, SBOM, manifest + certifier externo donde proceda | prerelease |

Los nombres son conceptos de planificación; **no presuponen tareas Gradle existentes**. El agente crea/adapta solo las tareas necesarias, sin pasar por una API paralela o invocar builds anidados con lock exclusivo.

## Requisitos

- L1: sacar tests puros de la dependencia incondicional de `installDist` en `v2/pipeline-application/build.gradle.kts`.
- L2: construir plugins externos solo si el test usa realmente classpath/ABI externo o en release según matriz.
- L3: subprocessos con propiedad de `tempRoot`, grupo de procesos, drenado no bloqueante y kill+wait en teardown; nunca test que dependa de otro test para calentar caché.
- L4: `forkEvery=1` y single fork solo donde un defecto reproducible justifique aislamiento; medida por clase, no flag global.
- L5: cambios de código que alcancen `:pipeline-application:test` por `--tests` solo disparan dependencias baratas; casos requeridos `installDist` se mudan a tarea específica.
- L6: no paralelizar dos invocaciones de Gradle sobre el mismo checkout si `settings.gradle.kts` instala lock exclusivo.
- L7: cada outcome de test tiene `Executed`, `Skipped`, `Blocked`, `Failed`, `NotRun`; 0 tests o sólo skipped no equivalen a Success.
- L8: externalizar distribución/bench/containers al harness externo conforme `RESPONSIBILITY_MIGRATION_ROADMAP.md`; la suite interna retiene contratos y fitness.
- L9: tiempos medidos por host, commit y versión del wrapper; no prometer umbral absoluto en máquinas heterogéneas.

## Aceptación

- Edición de reducer no provoca `installDist` ni `buildExamplePlugin` sin cambio de frontera (verificación del DAG de tareas + logs reales).
- Cambiar `plugin descriptor` selecciona un check de `ServiceLoader` y el nivel requerido.
- Una suite lenta seleccionada por accidente debe poder señalar su binding/capacidad exacta.
- El costo de 5 iteraciones rápidas mejora frente a baseline; sin mutantes supervivientes nuevos de propiedades críticas.
- Prueba instalada exigida en candidate se ejecuta sobre ZIP/artifact actual, no el antiguo.
