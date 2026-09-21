# Despiece propuesto ITO — subordinado a ROADMAP.md

**Estado: PLANNED, sin implementación, pruebas NOT_RUN.** El roadmap padre es la única secuencia autorizada; esta tabla NO es cola paralela. ADR-0094 PROPOSED, especificación ../03-specifications/INTELLIGENT_TEST_ORCHESTRATOR.md, UAT ../07-uat/INTELLIGENT_TEST_ORCHESTRATOR_UAT.md.

## Precedencias
RP-0 WU-RP-005 investiga y corrige el hang actual **antes de desarrollar ITO**: no sustituir el required check averiado por una suite acotada para fingir verde. RP-1 seguridad/integridad sigue prioritario, y se respetan RP-2/RP-3. Primer código CLI opt-in en RP-4, manteniendo gates originales; Step oficial externo en RP-7 solo DESPUÉS de RP-5 y con decisión de aceptación ADR y autorización si un contrato cambia. No añadir dependencia a RP-8/RP-9.

| WU | Apertura y capacidad | DoD verificable |
|---|---|---|
| WU-RP-043 / TST-00 | RP-0..RP-3 cumplidos; caracterización de CI, tareas Gradle/justfile y tiempos cold/warm, relación test→componente/contrato, 2 fixtures de tecnologías distintas | Reproducción de problema y baseline real con IDs, argv, XML, tiempo y categoría de hangs; NO modifica required CI. |
| WU-RP-044 / TST-01 | 043: motor Kotlin autónomo + CLI plan/explain + Git snapshot dirty, YAML v1 schema, grafo explícito de ownership/contratos/consumidores | UAT-ITO-001..006; corre aun cuando v2 falla; reasons y UNKNOWN verificables, sin outputs ITO en repo. |
| WU-RP-045 / TST-02 | 044: ProcessSupervisor robusto, runners Gradle + command, parser XML fresco, state fuera del repo, tres modos ephemeral/local/ci | UAT-ITO-007..014; no deadlocks/zombies, 0-tests false green, fingerprints e invalidación, no pérdida de diagnóstico. |
| WU-RP-046 / TST-03 | 045: perfiles dev/verify/integration/release; UAT setup/execute/assert/cleanup; 2.º lenguaje real por command | UAT-ITO-015..022, 025..026; gate completo conserva matriz UAT-RP; salida tipada y receipts sin volcar logs al repo. |
| WU-RP-047 / TST-04 | 046: shadow en cambios reales de Step, event codec y DSL; análisis misses, coste y límites; simplificar AGENTS.md/justfile cuando el motor esté certificado | UAT-ITO-023..024; comparar con full suite independiente, registrar falsos negativos/UNKNOWN; no migrar required checks automáticamente. |
| WU-RP-071 / TST-05 | RP-5 certificado y RP-7 abierto: plugin externo oficial que consume librería Kotlin sin depender del CLI | G0..G8; C01..C19 aplicables; Event Harness; UAT-ITO-027..031; ningún bypass ni cambio por nombre de Step en coordinator. |
| WU-RP-072 / TST-06 | 071: adaptadores adicionales por demanda Maven, pytest, Jest/Vitest, Cargo y Go; 2 repos heterogéneos | UAT-ITO-032..035; selector soportado/fallback probado; medir reducción tiempo sin omitir pruebas exigidas. |

## Gobernanza de gates y economía
- Ciclo dev: seleccionar tests directos y UAT fieles según impacto (no barrer application:test por comodidad), priorizar quick fail, agrupar invocaciones de mismo runner, usar caches locales solo cuando válidas. WU corta: closure de consumidores/UAT relevantes; cambio transversal puede exigir full antes.
- Cada integración: gate requerido íntegro en SHA candidato; release: gate de release + artifact exacto, no reuse por historial local. Un shard cancelado o mandatory skipped = INCOMPLETE. Los tests y recibos anteriores de V2 continúan normativos.
- Shadow mode opt-in hasta demostrar cobertura real y fallo controlado; proponer migración de CI por WU/ADR separado cuando existan evidencias, no por modificar YAML o AGENTS. No prometer umbrales de velocidad sin medición.
- Al completar cada WU: tests y UAT realmente ejecutados, reportes/URLs externas, snapshot/entorno, duración, misses/unknown, siguiente WU en SESSION_POINTER del agente activo. **No editar ese puntero al planificar estas WUs futuras.**