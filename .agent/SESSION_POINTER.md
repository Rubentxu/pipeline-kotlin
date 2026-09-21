# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21. **Tipo de cambio de esta sesión:** exclusivamente documentación y movimiento de propuestas históricas; NINGÚN código de aplicación, test, workflow ni receipt histórico se ha modificado en esta entrega.
**Código auditado:** main @ a554fd5544f74f580bbd531c9b394cff1e073621 (2026-09-21).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** DETERMINAR con git rev-parse HEAD al iniciar sesión; el commit de documentación es posterior al SHA de código auditado. Nunca copiar el SHA anterior como resultado de un test nuevo.

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- NEXT_WU: WU-RP-000 — reparar wrapper y definición del pipeline CI; a continuación WU-RP-001 y WU-RP-002.
- BLOCKERS: CI de a554fd55, GitHub Actions LPR-0, falla antes de compilar (./gradlew inexistente en raíz). El resto de jobs quedan skipped; no equivale a error de Kotlin. SDKMAN pendiente según receipt histórico, verificar vigencia antes de afirmar estado remoto.
- NO_GO: iniciar core.lock/Step nuevo o publicar una release nueva mientras RP-0/RP-1 no estén verificadas; no modificar recibos históricos.
- RELEASE_REFERENCE: v0.39.0 (certificada documentalmente en SU commit y canal GitHub); HEAD posterior NOT_YET_RECERTIFIED.
- HISTORY: docs/historico/INDEX.md.
- TESTS_THIS_DOCUMENTATION_CHANGE: NOT_RUN; documentos/textos no implican certificación.
- STALE_LEGACY_POINTERS: .agent/HANDOFF-WU-LPR-090.md, .agent/LPR-001_CYCLE_STATE.md, .agent/TESTING-STATE.md contienen secciones históricas de Phase D pending o contadores anteriores. NO usarlos como cola actual; conservar su evidencia y reconciliarlos en RP-002.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. git status --short; git rev-parse HEAD; git log -1 --format='%H %cI %s'; git log --oneline -6.
2. Leer AGENTS.md (protocolo inicial), este puntero, ROADMAP.md, CERTIFICATION_PROTOCOL.md, PRODUCTION_READY_UAT_MATRIX.md y el último bloque de WORK_JOURNAL.md.
3. Contrastar main/remoto/CI, branch y commits posteriores a a554fd55; si HEAD cambió, efectuar análisis incremental y actualizar este puntero ANTES de codificar.
4. Confirmar que v2/gradlew existe y que .github/workflows/lpr0-ci.yml lo invoca mal; abrir WU-RP-000 y caracterizar el CI fallido sin falsificar una baseline verde.
5. Implementar la corrección MÍNIMA en una WU; ejecutar targeted tests y gate remote del nuevo SHA; registrar en nuevo receipt y WORK_JOURNAL.md. Recién entonces actualizar NEXT_WU.
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
