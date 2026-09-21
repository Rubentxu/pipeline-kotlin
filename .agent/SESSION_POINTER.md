# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-21T11:04Z. **Tipo de cambio de esta sesión:** WU-RP-002 cierre — inventory regenerated (20 Core + 10 SDK + 1 External = 31 keys) + 3 pre-existing CI failures closed (FArchL7 48→51, Lfc0V1 archived path, ConcurrentStepDispatcherTest flake hardening). 2 NEW flaky SQLite tests surfaced in CI (Lpr041 + EventHistoryContract); pre-existing at 8b5f41bf; deferred to WU-RP-002.1.
**Código auditado:** main @ 6822eff1f9acb2da050f5c0a4f4b9a9c1a741bbb (post WU-RP-002, base 4f3451f2, base-base 7fd407ef).
**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.
**Cabeza actual:** `git rev-parse HEAD` → 6822eff1 (local + remote). CI compile + arch-fitness VERIFIED SUCCESS at 6822eff1 via run 35591353345; domain-unit FAILURE due to 2 new flaky SQLite tests deferred to WU-RP-002.1.

## Estado operativo

- ACTIVE_PHASE: RP-0 — CI reproducible y verdad del inventario.
- LAST_CLOSED_WU: WU-RP-002 — inventory regenerated (20 Core + 10 SDK + 1 External = 31 keys) + 3 pre-existing CI failures closed (FArchL7 48→51, Lfc0V1 archived path, ConcurrentStepDispatcherTest flake). Receipt: docs/v2/07-uat/WU_RP_002_RECEIPT.md. Status: PASS_WITH_KNOWN_FAILURES (3 closed + 2 new flaky SQLite tests deferred to WU-RP-002.1). New script .agent/scripts/regenerate_step_inventory.py is the source of truth for Step counts (DRIFT_COUNT=0). Local L4 (309+554+178+30 = 1071 tests) ALL GREEN. CI run 35591353345 at 6822eff1: compile SUCCESS, arch-fitness SUCCESS (FArchL7 + Lfc0V1 verified remotely), domain-unit FAILURE (2 NEW pre-existing flaky SQLite tests surfaced).
- NEXT_WU: WU-RP-002.1 — close 2 newly surfaced flaky SQLite tests (Lpr041DurableSequenceRepairTest + EventHistoryContractTest.projection carries STORE-assigned sequence). Both use SqliteEventStore with Files.createTempDirectory; recommended fix: @TempDir + try/finally cleanup to make them robust under CI filesystem pressure. After WU-RP-002.1 closes, WU-RP-003 widens protection to include domain-unit + architecture-fitness.
- BLOCKERS: 2 pre-existing flaky SQLite tests (Lpr041 + EventHistoryContract) surfaced by CI in run 35591353345; pre_existing_at=8b5f41bf; deferred to WU-RP-002.1. Branch protection widening deferred until WU-RP-002.1 closes (otherwise main becomes unmergeable).
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
4. HEAD should be 6822eff1 on both local and remote; CI compile + arch-fitness should be GREEN at 6822eff1 via run 35591353345. Branch protection ACTIVE (`LPR-0 CI / compile` required, strict=true, force-pushes/deletions off). Next: WU-RP-002.1 (close 2 flaky SQLite tests; widen protection afterwards).
5. Si HEAD diverge, evaluar fast-forward o merge; si protection hook bloquea, ejecutar bootstrap procedure documentado en WU_RP_001_RECEIPT.md.
6. Si no hay permisos para editar reglas de protección, registrar BLOCKED_EXTERNAL y dejar pendiente el check requerido, nunca green by inspection.

## Handoff transaccional

Antes de terminar una sesión actualizar en UN mismo cambio: este puntero (estado, último SHA observado, nueva WU, riesgos, primer comando), el diario (entrada append-only), TESTING-STATE solo en las partes de impacto vigente, y un nuevo receipt de resultados. En fracaso, mantener la WU activa y el gate abierto. Si puntero/CI/Git contradicen un handoff, Git/CI gobiernan lo observado y se escribe la corrección explícita.
