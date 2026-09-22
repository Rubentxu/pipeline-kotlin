# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T12:32Z. **Tipo de cambio de esta sesión:** RP-2 CLOSED (gate receipt) y **WU-RP-030 CLOSED** (connascence evento/codecs/sequence + defecto P2: 3 kinds no decodificables en JsonEventLog, fix en producción). Receipts: RP2_GATE_RECEIPT.md, WU_RP_030_RECEIPT.md. HEAD avanza sobre 32fa5924 (CI de 32fa5924: 7/7 SUCCESS; SHA del fix WU-RP-030 pendiente de CI).

**Código auditado:** main @ 32fa5924 (post RP-2). WU head = 32fa5924.

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
- LAST_CLOSED_WU: WU-RP-030 (connascence; receipt WU_RP_030_RECEIPT.md).
- NEXT_WU: **WU-RP-031** — separar por pequeñas extracciones StructuralPreparation → DurableResolution → TypedInputDecode → StepExecutor; coordinator solo lifecycle/run-stage. Cada extracción pasa golden journal/event/replay y UAT kill/resume ANTES de retirar su predecesor. Después WU-RP-032 (semánticas DSL).
- BLOCKERS: ninguno.
- NO_GO: iniciar Step core nuevo o publicar release; no modificar recibos históricos; no cambiar contrato público sin ADR/autorización. Próximo ADR libre: ADR-0096 (ADR-0094 reservado).
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir que HEAD = 32fa5924.
2. Leer ROADMAP (§5 RP-3), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. Verificar CI del SHA observado antes de dar nada por verde.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: localizar fitness/architecture tests existentes (Lfc2RegistryFamilyFitness y afines) y caracterizar consumidores de las APIs que WU-RP-030 va a medir antes de añadir checks.
