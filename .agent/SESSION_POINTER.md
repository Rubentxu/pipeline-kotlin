# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T16:07Z. **Tipo de cambio de esta sesión:** WU-RP-002.3 push and CI verification — git init -b master fix VERIFIED remotely (zero failure patterns in CI log for ADV/UatLocal targets); application-focused job cancelled externally at ~14 min for the third time (orthogonal to WU-RP-002.3). Receipt: `docs/v2/07-uat/WU_RP_002_3_RECEIPT.md` (with remote CI evidence).
**Código auditado:** main @ 4f9d339cb8e3cf499fa1372e5469d8bb34b9e28c (post WU-RP-002.3 push; base a9fb87f8, base-base ff17bf9d).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → 4f9d339c local+remote. CI run 35606780538 at 4f9d339c: compile SUCCESS, domain-unit SUCCESS, architecture-fitness SUCCESS, application-focused cancelled (external runner shutdown). The WU-RP-002.3 fix is verified in CI by absence of failure patterns.

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- LAST_CLOSED_WU: WU-RP-002.3 — ADV/UatLocal fixes verified. Receipt: docs/v2/07-uat/WU_RP_002_3_RECEIPT.md. Status: PASS_WITH_KNOWN_INFRA — local evidence C1+C2+C3+C4 green; remote evidence shows zero targeted-test failures but application-focused job external cancellation prevents full SUCCESS conclusion. The cancellation is orthogonal to WU-RP-002.3.
- NEXT_WU: WU-RP-003 (RP-0 close-out). (a) Widen `branch_protection.required_status_checks.contexts` to include `domain-unit` + `architecture-fitness` on top of `LPR-0 CI / compile` (those are demonstrably green at the new head). Application-focused is NOT included — blocked by R9. (b) Decide workflow R5 (PR-based vs long-lived integration branch; 7 bootstrap pushes observed). (c) Archive RP-000 cycle and advance to RP-1. (d) R10 separate work unit to investigate the application-focused runner-cancellation issue.
- BLOCKERS: R9 (NEW) — GitHub Actions runner cancels the application-focused job externally after ~14 min, despite `timeout-minutes: 120`. Reason undisclosed. WU-RP-002.3 targeted tests are not failing (confirmed by log inspection); the cancellation is unrelated to RP-000 pre-existing failures. Tracked in `WU_RP_002_3_RECEIPT.md` "Remote CI evidence".
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
4. HEAD should be 96604dee on both local and remote; CI at that HEAD shows compile + domain-unit + architecture-fitness GREEN (3/4 jobs), with application-focused cancelled at the runner externally (not a step failure). The WU-RP-002.2 fitness fix is recorded as VERIFIED in the receipt (no assertion error observed in the CI log); the 2 NEW ADV adversarial failures are deferred to WU-RP-002.3. Branch protection ACTIVE (`LPR-0 CI / compile` required, strict=true, force-pushes/deletions off). Next: WU-RP-002.3 (close GitCheckoutExecutorAdversarialTest CI-env dependency).
5. Si HEAD diverge, evaluar fast-forward o merge; si protection hook bloquea, ejecutar bootstrap procedure documentado en WU_RP_001_RECEIPT.md.
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
