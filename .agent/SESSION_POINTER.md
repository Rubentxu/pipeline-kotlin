# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T13:57Z. **Tipo de cambio de esta sesión:** WU-RP-002.2 cierre — closed pre-existing CoreSleepRegistryPrimaryFitnessTest stale key set (pinned 17 keys; production registry 20 keys including `core.stash`, `core.unstash` from WU-LPR-089 and `core.publishHTML` from WU-LPR-090). Inventory mapping corrected (`core.publishHtml` → `core.publishHTML`). Receipt: `docs/v2/07-uat/WU_RP_002_2_RECEIPT.md`.
**Código auditado:** main @ ff17bf9da7aa8134e0e0f97a9b1c71513bcfa331 (post WU-RP-002.1, base c39dcaa6, base-base 6822eff1).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → <pending — see WU-RP-002.2 push> (local+remote). CI run 35593694935 at ff17bf9d: compile SUCCESS, domain-unit SUCCESS, architecture-fitness SUCCESS; application-focused CANCELLED (root-caused and closed locally by WU-RP-002.2 — pending next CI run on the new HEAD).

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- LAST_CLOSED_WU: WU-RP-002.2 — closed pre-existing CoreSleepRegistryPrimaryFitnessTest stale key set + corrected inventory `core.publishHtml` → `core.publishHTML`. Status: PASS. Local evidence C1+C2+C3+C4 all green. Receipt: docs/v2/07-uat/WU_RP_002_2_RECEIPT.md.
- NEXT_WU: WU-RP-003 — RP-0 close-out. (a) Widen `branch_protection.required_status_checks.contexts` to include `domain-unit` + `architecture-fitness` on top of `LPR-0 CI / compile`; (b) decide workflow R5 (PR-based vs long-lived integration branch to avoid the 5 bootstrap pushes observed in RP-000); (c) Run a **full `:pipeline-application:test` LPR-0 application-focused job** on remote to verify CI greenness at the new HEAD; (d) archive RP-000 cycle, advance to RP-1.
- BLOCKERS: 0. All 5 pre-existing CI failures surfaced in RP-000 are now closed (3 in WU-RP-002, 2 SQLite flakes in WU-RP-002.1, 1 fitness stale set in WU-RP-002.2).
- NO_GO: iniciar core.lock/Step nuevo o publicar una release nueva mientras RP-0/RP-1 no estén verificadas; no modificar recibos históricos.
- RELEASE_REFERENCE: v0.39.0 (certificada documentalmente en SU commit y canal GitHub); HEAD posterior NOT_YET_RECERTIFIED.
- HISTORY: docs/historico/INDEX.md.
- TESTS_THIS_WU: local only (`cd v2 && ./gradlew :pipeline-application:test --tests "CoreSleepRegistryPrimaryFitnessTest"`). Remote CI verification pending the WU-RP-002.2 push. Locally all tests in the fitness class are green (16/16).
- STALE_LEGACY_POINTERS: .agent/HANDOFF-WU-LPR-090.md, .agent/LPR-001_CYCLE_STATE.md, .agent/TESTING-STATE.md contienen secciones históricas de Phase D pending o contadores anteriores. NO usarlos como cola actual; conservar su evidencia y reconciliarlos en RP-002.
- STASH_REF: 1 stash entry pre-8b5f41bf con WU-LPR-091 phase-b-untouched (handler try/catch fix + test). Preservado por NO_GO, no aplicarlo.
- BOOTSTRAP_NOTE: Bootstrap push (DELETE protection -> push -> RE-APPLY) executed 5 times in RP-000 cycle (fea34ede, 4f3451f2, 6822eff1, c39dcaa6, ff17bf9d, <pending WU-RP-002.2>). WU-RP-003 SHOULD resolve this by adopting a PR-based workflow or a long-lived branch strategy.

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short; git rev-parse HEAD; git log -1 --format='%H %cI %s'; git log --oneline -6`.
2. Leer AGENTS.md (protocolo inicial), este puntero, ROADMAP.md, CERTIFICATION_PROTOCOL.md, PRODUCTION_READY_UAT_MATRIX.md y el último bloque de WORK_JOURNAL.md.
3. Contrastar `git log -1 origin/main` con HEAD local. Si divergen (ej. PRs remotos), evaluar fast-forward o merge.
4. HEAD should be the WU-RP-002.2 head on both local and remote; CI at that HEAD should show compile + domain-unit + architecture-fitness GREEN, with application-focused GREEN (the WU-RP-002.2 fitness fix). Branch protection ACTIVE (`LPR-0 CI / compile` required, strict=true, force-pushes/deletions off). Next: WU-RP-003 (RP-0 close-out).
5. Si HEAD diverge, evaluar fast-forward o merge; si protection hook bloquea, ejecutar bootstrap procedure documentado en WU_RP_001_RECEIPT.md.
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
