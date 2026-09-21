# WORK_JOURNAL — diario de continuidad (append-only)

**Finalidad:** conservar una secuencia breve de decisiones, trabajo realmente realizado y el siguiente punto de entrada. NO sustituye a Git, tickets, XML, recibos ni .agent/TESTING-STATE.md. Anotar UTC y SHA; nuevas entradas al FINAL, sin cambiar las anteriores. Los receipts de releases permanecen inmutables.

## 2026-09-21 — RP-BOOT-001 — Orden documental y nuevo contrato de certificación

- Baseline observada: main a554fd5544f74f580bbd531c9b394cff1e073621; a esta fecha el workflow LPR-0 del SHA falló antes de compilar por no encontrar ./gradlew en raíz. Versión v0.39.0 publicada el 2026-09-19, no equivale al estado posterior de main.
- Hecho en esta unidad documental: archivar las cinco propuestas empaquetadas y la cronología/snapshot LPR antiguos sin tocar sus blobs; adoptar ROADMAP.md como única secuencia; establecer ACTIVE_DOCUMENTS, CERTIFICATION_PROTOCOL, PRODUCTION_READY_UAT_MATRIX y SESSION_POINTER; añadir protocolo de recuperación a AGENTS.md.
- Decisión: gates RP-0 y RP-1 preceden nuevos Steps; cada certificado se ancla al SHA/artefacto; conservar histórico de M3/ML/EM/EVT y recibos previos. No confundir REGISTERED con CERTIFIED.
- Código/CI/tests de aplicación editados: NINGUNO. Pruebas ejecutadas durante esta unidad: NINGUNA. Estado: DOCUMENTACIÓN PUBLICADA si commit existe; BUILD/CI/PRODUCT-GATE siguen NO_VERIFICADOS en el nuevo SHA.
- Riesgos iniciales: H01 CI; H02 suite publishHTML; H03/H04 index.html/inyección; H05/H06 symlinks; H07 coordinator; H08/H09 serialización/concurrencia; H10 drift.
- Próxima unidad WU-RP-000: inspeccionar Git/CI actuales, corregir wrapper/workflows, conseguir gates de CI reales. NO continuar WU-LPR-091 antes de RP-0/RP-1.
- Registro del commit documental: identificar mediante git log -1 -- docs/v2/05-roadmap/ROADMAP.md; no escribir SHA anticipado. Al iniciar la próxima sesión, agregar una entrada breve de comprobación de cabeza de rama.

## Plantilla para próximas entradas (copiar al final, no marcar PASS sin resultado)

### YYYY-MM-DDTHH:MM:SSZ — WU-RP-NNN — estado

- Base SHA / HEAD SHA / branch:
- Intención, contrato y UAT:
- Decisión/ADR; rutas modificadas:
- Tests realmente ejecutados: comando, exit, XML (tests/failures/errors/skipped), artefacto SHA; CI URL:
- PASS / FAIL / BLOCKED / NOT_RUN y causa; evidencia histórica todavía válida/caducada:
- Bloqueos y riesgo residual:
- Puntero actualizado: NEXT_WU y primer comando reproducible:
