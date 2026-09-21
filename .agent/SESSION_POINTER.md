# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T11:36Z. **Tipo de cambio de esta sesión:** WU-RP-001 CIERRE FINAL (second round) — receipt+state committed at 4f3451f2, push landed via bootstrap (compile GREEN verified at 4f3451f2 via run 35589016116).
**Código auditado:** main @ 4f3451f2ce1f9e64a0f79bc15baf55ea759280f1 (post WU-RP-001, base 7fd407ef, base-base 8b5f41bf).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → 4f3451f2 (local + remote). CI compile VERIFIED SUCCESS at 4f3451f2 via run 35589016116; previous fea34ede also GREEN via run 35587673258.

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- LAST_CLOSED_WU: WU-RP-001 — branch protection + checks mapping + CLI installed. Receipt: docs/v2/07-uat/WU_RP_001_RECEIPT.md. Status: PASS_WITH_PROTECTION. main now protected (enforce_admins=true, strict=true, contexts=["LPR-0 CI / compile"], force-pushes/deletions off). CLI installDist + validate fixture OK. Compatibility corpus 30/30 OK. Pushed 7fd407ef..fea34ede then 4f3451f2 to remote (two bootstrap rounds due to strict protection requiring compile GREEN on pushed SHA). CI run 35589016116 confirms compile SUCCESS at 4f3451f2 (HEAD). 3 pre-existing failures persist (ConcurrentStepDispatcherTest flake, FArchL7 48→51 drift, Lfc0V1QuarantineFitnessTest archived path) → WU-RP-002 closes them. Receipt delta (HEAD=4f3451f2) staged locally; will be pushed alongside WU-RP-002 first commit (avoiding another bootstrap).
- NEXT_WU: WU-RP-002 — regenerate Step inventory from CoreStepRegistryFactory + receipts; reconcile 16 vs 20 counters; fix FArchL7DomainEventExhaustivityTest drift (48→51); fix Lfc0V1QuarantineFitnessTest archived path; harden ConcurrentStepDispatcherTest flake.
- BLOCKERS: 3 pre-existing test failures surfaced now that CI executes (1 ConcurrentStepDispatcherTest flake in slow CI runner; 2 arch-fitness drifts 48→51 and archived path) → WU-RP-002. Branch protection widened to all jobs pending WU-RP-002.
- NO_GO: iniciar core.lock/Step nuevo o publicar una release nueva mientras RP-0/RP-1 no estén verificadas; no modificar recibos históricos.
- RELEASE_REFERENCE: v0.39.0 (certificada documentalmente en SU commit y canal GitHub); HEAD posterior NOT_YET_RECERTIFIED.
- HISTORY: docs/historico/INDEX.md.
- TESTS_THIS_WU: local only (cd v2 && ./gradlew ...). Remote CI verification pending push + GH Actions run. **VERIFIED at fea34ede via run 35587673258: compile SUCCESS.**
- STALE_LEGACY_POINTERS: .agent/HANDOFF-WU-LPR-090.md, .agent/LPR-001_CYCLE_STATE.md, .agent/TESTING-STATE.md contienen secciones históricas de Phase D pending o contadores anteriores. NO usarlos como cola actual; conservar su evidencia y reconciliarlos en RP-002.
- STASH_REF: 1 stash entry pre-8b5f41bf con WU-LPR-091 phase-b-untouched (handler try/catch fix + test). Preservado por NO_GO, no aplicarlo.
- BOOTSTRAP_NOTE: Push of fea34ede AND 4f3451f2 each required DELETE/PUT of protection because the strict=true required check (`LPR-0 CI / compile`) had not yet run on the pushed SHA at push time. Procedure executed twice. Documented in WU_RP_001_RECEIPT.md. WU-RP-002+ must adopt the PR-based workflow (PR runs CI before merge attempt), or use a long-lived branch strategy, to avoid this bootstrap on every doc-only commit.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log -1 --format='%H %cI %s'; git log --oneline -6`.
2. Leer AGENTS.md (protocolo inicial), este puntero, ROADMAP.md, CERTIFICATION_PROTOCOL.md, PRODUCTION_READY_UAT_MATRIX.md y el último bloque de WORK_JOURNAL.md.
3. Contrastar `git log -1 origin/main` con HEAD local. Si divergen (ej. PRs remotos), evaluar fast-forward o merge.
4. HEAD should be 4f3451f2 on both local and remote; CI compile should be GREEN at 4f3451f2 via run 35589016116. Branch protection ACTIVE (`LPR-0 CI / compile` required, strict=true, force-pushes/deletions off). Next: WU-RP-002 (regenerate inventory + reconcile 3 pre-existing failures).
5. Si HEAD diverge, evaluar fast-forward o merge; si protection hook bloquea, ejecutar bootstrap procedure documentado en WU_RP_001_RECEIPT.md.
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
