# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T16:05Z. **Tipo de cambio de esta sesión:** **WU-RP-032 CLOSED** (r1 post{} fail-closed = ad4cd996; r2 superficie options slim = 04180921, CI SUCCESS ambos). Receipts: WU_RP_032_R1_RECEIPT.md, WU_RP_032_R2_RECEIPT.md. HEAD = 04180921 (CI 7/7 SUCCESS).

**Código auditado:** main @ 04180921. WU head = 04180921.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md (filas RP-011..018 mapeadas).
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-2 CLOSED** (gate receipt RP2_GATE_RECEIPT.md). **RP-3 OPEN**.
  - RP-2 WUs cerradas: 020 (concurrencia store), 021 (rutas de ejecución), 022 (perf baseline + fixes P1 redactor ~230x y P2 chunking SQLITE_TOOBIG), 022b (SLOs), 023 (observación E2E). 024/025 absorbidos en la matriz.
- **KNOWN LIMITATIONS**:
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a WU-RP-042 (release gate RP-5).
  - M5 maxRss ~11 GB (transcript en memoria antes de chunking): sin SLO de RSS en RP-2; candidato streaming-chunks en RP-4.
  - Flake M3 SIGPIPE child (exit 141) 1x, no determinista, 2 reruns limpios. Abierto, no bloqueante.
  - UAT-RP-018 PARTIAL: sandbox-profile 'os' requiere RP-4/5 (ADR-0016).
- LAST_CLOSED_WU: **WU-RP-032 CLOSED** (r1 post{} fail-closed = ad4cd996; r2 options surface slim = 04180921; CI SUCCESS ambos). Receipts WU_RP_032_R1/R2_RECEIPT.md.
  - r1: `post { }` aceptado pero descartado en StageSpec -> fail-closed IllegalStateException + PostDslFailClosedTest.
  - r2 (decisión operador): options de stage solo parámetros de NUESTRO dominio; superficie muerta `options{retry/skip}` eliminada (timeout-only, RetrySpec borrado); retry vive solo como Block Step durable (ADR-0075). UatDsl008StageOptionsFailClosedTest.
- NEXT_WU: continuar RP-3: Step externo con/sin cuerpo por registro genérico (DSL-004/005); luego auditoría declaración-vs-ejecución y semánticas de cancelación.
- BLOCKERS: ninguno.
- NO_GO: iniciar Step core nuevo o publicar release; no modificar recibos históricos; no cambiar contrato público sin ADR/autorización. Próximo ADR libre: ADR-0096 (ADR-0094 reservado).
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir que HEAD = 04180921.
2. Leer ROADMAP (§5 RP-3), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. Verificar CI del SHA observado antes de dar nada por verde.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: localizar fitness/architecture tests existentes (Lfc2RegistryFamilyFitness y afines) y caracterizar consumidores de las APIs que WU-RP-030 va a medir antes de añadir checks.
