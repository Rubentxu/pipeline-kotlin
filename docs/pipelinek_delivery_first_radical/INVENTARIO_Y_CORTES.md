# Inventario con candidatos **del código real**, no un manifiesto de borrado indiscriminado

**HEAD:** `1c21f30357e5a9f56b257277cce92b40276d25cd` · **Estado:** clasificación inicial verificada mediante árbol/lectura selectiva en GitHub; **no se ha corrido Gradle**. Rehacer `tools/test_portfolio.py` en checkout local.

## Arquitectura: qué NO se debe confundir

- `v2/test-harness/` = mecanismo **interno de gobernanza de pruebas** (39-ish tipos principales en 38 archivos) que se ha convertido en subsistema grande. **Objetivo: eliminar el módulo completo** después de reemplazar la única garantía verdaderamente necesaria por Gradle/JUnit y certifier externo; borrar código fuente y tests dependientes juntos.
- `v2/pipeline-event-harness/` = **NO borrar por nombre**: `MainEventsVerifyCli.kt` en producto importa `YamlEventContractCodec` y `EventHarness` para `pipeline events verify --db ... --run ... --contract ...`; es API/funcionalidad pública. Solo evaluar reducción interna si su costo es observado.
- `v2/pipeline-application/src/main/.../harness/InProcessProductProbeExecutor.kt` = adaptador QA dentro de `src/main`, usa `ProbeExecutor` de `:test-harness`; **objetivo mover fuera de producción o borrar** después de que las garantías HF1 se observen en `:pipeline-application:test`/`PipelineRule`.
- `v2/pipeline-application/build.gradle.kts` incluye `implementation(project(":test-harness"))` y `laneReportClasspath(project(":test-harness"))`. Hay que retirar ambas, y la `include(":test-harness")` de `v2/settings.gradle.kts`, **solo tras migrar consumidores**. Comprobar también ServiceLoader/resources/JAR/SDK y `:pipeline-release:releaseGate`.

## Oleada R0 — BORRAR YA TODO LO QUE NO EJECUTA (tras prueba simple de no-consumidores)

1. **Métodos `@Disabled` con mensajes `Historical ... superseded`, transitorios de G1/G3/G4, contadores de filas y snapshots internos obsoletos.** Ejemplo verificado: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepRegistryPrimaryFitnessTest.kt`, con numerosas filas históricas deshabilitadas. **Podar métodos, NO el test completo** si alberga tests productivos vivos. Anotar referencia histórica a Git; no preservar código muerto «por trazabilidad».
2. **Guardas de forma sin contrato de usuario.** Revisar para retirar `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/CoordinatorGrowthGuardrailTest.kt`: ratchet de recuento de líneas, no una garantía funcional. Mantener, si las hay, las pruebas de separación de capacidades/autoridades, no el tamaño exacto del archivo.
3. **Tests que inspeccionan texto fuente privado** (`grep` de patrones, `isNotBlank` a N líneas, número exacto de constructor params) cuando tests de comportamiento o compilador protegen lo importante. Ejemplos candidatos bajo `v2/test-harness/src/test/...`: `ValidationPlanCarrierStrictnessTest.kt`, `CycleFidelityStringDiscriminatorFitnessTest.kt`. Quedan cubiertos al retirar el módulo.
4. **Recetas huérfanas:** `justfile` (`just corpus` ejecuta `:test`, aunque `CompatibilityCorpusTest` está etiquetado `installed-contract`), `scripts/validation-gate.py` (orquesta Gradle recursivo; candidato a desaparecer con `:test-harness`), generadores y lectores de inventario sin consumidores. `git grep`/Gradle test primero; borrar tareas, scripts y docs activas que referencien rutas eliminadas.

**Evidencia de cierre R0:** diff `git rm` pequeño, ninguna referencia productiva, ejecución focalizada >0 y compilación de módulos dependientes. No esperar a reconstruir toda la suite antigua para quitar métodos ya deshabilitados.

## Oleada R1 — EXPULSAR EL QA DEL BINARIO (la gran reducción)

### Cortes objetivo: directorios de `v2/test-harness/src/main/kotlin/.../harness/`

| Directorio | Qué contiene | Acción decidida | Sustituto menor |
|---|---|---|---|
| `cycle/` | `CycleRunner`, `CycleCli`, probes, oracle ledger, evidencias/promotion, FSM, fs ownership | **RETIRAR COMPLETO** tras cortar el único consumidor | Script de UAT efímero `mktemp` externo + ejecutable real; artefactos/identidad del RC existente |
| `retirement/` | `DeletionGuard`, wave, 5-axis carrier, clasificadores/verdict readers | **RETIRAR COMPLETO** cuando decisiones de baja ya se registran en PR/certifier, no Kotlin productivo | Una tabla de retirada con evidencia, revisión de diff y Git |
| `lanes/` | `LanePlanner`, `LanePlannerMain`, `TestLanes` | **RETIRAR COMPLETO**; Gradle `Test` tasks ya poseen el particionado | Tareas `test`, `pluginContractTest`, `installedContractTest` existentes; sin informe alternativo |
| `plan/` | `ChangedInput`, `ImpactResolver`, `Dispatch`, `EvidenceManifest`, `EvidenceReader`, `Gate`, `GateCli`, provenance | **RETIRAR COMPLETO** tras validar no pérdida de un gate único | `git diff` + elección de tests por skill, Gradle/JUnit (run>0) y certifier del ZIP |
| `catalog/` | `ContractCatalogue` específico de test-harness | **RETIRAR** junto al selector | Garantías en tests de producto/SDK y specs existentes, no catálogo paralelo por path |
| `util/` | `DigestFormat` de QA | **RETIRAR** si no queda consumidor | Hashing de producto y `SHA256SUMS` ya canónicos |

Objetivo máximo: retirar las **38 clases Kotlin de `src/main` + 51 Kotlin de `src/test`** de este módulo, más tareas/recetas/resources asociadas. Es una **meta condicionada de 89 archivos Kotlin**, no 89 borrados aprobados. Al abandonar el mecanismo propio desaparecen sus bugs (p.ej., `CycleRunner.finalise()` borra hijos anidados ajenos, `Dispatch` usa `test`, `Gate` obliga `--rerun-tasks`, fidelidad HF combinada incorrectamente). **No invertir antes otra semana en corregir el subsistema que se va a desinstalar**. Mantener temporalmente un gate P0 externo simple cuando sea imprescindible para la transición; retirarlo en el mismo corte.

**Condiciones anteriores a `git rm -r v2/test-harness`:** (1) `:pipeline-application` ya no lo incorpora al ZIP (inspección classpath y distribución); (2) tareas Gradle relevantes producen tests ejecutados >0 y fallo real observable; (3) está conservada la cobertura única sobre secretos/replay/ABI/integridad; (4) `:pipeline-release` conserva identidad y artefactos; (5) enmienda aceptada para quitar autoridad normativa a cycle/plan/deletion o resultado `BLOCKED_POLICY`, nunca eliminación clandestina; (6) gates existentes del nuevo HEAD ejecutados, salvo cambios expresamente aprobados de política. Si hay uso funcional externo del módulo, conservar esa API **fuera del runtime** o revertir su retirada.

## Oleada R2 — RETIRADA MASIVA DE UAT/E2E DUPLICADAS / MIGRATED-PENDING

**Candidatos reales**, todos **verificar por archivo** contra HEAD/certifier: `UatStep001ShExecutionTest.kt`, `UatStep002EchoCaptureTest.kt`, `UatStep003ErrorAbortTest.kt`, `UatStep004SleepTimingTest.kt`, `UatLocal010SmokeE2ESandboxTest.kt`, `UatLocal010SmokeE2ESandboxOfflineTest.kt`, `UatEvt001ReplayTest.kt`, `UatEvt002MultiStepReplayTest.kt`, `UatLocal001KillDuringShTest.kt`, `UatLocal002ResumeAfterKillTest.kt`, `UatDsl001JenkinsFamiliarityTest.kt`, `UatDsl003ParallelTest.kt`, `UatDsl006BodyExecutionTest.kt`, `CompatibilityCorpusTest.kt`, `UatLocal005BannedImportsTest.kt`.

- **`UatStep001...`, etc.** con `@Disabled("migrated-pending")` a nivel de clase, sin cobertura ejecutándose: son **candidatos prioritarios para retirar código muerto**. Conforme al ADR-PK-QV-003 actual, comprobar sustituto con equivalencia o **tramitar aprobación de cambio de política**. Un `@Disabled` no cuenta como PASS ni como protección. Distinguir tests vivos en otras clases del mismo fichero.
- **`CompatibilityCorpusTest`** está `@Tag("installed-contract")`: **NO borrar en bloque** sin comprobar que el certifier externo ejecuta el corpus relevante con igual contrato/fidelidad y que se mantiene un smoke barato de DSL/fixtures. Objetivo mover coste a RC, no duplicar en productor.
- **`UatLocal001/002` (kill/resume)** son críticos y puede faltar equivalencia HF3 real. Retirar del productor **solo después** de que el certifier lo compruebe; si no existe, mantener **un único** caso de recuperación ejecutable en la etapa correcta.
- **`UatLocal005BannedImportsTest`**: su nombre engaña; contiene prohibición de JGit y paths de git; `RESPONSIBILITY_MIGRATION_ROADMAP.md` marca **SE QUEDA**. Puede sustituirse por **un** architecture fitness más pequeño en el productor; no retirar sin esa garantía.
- **OBS/redacción/secretos, sandbox, concurrencia:** preferir 1 test discriminante cada uno donde el efecto existe, no todos los históricos; si están en `migrated-pending` y rojos por producto, no esconder un fallo real por borrado.

La revisión de 17/10 afirma 62 Kotlin `@Disabled.*migrated-pending` y 142 elementos de PR #140 pendientes/blocked tras la poda previa de 21; cifras históricas, **no certificados de equivalencia**, no convertirlas en porcentaje de borrado.

## Oleada R3 — CONSOLIDAR LOS 94 `*Fitness*` nominales y tests de implementación

El árbol muestra **94 archivos `*Fitness*`** (nombres), no 94 redundancias. Inspeccionar primero aquellos que fijan contadores, estructura textual, nombres de clases, bytecode interno y migraciones intermedias. Reemplazar grupo de 10 tests de forma por **un contrato funcional** o cero si Kotlin/Gradle ya imponen el límite; mantener reglas arquitectónicas que previenen escapes reales (replay authority, secretos, package/API, imports, separación de output/eventos). Eliminar `@Disabled` históricos incluso dentro de clases que continúan.

## Conservación acotada (cartera mínima)

Conservar **una observación por garantía importante**, preferiblemente determinista y barata, ampliando al nivel externo solo si esa garantía no es observable internamente: (1) DSL/compiler + Step contract; (2) outcomes/replay/retry/terminal; (3) Output Plane/cursor/secretos; (4) SDK/plugin ABI/ServiceLoader; (5) sandbox/credenciales/aislamiento; (6) provenance/artefactos. Ninguna matriz explota por cartesian product: un consumidor real por capacidad, negativo adversarial cuando cambia la frontera. `@Disabled` no protege nada y no constituye garantía.

### Regla de oro de la poda

**Borrar la implementación del QA que dejó de tener propietario real, no borrar a ciegas las garantías del producto.** No crear scripts/suites sustitutas del mismo tamaño ni migrar 1:1 todos los viejos E2E al certifier; **consolidar** con los escenarios necesarios por capacidad. Documentar en PR: archivo eliminado → garantía → sustituto o demostración de no utilidad → comando que falla ante el defecto (cuando corresponde). Una justificación de 1 línea por grupo es suficiente.
