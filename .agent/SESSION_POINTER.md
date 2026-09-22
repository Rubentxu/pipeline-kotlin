# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T11:31Z. **Tipo de cambio de esta sesión:** WU-RP-022 CLOSED — Performance baseline + 2 defectos críticos de rendimiento corregidos. Commits `c3faadb2` (P1: StreamingRedactor ring-buffer, ~230x throughput) y `9393e34a` (P2: transcript chunking + flush diagnosis). CI run `35720331038` 7/7 SUCCESS en 9393e34a. Receipt: docs/v2/07-uat/WU_RP_022_RECEIPT.md.

**Código auditado:** main @ 9393e34a (post RP-022). WU head = 9393e34a.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-1 CLOSED** (ADR-0095 KNOWN_LIMITATION). RP-2 OPEN.
  - WU-RP-020 CLOSED (CI verde).
  - WU-RP-021 CLOSED (execution paths characterisation; receipt WU_RP_021_RECEIPT.md).
  - WU-RP-022 CLOSED (perf baseline; receipt WU_RP_022_RECEIPT.md). Dos defectos P0-level corregidos en producción:
    - P1: StreamingRedactor O(n²) por byte → ring buffer, 0.1 MB/s → 23 MB/s (probe floor 20 MB/s).
    - P2: transcript >1e9 chars mataba sqlite-event-writer (SQLITE_TOOBIG) → chunking 64 MiB lossless en ShExecution + flush relanza el error real del writer.
  - Baseline medida (SHA 9393e34a): M1 echo-run 4.93s mediana; M2 warm rerun 5.01s; M3 200MiB 30.7s; M4 slow consumer 3.5s; M5 1GiB soak 137.1s maxRss ~10GB; M6 cpu-echo wall 4.88s user 12.67s. Harness reproducible: v2/compatibility/rp022_perf_baseline.sh <out.json>.
- **KNOWN LIMITATIONS**:
  - UAT-RP-005 invariant 3 (archive MANIFEST.json): FAIL_PROVEN. ADR-0095 difiere a WU-RP-042 (release gate).
  - Flake M3 no determinista observado 1x: child sh SIGPIPE (exit 141); 2 reruns limpios. Abierto, no bloqueante (ver receipt RP-022).
  - M5 maxRss ~10GB: transcript completo materializado en memoria antes del chunking; candidato a streaming-chunks si los SLOs lo exigen.
- LAST_CLOSED_WU: WU-RP-022.
- NEXT_WU: **definición de SLOs RP-2 a partir de la baseline medida** (ROADMAP: SLOs POSTERIORES a medición), y a continuación la siguiente WU abierta de RP-2.
- BLOCKERS: ninguno a nivel código. Residual: ver KNOWN LIMITATIONS.
- NO_GO: iniciar Step core nuevo o publicar release; no modificar recibos históricos; no cambiar contrato público sin ADR/autorización.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir que HEAD = 9393e34a.
2. Leer ROADMAP, CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. Verificar CI del SHA observado antes de dar nada por verde.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: revisar SLOs pendientes en ROADMAP sección RP-2 y proponer valores contra la baseline de WU_RP_022_RECEIPT.md.
