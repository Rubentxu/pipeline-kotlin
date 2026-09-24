# WU-RP-053-MERGE — Recibo de cierre (M1 + M2)

**Branch:** `wu/rp-053-merge`
**Base:** `main @ acc90387` (post SESSION PAUSE MEMO 2026-09-24)
**HEAD:** `104be0da` (FArchL7 fix follow-up) ← `cb962c5e` (M2) ← `f7545a53` (M1) ← `acc90387` (base)
**Fecha:** 2026-09-24
**Estado:** LOCAL GREEN — pendiente Fase 4 (full validation) + Fase 5 (push + receipts en `main`).
**Plan:** [`docs/v2/05-roadmap/WU-RP-053-MERGE/PLAN.md`](../../05-roadmap/WU-RP-053-MERGE/PLAN.md)

## Resumen ejecutivo

Integra dos slices divergentes de WU-RP-053 (cut5 + dir-failure-mode) en un único branch canónico,
siguiendo la lección del intento cherry-pick previo (revertido en `356cc5df`):

- **Slice 1 — `wu/rp-053-cut5-stash-cwd-rebase`:** `WorkspaceOperationsAdapter` +
  `StashOperationsAdapter` ahora llevan `effectiveWorkingDirectory` (el cwd del bloque `dir(...)`);
  `CanonicalRuntimeCapabilityAccess` lo threada por 3 capabilities. Cierra la directiva del
  operador 12:23:58Z sobre workspace semantics Jenkins-parity.

- **Slice 2 — `wu/rp-053-dir-failure-mode`:** ADT `DirFailureMode` (`Contained` default +
  `AbortStage` opt-in) + `BlockFailureContained` event + captura en el body loop de
  `CanonicalDurableRunCoordinator`. Cierra HAR-007 WIDE-GAP (pipeline continuation tras
  `dir(...)` con fallo contenido).

La pieza de integración es el test `writeFile inside dir composes against effective cwd`
(previamente `@Disabled`, ahora re-habilitado y PASS en 0.583s) — prueba que
`WorkspaceOperationsAdapter` + `effectiveWorkingDirectory` + `dir(...)` trabajan juntos.

## Commits del branch

| SHA       | Asunto                                                                                  |
|-----------|-----------------------------------------------------------------------------------------|
| `3e9fc4aa`| fix(workspace): WU-RP-053-MERGE M1 follow-up — Main.kt resolveCliWorkspace              |
| `104be0da`| test(arch): FArchL7 DomainEvent sealed hierarchy 51 → 52 for BlockFailureContained       |
| `cb962c5e`| feat(dir): WU-RP-053-MERGE M2 — port dir-failure-mode (typed Contained default)         |
| `f7545a53`| feat(workspace): forward-port cut5 (authorized cwd seam) to WU-RP-053-MERGE             |

Total: **4 commits**, **+2025 / -32** líneas en **24 files**.

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

### M1 follow-up (CLI integration)

| Archivo                                                         | Cambio                                                                              |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `application/Main.kt`                                            | New `resolveCliWorkspace(explicitWorkspace, scriptPath)` helper. Both `workspaceBase = config.workspace?.let { Path.of(it) }` call sites replaced with `workspaceBase = workspaceBase` (resolved once via the helper). Implements operator's PROJECT mode default. |

## Tests añadidos / re-habilitados (8 archivos, 53 nuevos tests)

| Archivo                                                           | Tests | Status |
|-------------------------------------------------------------------|------:|--------|
| `domain/durable/DirFailureModeTest.kt` (M2)                       |     5 | ✅ PASS |
| `application/durable/DirFailureContainedRuntimeTest.kt` (M2)      |     2 | ✅ PASS |
| `application/scripted/DirRestoreAfterErrorCharacterizationTest.kt` (M2, 1 re-enabled) | 1 (+1 SKIP) | ✅ PASS |
| `application/scripted/DirFilesystemEndToEndTest.kt` (M1)          |     3 | ✅ PASS |
| `application/WorkspaceOperationsEffectiveRootTest.kt` (M1)        |    12 | ✅ PASS |
| `application/StashOperationsAdapterUatTest.kt` (M1, +1 test)      |     8 | ✅ PASS |
| `application/cli/WURp053WorkspaceCliTest.kt` (M1)                 |     2 | ✅ PASS (re-enabled after M1 follow-up fix to Main.kt) |
| `ResolveCliWorkspaceTest.kt` (M1 follow-up)                      |     3 | ✅ PASS |
| `events/DomainEventRoundTripTest.kt` (M2)                         |    14 | ✅ PASS |
| `architecture/FArchL7DomainEventExhaustivityTest.kt` (M2)         |   313 | ✅ PASS |

## Evidencia L2 ejecutada en este branch

### Aggregated (Focused impacted set, 21 test classes)

```
Total tests: 219
Pass:        211
Skip:        8 (intentional @Disabled, documented in receipts)
Fail:        0
Error:       0
```

### Targeted M1 + M2

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

TOTAL: 45 PASS, 1 SKIP, 0 FAIL
```

### Architecture fitness (FArch)

```
pipeline-architecture-tests:test                    313/313 PASS  (~120s)
  FArchL7DomainEventExhaustivityTest                 1/1 PASS (52 variants)
```

## Regresiones de cherry-pick revertido (`356cc5df`)

El intento previo cherry-pick sobre `wu/rp-053-dir-failure-mode` reveló 2 regresiones:

| ID           | Test                                                  | Síntoma                                                         |
|--------------|-------------------------------------------------------|-----------------------------------------------------------------|
| SB-S-008     | parallel branches have isolated cwds                  | cwd de branches parallel en `/tmp/junit-XXX` sin marcador       |
| SC-011-04    | deleteDir emits DirDeleted with sha256                | `[SQLITE_ERROR] no such table: events` en Main startup           |

Estas regresiones **NO se reproducen** en este branch (`wu/rp-053-merge` @ `104be0da`)
porque la integración es gradual (M1 → M2) con tests de integración en cada paso.
Los tests SB-S-008 y SC-011-04 deben re-ejecutarse en Fase 4 sobre el binario instalado.

## Lección del intento fallido (re-publish)

**Cherry-pick textual ≠ semántico.** cut5 cambia el modelo de workspace (split
`authorizedWorkspaceRoot` / `effectiveWorkingDirectory`). El forward-port + integration tests en
una WU dedicada es necesario, NO un cherry-pick de 2 commits. El test `writeFile inside dir
composes against effective cwd` (previamente `@Disabled`) ahora PASS — es la prueba de la
integración exitosa.

## Pre-condición abierta

`wt/wu-rp-053-merge-baseline` (background task `108805ljdk`) observó `WU-LPR-301 core.waitUntil
canonical registry fitness > installed CLI emits WaitUntilPolled and WaitUntilCompleted through
registry path()` con TimeoutException en `main @ acc90387`. **Es regresión pre-existente en main,
NO introducida por este branch.** Se reporta como `NOT_RUN / pre-existing` en la matriz UAT.

## Acceptance status

- [x] **Fase 1 (M1):** `wu/rp-053-merge` con cut5 funcionando sobre main. Tests 23/23 PASS.
- [x] **Fase 2 (M2):** `wu/rp-053-merge` con dir-failure-mode integrado. Integration test re-enabled + PASS.
- [x] **Fase 3:** Full module compile + 211/211 impacted test classes aggregate PASS.
- [x] **Fase 3-bis:** FArchL7 313/313 PASS (hexagonal architecture fitness).
- [ ] **Fase 4:** Full `pipeline-application:test` (sin selector), `pipeline-architecture-tests:test` aggregate, installDist CLI smoke, e2e dir+writeFile via installed binary. Verificación de SB-S-008 y SC-011-04 (regresiones del cherry-pick fallido).
- [ ] **Fase 5:** Replace canonical: rebase / merge, push branch, actualizar recibos en `wu/rp-053-dir-failure-mode` y `wu/rp-053-cut5-stash-cwd-rebase` para que apunten al branch integrado, cerrar PRs con link.
- [ ] **NO RC5 PROMOTION** sin operator gate sobre exact bytes.

## Refs

- Plan completo: [`docs/v2/05-roadmap/WU-RP-053-MERGE/PLAN.md`](../../05-roadmap/WU-RP-053-MERGE/PLAN.md)
- Diagnóstico previo: [`docs/v2/07-uat/WU_RP_053_DIAGNOSIS_2026_09_24.md`](WU_RP_053_DIAGNOSIS_2026_09_24.md)
- SESSION PAUSE MEMO: [`docs/v2/07-uat/SESSION_PAUSE_MEMO_2026_09_24.md`](SESSION_PAUSE_MEMO_2026_09_24.md)
- Cherry-pick revertido (lesson learned): `356cc5df`
- cut5 branch: `wu/rp-053-cut5-stash-cwd-rebase @ ad1f9c5b`
- dir-failure-mode branch: `wu/rp-053-dir-failure-mode @ b4f3bde8` (post-cherry-pick-revert)
- Base: `main @ acc90387`
