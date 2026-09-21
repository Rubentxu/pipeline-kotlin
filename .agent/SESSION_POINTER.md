# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T11:58Z. **Tipo de cambio de esta sesión:** WU-RP-000 (CI path repair); cierre local, pendiente push + verificación GH Actions del nuevo SHA.
**Código auditado:** main @ 5aa318029337dd5fbbf3fe54a3233b91a2a8bda4 (post WU-RP-000, base 8b5f41bf).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → 5aa31802 (local, NOT_YET_PUSHED). NO copiar este SHA como resultado de un test verde hasta verificar GH Actions run verde en 5aa31802.

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- LAST_CLOSED_WU: WU-RP-001 — branch protection + checks mapping + CLI installed. Receipt: docs/v2/07-uat/WU_RP_001_RECEIPT.md. Status: PASS_WITH_PROTECTION. main now protected (enforce_admins=true, strict=true, contexts=["LPR-0 CI / compile"], force-pushes/deletions off). CLI installDist + validate fixture OK. Compatibility corpus 30/30 OK.
- NEXT_WU: WU-RP-002 — regenerate Step inventory from CoreStepRegistryFactory + receipts; reconcile 16 vs 20 counters; fix FArchL7DomainEventExhaustivityTest drift (48→51); fix Lfc0V1QuarantineFitnessTest archived path; harden ConcurrentStepDispatcherTest flake.
- BLOCKERS: 3 pre-existing test failures surfaced now that CI executes (1 ConcurrentStepDispatcherTest flake in slow CI runner; 2 arch-fitness drifts 48→51 and archived path) → WU-RP-002. Branch protection widened to all jobs pending WU-RP-002.
- NO_GO: iniciar core.lock/Step nuevo o publicar una release nueva mientras RP-0/RP-1 no estén verificadas; no modificar recibos históricos.
- RELEASE_REFERENCE: v0.39.0 (certificada documentalmente en SU commit y canal GitHub); HEAD posterior NOT_YET_RECERTIFIED.
- HISTORY: docs/historico/INDEX.md.
- TESTS_THIS_WU: local only (cd v2 && ./gradlew ...). Remote CI verification pending push + GH Actions run.
- STALE_LEGACY_POINTERS: .agent/HANDOFF-WU-LPR-090.md, .agent/LPR-001_CYCLE_STATE.md, .agent/TESTING-STATE.md contienen secciones históricas de Phase D pending o contadores anteriores. NO usarlos como cola actual; conservar su evidencia y reconciliarlos en RP-002.
- STASH_REF: 1 stash entry pre-8b5f41bf con WU-LPR-091 phase-b-untouched (handler try/catch fix + test). Preservado por NO_GO, no aplicarlo.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log -1 --format='%H %cI %s'; git log --oneline -6`.
2. Leer AGENTS.md (protocolo inicial), este puntero, ROADMAP.md, CERTIFICATION_PROTOCOL.md, PRODUCTION_READY_UAT_MATRIX.md y el último bloque de WORK_JOURNAL.md.
3. Contrastar `git log -1 origin/main` con HEAD local. Si divergen (ej. PRs remotos), evaluar fast-forward o merge.
4. Verificar GH Actions run de 5aa31802 (`gh run list --workflow=lpr0-ci.yml --limit 3`); si NO existe, push primero. Si existe y es verde, continuar WU-RP-001. Si existe y es rojo, diagnosticar antes de continuar.
5. Si todo verde en 5aa31802, ejecutar WU-RP-001 (mapear checks obligatorios; caracterizar rama main y reglas de protección).
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
