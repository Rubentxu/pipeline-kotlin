# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T06:38Z. **Tipo de cambio de esta sesión:** WU-RP-101 CLOSED — RP-1 first WU. Test-side determinism fix for `Lpr011r2SecretRedactionAtRestUatTest.console log contains no raw secret while the child is still alive` (kt:211). CI of HEAD 0063ac46 was RED on this test (run 35662787309, application-shard engine failed). Fix: widened test payload 100 → 1000 echo lines and `sleep 2` → `sleep 10` so the executor's BufferedWriter flushes sanitized bytes to console.log during the live observation window; added Files.getLastModifiedTime capture and tightened polling cadence 50 → 20 ms. Zero production-code change. Receipt: docs/v2/07-uat/WU_RP_101_RECEIPT.md.
**Código auditado:** main @ 0063ac46 (pre-fix HEAD). WU head = this commit (TBD; see git log).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → this commit (TBD; pending push + CI).

## Estado operativo

- ACTIVE_PHASE: RP-1 OPEN. First WU WU-RP-101 closed (test-only fix, zero production change, 3/3 local runs green, full class green, sibling regression green). CI verification of this commit pending `git push origin main` + `gh run list --limit 1`.
- LAST_CLOSED_WU: WU-RP-101 — Lpr011r2 DURING-execution determinism closure. Test-side only. NO_GO respected (no Tier A new Step, no release, no SDKMAN, no historical receipt edits).
- NEXT_WU: WU-RP-010 (publishHTML non-overwrite + index collision; UAT-RP-005), contingent on CI green of WU-RP-101.
- BLOCKERS: none for WU-RP-101 close-out. CI verification of the new SHA is the only open dependency.
- NO_GO: iniciar Step core nuevo o publicar release mientras RP-1 no cierre (Tier A/B); no modificar recibos históricos; no cambiar contrato público sin ADR/autorización.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log --oneline -8`.
2. Leer AGENTS.md, este puntero, ROADMAP.md (§3 RP-1), WORK_JOURNAL.md último bloque.
3. Confirmar CI verde del HEAD WU-RP-101: `gh run list --limit 1`.
4. Si CI verde → abrir WU-RP-010. Si CI rojo → diagnosticar antes de continuar (no acumular regresión).
5. Cada WU de RP-1 arranca con inventario de matriz UAT-SEC/ART y clasificación de clases timing-sensitive (gate release, nunca debilitar aserciones).

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.