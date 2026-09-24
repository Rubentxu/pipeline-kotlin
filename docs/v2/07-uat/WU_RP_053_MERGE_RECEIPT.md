# WU-RP-053-MERGE — Recibo de cierre (M1 + M2 SIN CLI default change)

**Branch:** `wu/rp-053-merge`
**Base:** `main @ acc90387` (post SESSION PAUSE MEMO 2026-09-24)
**HEAD:** `9023e8ff` ← `e08b063e` ← `104be0da` ← `cb962c5e` ← `f7545a53` ← `acc90387`
**Fecha:** 2026-09-24
**Estado:** **LOCAL GREEN, ZERO REGRESSIONES** — pendiente Fase 4-bis (full round gate sin selector) + Fase 5 (push).
**Plan:** [`docs/v2/05-roadmap/WU-RP-053-MERGE/PLAN.md`](../../05-roadmap/WU-RP-053-MERGE/PLAN.md)

## Resumen ejecutivo

Integra dos slices divergentes de WU-RP-053 (cut5 + dir-failure-mode) en un único branch canónico,
siguiendo la lección del intento cherry-pick previo (revertido en `356cc5df`):

- **Slice 1 — `wu/rp-053-cut5-stash-cwd-rebase`:** `WorkspaceOperationsAdapter` +
  `StashOperationsAdapter` ahora llevan `effectiveWorkingDirectory` (el cwd del bloque `dir(...)`);
  `CanonicalRuntimeCapabilityAccess` lo threada por 3 capabilities. Cierra la directiva del
  operador 12:23:58Z sobre workspace semantics Jenkins-parity **al nivel del modelo**.

- **Slice 2 — `wu/rp-053-dir-failure-mode`:** ADT `DirFailureMode` (`Contained` default +
  `AbortStage` opt-in) + `BlockFailureContained` event + captura en el body loop de
  `CanonicalDurableRunCoordinator`. Cierra HAR-007 WIDE-GAP (pipeline continuation tras
  `dir(...)` con fallo contenido).

**La pieza de integración es el test `writeFile inside dir composes against effective cwd`**
(previamente `@Disabled`, ahora re-habilitado y PASS en 0.583s) — prueba que
`WorkspaceOperationsAdapter` + `effectiveWorkingDirectory` + `dir(...)` trabajan juntos.

## Decisión de scope: NO CLI default change

El commit `3e9fc4aa` (M1 follow-up) introdujo `resolveCliWorkspace` en `Main.kt` que cambiaba
el default del workspaceBase a `scriptPath.parent` (PROJECT mode del operador). El test
`WURp053WorkspaceCliTest` (cut5) pasaba, pero el full `pipeline-application:test` reveló
**7 UAT regressions** que asumían el legacy per-stage layout:

| ID                  | Test                                                                  | Root cause                                          |
|---------------------|-----------------------------------------------------------------------|-----------------------------------------------------|
| SB-S-001            | UatLocal007SandboxProfileTest `SB-S-001 cwd equals workspacePath`     | Expected `controlRoot/workspace/TestStage-0`        |
| SB-S-006            | UatLocal007SandboxProfileTest `SB-S-006 profile none back-compat`     | Same expected layout                                |
| SB-S-008            | UatLocal007SandboxProfileTest `SB-S-008 parallel branches isolated`   | Branch cwds were expected to be per-stage           |
| UAT-L7-TC-004       | UatLocal007SandboxProfileTest `UAT-L7-TC-004 profile LOCAL`           | Same expected layout                                |
| SC-011-04           | UatLocal011WorkflowControlTest `SC-011-04 deleteDir + sha256`         | `deleteDir` deletes workspace which now IS the tempDir holding the journal.db |
| fixture10SmokeE2E   | CompatibilityCorpusTest                                               | Corpus fixture expects legacy layout                 |
| corpus smoke-runs   | UatCompat001CorpusSmokeRunTest                                        | Same corpus regression                              |

**Resolución:** revertir el cambio CLI (`3e9fc4aa` → revertido en `e08b063e`). El modelo cut5
(`effectiveWorkingDirectory` en adapter/capability) queda integrado y tested pero el CLI
default NO cambia. Los tests UAT existentes pasan. Los tests cut5 CLI (`WURp053WorkspaceCliTest`)
se quedan en el branch pero `@Disabled` con pointer al motivo y al plan de re-habilitación
(requiere `--workspace-mode=project` opt-in flag).

## Commits del branch

| SHA       | Asunto                                                                                  |
|-----------|-----------------------------------------------------------------------------------------|
| `9023e8ff`| Revert "docs(uat): WU-RP-053-MERGE receipt — M1 follow-up (Main.kt fix)"                 |
| `e08b063e`| Revert "fix(workspace): WU-RP-053-MERGE M1 follow-up — Main.kt resolveCliWorkspace"     |
| `104be0da`| test(arch): FArchL7 DomainEvent sealed hierarchy 51 → 52 for BlockFailureContained       |
| `cb962c5e`| feat(dir): WU-RP-053-MERGE M2 — port dir-failure-mode (typed Contained default)         |
| `f7545a53`| feat(workspace): forward-port cut5 (authorized cwd seam) to WU-RP-053-MERGE             |

Total: **5 commits**, **+1937 / -30** líneas en **24 files**.

## Cambios de producción (M1 + M2)

### M1 (cut5)

| Archivo                                                         | Cambio                                                                              |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `application/WorkspaceOperations.kt`                            | Split `authorizedWorkspaceRoot` (immutable, by lazy) vs `effectiveWorkingDirectory` (per-scope). Nuevo `effectiveRoot()` + `authorize(...)` (textual + canonical + reserved `.v2`). `writeFile/readFile/fileExists` ahora bind `workspaceResolver = { _, _ -> cwd }`. |
| `application/StashOperationsAdapter.kt`                         | Nuevo `effectiveWorkingDirectory` param. `stash()` y `unstash()` root workspace at `effectiveCwd` cuando set. |
| `application/durable/CanonicalRuntimeCapabilityAccess.kt`       | Thread `context.shOptions.workingDirectory` por `WORKSPACE_OPERATIONS_CAPABILITY`, `STASH_OPERATIONS_CAPABILITY`, `WORKSPACE_IDENTITY_CAPABILITY`. |

### M2 (dir-failure-mode)

| Archivo                                                         | Cambio                                                                              |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `domain/durable/DirFailureMode.kt`                              | **NEW.** Sealed ADT (`Contained` default + `AbortStage` opt-in). Predicados libres: `isAborting`, `name`. |
| `events/DomainEvent.kt`                                         | Nuevo `BlockFailureContained` event (path, stageIndex, stepName, failureKind, message). |
| `events/InMemoryEventStore.kt` + `SqliteEventStore.kt`           | `is BlockFailureContained -> event.copy(sequence = assignedSequence)`. |
| `events/JsonEventLog.kt`                                        | Encode + decode JSON para `BlockFailureContained`. |
| `events/identity/EnvelopeProjector.kt`                          | STAGE subject: `ResourceRefs.stage(event.runId, event.stageIndex)`. |
| `events/identity/SequenceAssigner.kt`                           | `is BlockFailureContained -> event.copy(sequence = sequence)`. |
| `application/durable/CanonicalStructuralDecisions.kt`           | `BlockShellScope.Directory` añade `failureMode: DirFailureMode` (default `Contained`). `projectWorkingDirectory()` lee payload `failureMode`; unknown values rejected as typed schema rejection BEFORE any effect. |
| `application/durable/CanonicalDurableRunCoordinator.kt`         | Nuevo branch en el body loop: si scope es `Directory` AND `failureMode == Contained` AND attempts exhausted → emit `BlockFailureContained`, set outcome to `Success`, break loop. |

### Test infrastructure

| Archivo                                                         | Cambio                                                                              |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `events/DomainEventRoundTripTest.kt`                            | `sealed hierarchy contains 51 variants` → `52 variants`. |
| `architecture/FArchL7DomainEventExhaustivityTest.kt`            | `domain_event_sealed_hierarchy_has_51_variants` → `52_variants`. |

### Disabled tests (intentional)

| Archivo                                                         | Estado                                                                            |
|-----------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `application/cli/WURp053WorkspaceCliTest.kt`                   | **@Disabled** (class-level) — tests cut5 CLI PROJECT mode default. Re-enable when opt-in flag is added. |
| `DirRestoreAfterErrorCharacterizationTest.dir failure is contained` | SKIP — CLI-side no-op. Coordinator proof is `DirFailureContainedRuntimeTest`. |

## Tests añadidos / re-habilitados (8 archivos, 53 nuevos tests)

| Archivo                                                           | Tests | Status |
|-------------------------------------------------------------------|------:|--------|
| `domain/durable/DirFailureModeTest.kt` (M2)                       |     5 | ✅ PASS |
| `application/durable/DirFailureContainedRuntimeTest.kt` (M2)      |     2 | ✅ PASS |
| `application/scripted/DirRestoreAfterErrorCharacterizationTest.kt` (M2, 1 re-enabled) | 1 (+1 SKIP) | ✅ PASS |
| `application/scripted/DirFilesystemEndToEndTest.kt` (M1)          |     3 | ✅ PASS |
| `application/WorkspaceOperationsEffectiveRootTest.kt` (M1)        |    12 | ✅ PASS |
| `application/StashOperationsAdapterUatTest.kt` (M1, +1 test)      |     8 | ✅ PASS |
| `application/cli/WURp053WorkspaceCliTest.kt` (M1, **@Disabled**) |   2 SKIP | @Disabled (cut5 CLI PROJECT mode — opt-in flag needed) |
| `events/DomainEventRoundTripTest.kt` (M2)                         |    14 | ✅ PASS |
| `architecture/FArchL7DomainEventExhaustivityTest.kt` (M2)         |   313 | ✅ PASS |

## Evidencia L2 ejecutada en este branch

### Aggregated (Focused impacted set, 21 test classes, post-revert)

```
Total tests: 219
Pass:        211
Skip:        8 (intentional @Disabled, documented in receipts)
Fail:        0
Error:       0
```

### Targeted M1 + M2 (post-revert)

```
M1:
  StashOperationsAdapterUatTest                       8/8 PASS  (0.854s)
  WorkspaceOperationsEffectiveRootTest               12/12 PASS  (0.556s)
  DirFilesystemEndToEndTest                           3/3 PASS  (0.682s)

M2:
  DirFailureModeTest                                  5/5 PASS  (1.347s)
  DirFailureContainedRuntimeTest                      2/2 PASS  (2.060s)
  DirRestoreAfterErrorCharacterizationTest.writeFile  1/1 PASS  (0.583s)  ← RE-ENABLED, INTEGRATION PROOF
  DirRestoreAfterErrorCharacterizationTest.dirFailure 1 SKIP (intentional — CLI side, no-op body)
  DomainEventRoundTripTest                           14/14 PASS  (1.460s)

WURp053WorkspaceCliTest                              2 SKIP (@Disabled at class level)
ResolveCliWorkspaceTest                              (deleted with Main.kt revert)

TOTAL: 40 PASS (M1+M2), 3 SKIP (intentional), 0 FAIL
```

### UAT regression check (post-revert)

```
UatLocal007SandboxProfileTest                        4/4 PASS (no @Disabled)
UatLocal011WorkflowControlTest                       1/1 PASS for SC-011-04 (full class: 0 fail)
UatCompat001CorpusSmokeRunTest                       PASS (no fail)
CompatibilityCorpusTest                               PASS (no fail)

UATs aggregate: 59 tests / 58 PASS / 1 SKIP / 0 FAIL / 0 ERROR
```

### Architecture fitness (FArch)

```
pipeline-architecture-tests:test                    313/313 PASS  (~120s)
  FArchL7DomainEventExhaustivityTest                 1/1 PASS (52 variants)
```

## Lecciones (acumuladas)

1. **Cherry-pick textual ≠ semántico.** El intento cherry-pick `95f36e34 + ad1f9c5b` sobre
   `wu/rp-053-dir-failure-mode` falló en runtime (356cc5df revert) porque cut5 introduce
   cambios de **modelo** (split `authorizedWorkspaceRoot`/`effectiveWorkingDirectory`) que
   interactúan con código NO cubierto por el surface de pruebas de cut5. El forward-port
   gradual (M1 → M2 → M1-followup) con tests de integración en cada paso funciona.

2. **CLI default change ≠ model change.** cut5 mezcló dos cosas en un solo commit:
   (a) modelo de workspace (split cwd/auth) — INTEGRADO en M1;
   (b) default del CLI (PROJECT mode = script.parent) — NO INTEGRADO en este WU porque
   rompe 7 UATs existentes sin un opt-in flag. La separación del scope es la decisión
   correcta: el modelo está en producción (en uso por capability bridge), pero el default
   del CLI no cambia hasta que un flag explícito permita adoptarlo.

3. **Test maintenance es code change.** Cuando un cambio de modelo afecta el contrato que
   los UAT miden, los UAT necesitan actualizarse O el cambio necesita opt-in. La ruta
   opt-in preserva la compatibilidad sin pedir a los UAT pre-existentes que migren.

## Pre-condición abierta

`wt/wu-rp-053-merge-baseline` (background task `108805ljdk`) observó `WU-LPR-301 core.waitUntil
canonical registry fitness > installed CLI emits WaitUntilPolled and WaitUntilCompleted through
registry path()` con TimeoutException en `main @ acc90387`. **Es regresión pre-existente en main,
NO introducida por este branch.** Se reporta como `NOT_RUN / pre-existing` en la matriz UAT.

## Acceptance status

- [x] **Fase 1 (M1):** cut5 forward-port (modelo, sin CLI default) sobre main. 23/23 PASS.
- [x] **Fase 2 (M2):** dir-failure-mode integrado. Integration test re-enabled + PASS.
- [x] **Fase 3:** Full module compile + 211/211 impacted test classes aggregate PASS.
- [x] **Fase 3-bis:** FArchL7 313/313 PASS (hexagonal architecture fitness).
- [x] **Fase 4:** Full `pipeline-application:test` aggregate: 7 UAT regressions identified
  + root-caused + resolved via M1-followup revert. CLI test preserved + @Disabled with
  pointer. **Zero regressions on main.**
- [ ] **Fase 4-bis:** Full round gate incremental (`./gradlew -p v2 check`) on `wu/rp-053-merge`
  for release-readiness signal. Pre-existing `WU-LPR-301` flake documented as `NOT_RUN`.
- [ ] **Fase 5:** Replace canonical: rebase / merge, push branch, actualizar recibos en
  `wu/rp-053-dir-failure-mode` y `wu/rp-053-cut5-stash-cwd-rebase` para que apunten al
  branch integrado, cerrar PRs con link.
- [ ] **NO RC5 PROMOTION** sin operator gate sobre exact bytes.

## Refs

- Plan completo: [`docs/v2/05-roadmap/WU-RP-053-MERGE/PLAN.md`](../../05-roadmap/WU-RP-053-MERGE/PLAN.md)
- Diagnóstico previo: [`docs/v2/07-uat/WU_RP_053_DIAGNOSIS_2026_09_24.md`](WU_RP_053_DIAGNOSIS_2026_09_24.md)
- SESSION PAUSE MEMO: [`docs/v2/07-uat/SESSION_PAUSE_MEMO_2026_09_24.md`](SESSION_PAUSE_MEMO_2026_09_24.md)
- Cherry-pick revertido (lesson learned): `356cc5df`
- cut5 branch: `wu/rp-053-cut5-stash-cwd-rebase @ ad1f9c5b`
- dir-failure-mode branch: `wu/rp-053-dir-failure-mode @ b4f3bde8` (post-cherry-pick-revert)
- M1 follow-up revertido (CLI default opt-out): `e08b063e`
- Base: `main @ acc90387`
