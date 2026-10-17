# Procedimiento práctico para ejecutar la poda sin dejar otro test framework

**Política:** nada de scripts que borran automáticamente cientos de fuentes. `tools/test_portfolio.py` es *solo lectura*, produce TSV y proporciones; `git rm` lo ejecuta el agente en pequeños commits por decisión aprobada. No añadir `tools/` al repo: utilizarlo desde un workspace externo; borrarlo tras la campaña.

## A. Baseline fresco (sin tocar un fichero)

```bash
cd /ruta/workspace/pipeline-kotlin
git fetch origin main --tags
git status --short
git rev-parse HEAD
python3 /ruta/al/ZIP_extraido/tools/test_portfolio.py --repo . --base 0253d3d670602273915387ef5aac0f4b14d74556 --out /tmp/pipelinek-prune-candidates.tsv
# En paralelo inspeccionar dependencias reales (salida a /tmp, no al Git):
git grep -n -E '(:test-harness|dev\.rubentxu\.pipeline\.v2\.harness\.(plan|cycle|lanes|retirement|catalog))' -- v2 ':!v2/test-harness' || true
```

Guardar inventario con SHA. Si `main` ha avanzado, actualizar la selección desde código, no desde este ZIP. Contrastar `AGENTS.md`, ROADMAP, ADR aceptados y SDDK.

## B. R0 — retirar métodos disabled históricos + guardas de forma

- Elegir una clase de test realmente obsoleta y revisar TODOS sus `@Test`; conservar métodos que prueban comportamiento hoy.
- `git rm` solo ficheros íntegramente obsoletos; para métodos aislados editar quirúrgicamente. Ejemplo de archivo donde eliminar **solo métodos** históricos: `CoreSleepRegistryPrimaryFitnessTest.kt`.
- Para `CoordinatorGrowthGuardrailTest.kt`: separar garantía de ownership del recuento exacto de líneas y reemplazarla por un test ya existente si lo hay. No promover otro scanner.
- Compilar/test focalizado (comandos del Gradle real) y comprobar `>0` ejecutados. Un archivo borrado no requiere UAT nueva si no ejecutaba ninguna prueba.
- Commit `test(cleanup): prune dead historical tests` y no una RC por test muerto.

## C. R1 — migrar dependencia y borrar `:test-harness`

1. Buscar consumidores activos de `InProcessProductProbeExecutor`, `CycleCli`, `validationPlan`, `GateCli`, `LanePlannerMain`, `DeletionGuard` y META-INF/services. Comprobar `v2/settings.gradle.kts`, raíz, scripts, build de release, salida de empaquetado y SDK; si consumer real, **separar** API útil de QA antes de borrar.
2. Sustituir pruebas HF1 por `PipelineRule`/contratos JUnit ya disponibles. Mover el adaptador de `src/main` a test **si aún es necesario**; opción preferida eliminarlo si duplica `PipelineRule`. Eliminar `implementation(project(":test-harness"))` del runtime.
3. Mantener dos niveles verificables y uno externo: `:modulo:test` con JUnit afectado, `:pipeline-application:pluginContractTest` en cambios de plugin, `:pipeline-application:installedContractTest` solo candidato/consumidor (si policy aprobada); `:pipeline-release:releaseGate` + certifier externo en RC. **No escribir otro impact resolver ni `EvidenceManifest`**. Comprobar ejecución >0, fallos >0 producen código no cero, `SKIP` total no valida la obligación.
4. Borrar `laneReportClasspath`/`laneReport`, `validationPlan`, `scripts/validation-gate.py` y las recipes dependientes; borrar `v2/test-harness` y la entrada de `settings.gradle.kts` **en un único corte compilable**. Eliminar referencias del classpath productivo y de empaquetado. Evitar dejar adaptadores rotos.
5. Ejecutar Gradle focalizado, un negativo que debería fallar y build de ZIP **en el mismo commit**; comprobar que el ZIP NO incluye clases `.../harness/cycle`, `.../harness/plan` ni dependencias transitivas de QA (el CLI `events verify` sigue funcionando). No ejecutar full gate por rutina si ya ha sido aprobada la nueva política; si no, respetar gate actual o reportar `BLOCKED_POLICY`.
6. Commit `refactor(build): remove embedded QA framework and runtime dependency`; medir coste de `:test`, tamaño ZIP/classpath y errores antes/después.

**Reversión:** revertir solo el commit de la oleada si se pierde un contrato o hay un consumer no visto. No reinventar la FSM anterior. No propagar `Refused -> Passed` al quitarla: trasladar la decisión al test afectado/Gradle o a certifier en RC.

## D. R2 — ola UAT/E2E legacy y portafolio de fitness

1. Generar candidatos con el script (disabled de clase, obsoleto, tag instalado, source-scan). Agrupar por garantía, NO por nombre; elegir unidad de eliminación máxima compatible con una garantía pequeña.
2. `RETIRE` cuando hay equivalente **ejecutado**, `REPLACE` con test menor, `KEEP` si riesgo crítico sin sustituto, `BLOCKED_POLICY` para protecciones del ADR previo sin nueva autorización. No convertir un `@Disabled` en PASS; simplemente reducir código muerto cuando su retirada está autorizada.
3. Coste original y posterior: suma de clases/tests vivos, latencia p50 del bucle focalizado, tiempo completo en RC, minutos de mantenimiento reportados, archivos y líneas de QA eliminadas. Ningún porcentaje arbitrario es criterio de seguridad.
4. Borrar UAT/E2E/AAT creados *en el ciclo actual* tras una aceptación real y registrar una evidencia pequeña; no tocar UAT/fixtures canónicas del producto sin equivalencia.
5. Commit por contrato/capacidad, integración y release de producto al terminar un hito útil. El proyecto NO entrega RC por cada lote de borrado.

## E. Política radical propuesta de gates (solo después de ratificación del ADR)

| Momento | Lo único obligatorio | Nunca por defecto |
|---|---|---|
| Editar código | compilación incremental + tests afectados, por impacto/riesgo | `check` global, 7 niveles HF, mutation suite, `installDist`, `--rerun-tasks` |
| Merge de una feature | compilación + lint local del área + tests afectados + 1 negativa crítica si cambia frontera | corpus completo, E2E reinstalado, certifier externo |
| Integración de hito | contratos transversales realmente tocados + integración real para frontera modificada; full suite de producto en ejecución **centralizada** programada o antes de RC | repetir full suite por cada commit o cada agente |
| RC con binarios | `releaseGate`, artefactos completos/identidad y 1 vertical instalada/consumidor; regresiones críticas HF2/HF3/HF4 requeridas según riesgo; veredicto externo de autoridad | UAT/E2E por fixture, reproducir el mismo test en 3 harnesses |
| Producción | certifier y protección de branch según ADR-0105; si falta, `BLOCKED_EXTERNAL` | reclamar CERTIFIED a partir del productor |

No eliminar por decreto la protección `check` actual sin enmienda aceptada. Ejecución semanal o por RC de full suite no equivale a cobertura completa para todos los merges: los riesgos críticos deben tener un contrato en el merge relevante. Para fallos de seguridad, secretos, persistencia, replay, aislamiento, concurrencia o distribución, siempre conservar un test discriminante de nivel apropiado, no un mero test unitario como sustituto de E2E.

## F. UAT/AAT/E2E mínimos efímeros por feature

- Cambios ordinarios de handler/DSL: **0 E2E** si hay un test de contrato del consumidor y un smoke existente; **1 UAT CLI temporal** si de verdad falta observabilidad.
- Observabilidad: **1 UAT temporal** para seguir stdout+stderr por cursor sobre binario real; negativa de secreto integrada en el mismo escenario cuando sea imprescindible.
- Replay/kill/restart: **1 E2E HF3** temporal o reutilizar contrato external ya vigente, con efecto marcador y restart. Corrupción del journal: AAT **solo si cambia el decoder**.
- SDK/plugin: **1 consumidor externo real** de Maven/ZIP al publicar; rechazo incompatible en la misma prueba si se cambió admisión.
- RC: **1 smoke real de ZIP**, más casos por riesgo crítico descubierto y autoridad externa, no una copia por cada Step.

`mktemp -d` externo, timeout, resultado observable, borrado limitado al directorio propio; nunca mantener por inercia la prueba temporal en Git. `delivery-first-testing`: `harness-economics` → `change-impact` → `ephemeral-uat` → `regression-promotion`; después `merge-assurance`/`release-assurance` solo según fase.
