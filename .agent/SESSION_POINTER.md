# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T09:36Z. **Tipo de cambio de esta sesión:** WU-RP-021 CLOSED — Execution paths characterisation (8 rutas, IR golden shapes). Commit `9deab17f` (test-side only). L1 8/8 PASS; L4 :pipeline-application:test 1716 tests 0 fallos (17m19s). Hallazgo clave: script{} baja a UN core.sh heredoc (excepción legacy caracterizada). CI en curso.

**Código auditado:** main @ 9deab17f (post RP-021). WU head = 9deab17f.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → `f4aa20dcb709aad3fadb6922f26b4325e49ba61b`.

## Estado operativo

- ACTIVE_PHASE: **RP-1 CLOSED** (1 KNOWN_LIMITATION documented as ADR-0095). RP-2 OPEN.
  - WU-RP-101 CLOSED (CI verde at e95b3d41; run 35695823142 7/7 SUCCESS). Test-side determinism fix; zero production code.
  - WU-RP-010 round 1 CLOSED (commit 4b93a1eb; CI run 35697487778 7/7 SUCCESS 4m 5s). 4 tests E2E cover UAT-RP-005 invariants 1, 2, 4. Test-only.
  - WU-RP-013 CLOSED (commit 57a26d19; CI run 35699355394 7/7 SUCCESS 4m 54s). G7 StepContractSuite reconciliation for core.publishHTML (11 → 23 rows). Test-only.
  - WU-RP-011 CLOSED (both rounds): r1 HTML escape (commit ae6b334e), r2 paths confinement (commit d3e9b9b6). UAT-RP-006 + UAT-RP-007 covered.
  - WU-RP-012 CLOSED (commit b3f74e93; CI run 35705391067 7/7 SUCCESS 6m 42s). Stash/unstash paths confinement + symlink filter on both stash() and unstash(). 7 tests rp012 series: symfile / symdir / happy / intosym / trav / roundtrip / unstash-happy. **UAT-RP-008 (Stash symlinks) + UAT-RP-009 (Stash roundtrip) covered**.
- **KNOWED LIMITATIONS**:
  - UAT-RP-005 invariant 3 (archive MANIFEST.json): FAIL_PROVEN at production level. ADR-0095 defers implementation to WU-RP-042 (release gate) — must be re-evaluated before declaring a release.
- LAST_CLOSED_WU: WU-RP-021 (execution paths characterisation, 8 rutas; receipt docs/v2/07-uat/WU_RP_021_RECEIPT.md).
- NEXT_WU: **WU-RP-022** (baseline de rendimiento RP-2). Tras RP-021, WU-RP-020 CLOSED en 59a576e5 (CI 35708209584 7/7).
- BLOCKERS: ninguno a nivel código. Residual conocido documentado: UAT-RP-005 invariant 3 (MANIFEST.json archivado) — diferido por ADR-0095 hasta WU-RP-042 (release gate). UAT-RP-006/007/008/009 cubiertas. UAT-RP-005 inv 1/2/4 cubiertas. UAT-RP-010 cubierta (event codec). UAT-RP-011..018 corresponden a RP-2/3.
- NO_GO: iniciar Step core nuevo o publicar release mientras RP-1 no cierre (Tier A/B); no modificar recibos históricos; no cambiar contrato público sin ADR/autorización.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool (`session_mouse_...`, `session_penguin_...`, `session_sloth_...`, `session_snail_...`) returned spawn success yet produced no artifacts. Orchestrator proceeds direct per pre-authorized pattern, with verifiable evidence at every step. Documented in WU-RP-010_RECEIPT.md and /tmp/wu-rp-010-report.md.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log --oneline -10`.
2. Leer AGENTS.md, este puntero, ROADMAP.md (§3 RP-1), WORK_JOURNAL.md último bloque.
3. Confirmar HEAD = 57a26d19 y CI verde: `gh run list --limit 1` (último run verde).
4. Si CI verde → operador debe decidir entre (a) abrir WU-RP-011 (HTML injection, production escape fix), (b) abrir WU-RP-012 (stash symlink safety), (c) cerrar RP-1 a través de RP-2 con operator sign-off, (d) abrir WU-RP-010 round 2 (production MANIFEST.json). Cada uno requiere autorización explícita para cambios de producción.
5. Operador debe ratificar si abre alguno de estos o cierra la sesión sin avanzar.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.