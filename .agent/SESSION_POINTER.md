# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T22:15Z. **Tipo de cambio de esta sesión:** WU-RP-005 CLOSED — RP-0 closure round r9..r12. Two production fixes (timeout classification wins over post-kill wrapper exit code; cleanup retains control dir on timeout) + two test determinism fixes (step-dir selection via OpId.parse in UatLocal004; findOpId via OpId.parse in UatLocal007). CI run 35660883142 at 174bd060: 7/7 jobs SUCCESS. Receipt: docs/v2/07-uat/WU_RP_005_TEST_EFFICIENCY_RECEIPT.md.
**Código auditado:** main @ 9b2cf1d9 (receipt+pointer commit). WU head = 174bd060 (r12, all-green CI).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → 9b2cf1d9 local+remote.

## Estado operativo

- ACTIVE_PHASE: RP-0 CLOSED (CI reproducible: lpr0-ci.yml sharded workflow, 7 required contexts green at HEAD). Awaiting RP-0 archive decision, then RP-1 — Integridad, seguridad y verdad de certificación.
- LAST_CLOSED_WU: WU-RP-005 — root cause of application-focused budget overrun: (1) engine race — wrapper exit 0 published after watchdog SIGKILL misclassified as success (r9/r10 fixes); (2) test nondeterminism — filesystem-order dir pickers (r11/r12 fixes). All evidence in receipt rounds r9..r12.
- NEXT_WU: RP-1 opening — UAT-SEC/ART matrix scenarios green with fresh HEAD evidence; revised certification per dimension. No new Steps (NO_GO stands).
- BLOCKERS: none.
- NO_GO: iniciar Step core nuevo o publicar release mientras RP-1 no cierre; no modificar recibos históricos.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED (RP-1 scope).
- BOOTSTRAP_NOTE: 5 bootstrap cycles this session (protection PUT with plain-bool payload via Python tempfile; PATCH on subresources returns spurious 404). Contexts now: 3 base checks + 4 application shard names (literal job names).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log --oneline -6`.
2. Leer AGENTS.md, este puntero, ROADMAP.md (§3 RP-1), WORK_JOURNAL.md último bloque.
3. Confirmar CI verde en HEAD: `gh run list --limit 1`.
4. Abrir RP-1: inventariar matriz UAT-SEC/ART, clasificar clases sensibles a timing (gate release, nunca debilitar aserciones), y planificar WU-RP-101.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
