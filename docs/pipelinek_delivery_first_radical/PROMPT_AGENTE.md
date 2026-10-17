# Prompt para un agente de implementación — corte radical y funcionalidad real

Proyecto: Rubentxu/pipeline-kotlin. Autoridad: `AGENTS.md` + SDDK + `docs/v2/05-roadmap/ROADMAP.md`. Incorporar (tras revisión) `PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md` sin crear segundo roadmap. Base remota auditada `1c21f303`, refrescar HEAD. Skill obligatoria existente `delivery-first-testing/SKILL.md` desde `Rubentxu/agent-skill`: usar router `references/navigation.md`, registro de lentes y sólo lentes `ready`. Preferir `harness-economics`, `change-impact`, `regression-promotion`, `ephemeral-uat`, `merge-assurance`, `integration-assurance`, `release-assurance`, `failure-triage` según necesidad.

## Objetivo no negociable

**Entregar producto y desmontar QA innecesaria**. No más refactors autónomos del harness. Desde RC6, hubo 9.530 líneas QA tocadas frente a 437 producto. Como primer corte, atacar el hecho de que `:test-harness` es dependencia `implementation` del CLI y contiene 38 fuentes + 51 tests. Meta: retirar el módulo completo, tras migrar garantías reales a Gradle/JUnit y al certifier externo, **no construir `test-harness-lite`**. Conservar `pipeline-event-harness` porque `pipeline events verify` lo usa. No reconstruir motor de retirement/cycle/impact/evidence.

## Primer movimiento

1. Restaurar SDDK, Git y normas. Comparar `origin/main`, inventario de módulos y tests. Ejecutar `tools/test_portfolio.py` desde fuera del repo para inventario solo lectura. Reconocer `ADR-PK-DF-002` como **PROPUESTA**; tramitar aprobación/enmiendas antes de relajación global de `check` o bajas de legacy protegidas. Sin permiso, seguir poda no conflictiva y declarar `BLOCKED_POLICY` para el resto.
2. **R0:** suprimir métodos `@Disabled` de snapshots transitorios, tests de contadores o forma sin contrato; ejemplo `CoreSleepRegistryPrimaryFitnessTest` (conservar métodos vivos). Revisar `CoordinatorGrowthGuardrailTest` y source-regex fitness. Borrar código muerto y scripts huérfanos; commits atómicos.
3. **R1:** quitar `InProcessProductProbeExecutor` de `src/main` y `implementation(project(":test-harness"))`, migrar HF1 a tests directos, quitar dependencias Gradle/CLI/scripts al QA, borrar los paquetes `cycle`, `retirement`, `lanes`, `plan`, `catalog`, `util` y el módulo `:test-harness` cuando no tenga consumidores. No tocar `pipeline-event-harness` ni SDK contratos válidos. Comprobar JAR/ZIP sin QA y `events verify` operativo.
4. **R2:** retirar UAT/E2E legacy `migrated-pending` **solo** con equivalencia por garantía + política autorizada; no conservar tests deshabilitados por historia ni crear equivalentes 1:1. Garantías de secretos, recuperación, concurrencia, persistencia y seguridad quedan con una prueba discriminante real en seam propietario o certifier externo.
5. **H1 de producto:** arreglar flujo OBS real para que `pipelinek console --follow` funcione en vivo. Si el supuesto fallo ya está resuelto en HEAD, elegir otro defecto de usuario verificable, nunca llenar tiempo con tipos del harness. Luego H2 durable y H3 HTTP/SDK (paralelos si seams distintos), H4 candidate y H5 V1.

## Evidencia y economía

DEV: tests afectados. MERGE: tests afectados y críticos del cambio. INTEGRACIÓN/RC: full solo centralizada según política aprobada, ZIP y un consumidor real, además de HF3/HF4 cuando riesgo lo exige. UAT/E2E/AAT **temporales** en `/tmp`, por lo común cero o una por feature, con timeout, negativa específica y limpieza al cerrar. Eliminar temporales antes del SHA de publicación. No usar `--rerun-tasks` por defecto; no crear catálogos/manifiestos/gates nuevos.

## Agent teams, ownership y anti-colisión

Equipo A: `v2/pipeline-application/src/main/` y dependencia QA; Equipo B: podas históricas en `src/test` sin tocar A; Equipo C: catalogar dueños, consumidores, comparar clase/bytes/tiempo y verificar ZIP (lectura y fixtures externos), sin tocar producción; Integrador: `build.gradle.kts`, `settings.gradle.kts`, estados/normas, gates, commits e integración. Al salir de H0, equipo D Output Plane, equipo E CLI/console y equipo F HTTP/SDK; evitar editar el mismo archivo desde dos agentes o dos Gradle contra checkout bloqueado.

## Formato de cada informe (breve)

1. **Producto entregado** (comando y comportamiento del consumidor).
2. **Código QA realmente BORRADO**: archivos/familias, líneas, dependencias, runtime ZIP sin QA, tiempos antes/después (sin falsos porcentajes).
3. **Garantías conservadas**: contrato → test mínimo/ejecución real → resultado, y bloqueos `NOT_RUN`/`BLOCKED`.
4. **UAT/E2E/AAT temporales creadas y BORRADAS**; legacy pendientes por política/equivalencia.
5. SHA/branch/main, versión candidata y binarios publicados cuando autorizado, certifier externo separado.

**Antibucle:** si dos rondas consecutivas solo añaden o reparan tests/harness sin desbloquear funcionalidad ni cerrar un P0 real, STOP esa línea: eliminar, simplificar o volver al producto. No incrementar horas de tests para alcanzar un porcentaje.
