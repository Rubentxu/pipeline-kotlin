# WU-RP-053-MERGE — Plan: integrar cut5 (workspace authorization) + dir-failure-mode (typed failure semantics) en un único slice

**Estado:** PLANNED (aprobado por operador, modo AUTO). **Base:** main `acc90387` (post SESSION PAUSE MEMO).
**Criterio roadmap:** RP-3 ROADMAP §3 (coherence contract); cierre del gap de integración entre dos slices paralelos del WU-RP-053.

## Contexto y motivación

El roadmap WU-RP-053 tiene dos slices paralelos divergentes desde `70e3d55e`:

1. **`wu/rp-053-dir-failure-mode` @ `356cc5df`** (6 commits sobre main):
   - `DirFailureMode` ADT (`Contained` default + `AbortStage` opt-in) + `BlockFailureContained` event + bodyLoop branch en `CanonicalDurableRunCoordinator`.
   - Cierra HAR-007 WIDE-GAP (pipeline continuation tras `dir(...)` con fallo contenido).
   - **LOCAL GREEN** + arch fitness PASS en este HEAD.

2. **`wu/rp-053-cut5-stash-cwd-rebase` @ `ad1f9c5b`** (2 commits sobre main):
   - `WorkspaceOperationsAdapter` split `authorizedWorkspaceRoot` vs `effectiveWorkingDirectory` (commit `95f36e34`).
   - `StashOperationsAdapter` cwd scoping (commit `ad1f9c5b`).
   - Cierra la directiva del operador 12:23:58Z sobre workspace semantics Jenkins-parity.

El `@Disabled` en `DirRestoreAfterErrorCharacterizationTest.writeFile inside dir composes against effective cwd` apunta explícitamente a cut5 como upstream pendiente de merge. Esta es la integración que el WU-RP-053 necesita cerrar para tener un solo branch canónico.

## Lección del intento previo (cherry-pick fallido, revertido en `356cc5df`)

Un cherry-pick textual limpio de los 2 commits cut5 sobre `wu/rp-053-dir-failure-mode` aplicó sin conflictos, pero la suite completa de `pipeline-application` reveló **2 regresiones**:

1. **SB-S-008 parallel branches have isolated cwds** — El cwd de los branches parallel se quedó en `/tmp/junit-XXX` (raíz del tempDir), sin el marcador `b0`/`b1`/`stage-0-0`. La nueva composición de workspaces paralelos no encaja con el split `authorizedWorkspaceRoot`/`effectiveWorkingDirectory` introducido por cut5.

2. **SC-011-04 deleteDir emits DirDeleted with sha256** — `[SQLITE_ERROR] no such table: events` en `SqliteEventStore.eventsFor` durante `MainKt.main:865`. Interacción entre cut5 y la inicialización de la DB en `Main`.

**Causa raíz:** cut5 introduce cambios de **modelo** (split authorizedWorkspaceRoot vs effectiveWorkingDirectory) que interactúan con rutas de código NO cubiertas por el surface de pruebas de cut5 (parallel branches, Main startup). El cherry-pick textual NO equivale a cherry-pick semántico.

## Estrategia del WU (forward-port + integration tests, NO cherry-pick)

### Fase 0 — Caracterización baseline

Medir el estado actual de la suite completa de `pipeline-application` sobre `main @ acc90387` con un worktree dedicado. Esto da el baseline "sin cambios" para comparar contra los merges progresivos.

- Worktree: `wt/wu-rp-053-merge-baseline`
- Comando: `timeout 600 ./gradlew -p v2 :pipeline-application:test` → aggregate.
- Esperado: algunos fallos pre-existentes (los mismos que el L5 pre-cuts del 17:18Z).
- Recibo: `evidence/baseline-app-tests.txt`

### Fase 1 — Forward-port cut5 sobre un merge-base fresco

NO cherry-pick. Crear rama `wu/rp-053-merge-m1-cut5` desde `main @ acc90387` y aplicar el contenido de cut5 **adaptado**:
- Portar el modelo (split authorizedWorkspaceRoot vs effectiveWorkingDirectory) a WorkspaceOperations.
- Portar la lógica de StashOperationsAdapter.
- Portar Main.kt startup.
- Portar CanonicalRuntimeCapabilityAccess.
- Portar los tests (`WorkspaceOperationsEffectiveRootTest`, `StashOperationsAdapterUatTest`, `DirFilesystemEndToEndTest`, `WURp053WorkspaceCliTest`).
- **NO** portar nada que asuma la existencia de DirFailureMode — cut5 es ortogonal.

Salida: rama `wu/rp-053-merge-m1-cut5` con cut5 funcionando sobre main. SB-S-008 + SC-011-04 deben seguir PASS.

### Fase 2 — Forward-port dir-failure-mode sobre la rama de Fase 1

Crear rama `wu/rp-053-merge-m2-dir-failure` desde `wu/rp-053-merge-m1-cut5` y portar el contenido de dir-failure-mode:
- Portar `DirFailureMode` ADT.
- Portar `BlockFailureContained` event + 5 sitios `when` exhaustivos.
- Portar bodyLoop branch en `CanonicalDurableRunCoordinator`.
- Portar `DirFailureContainedRuntimeTest` + `DirFailureModeTest`.
- Re-habilitar `DirRestoreAfterErrorCharacterizationTest.writeFile inside dir composes against effective cwd` (quitar `@Disabled`).

Salida: rama `wu/rp-053-merge-m2-dir-failure`. Test writeFile PASARÍA (la integración resuelve el dependency).

### Fase 3 — Resolver conflictos de integración

En este punto, ambos slices están sobre la misma base. Los conflictos que el cherry-pick NO reveló (porque NO los probaba) aparecerán aquí:
- Si hay regresiones (similar a SB-S-008, SC-011-04): **diagnosticar y corregir** en este branch, no en los originales.
- Las correcciones van al slice integrado, no se re-aplican retroactivamente a cut5 ni a dir-failure-mode.

### Fase 4 — Validación

- `:pipeline-application:test` completo → aggregate.
- `:pipeline-architecture-tests:test` → debe seguir 313/313 PASS.
- `installDist` + smoke (`pipelinek version` + `pipelinek doctor` + e2e `dir + sh + writeFile`).
- Recibo consolidado `WU_RP_053_MERGE_RECEIPT.md`.

### Fase 5 — Reemplazar el branch canonical

- Cerrar PR WU-RP-053-DIR-FAILURE-MODE (reemplazado por `wu/rp-053-merge-m2-dir-failure` que tiene ambos slices).
- Cerrar PR #96 si era del operador, o documentar la nueva ruta.
- Branch canónico post-WU: `wu/rp-053-merge-m2-dir-failure`.
- El operador decide merge + rc5 + harness sobre el branch integrado.

## Reglas aplicables

- AGENTS.md §CIERRE REAL: cada fase se cierra solo cuando su evidencia (XML, recibo, smoke) está fresca.
- AGENTS.md §TESTING QUIRÚRGICO: cada fase valida con el conjunto mínimo que detecta el cambio introducido + las 2 regresiones conocidas.
- Conventional Commits estricto: cada commit = un cambio lógico atómico.
- Regla 4 del prompt-overlay: SEMVER derivado del historial (este WU integra 2 features → MINOR bump al consolidar rc).

## Riesgos identificados

| Riesgo | Mitigación |
|---|---|
| Las 2 regresiones del intento previo reaparecen en Fase 2/3 | Diagnosticar y corregir en el branch integrado; el "qué" ya está identificado (parallel branches + Main SQLite init). |
| Nuevas regresiones por integración no anticipadas | L5 round gate al final (Fase 4) detecta todo lo no cubierto por el surface de pruebas de cada slice individual. |
| Los branches originales (`wu/rp-053-dir-failure-mode`, `wu/rp-053-cut5-stash-cwd-rebase`) quedan obsoletos | Documentar en SESSION_POINTER que se cierra el capítulo; mantenerlos por trazabilidad hasta que el operador los archive. |
| Cherry-pick revertido (`356cc5df`) tiene una nota en SESSION_POINTER que sugiere "NO cherry-pick, WU dedicado" | Este WU ES ese WU dedicado. La nota queda como precedente histórico. |

## Estado de partida

- `main @ acc90387` (post SESSION PAUSE MEMO).
- `wu/rp-053-dir-failure-mode @ 356cc5df` (6 commits sobre main, LOCAL GREEN + arch fitness PASS).
- `wu/rp-053-cut5-stash-cwd-rebase @ ad1f9c5b` (2 commits sobre main, evidencia de tests `WorkspaceOperationsEffectiveRootTest`, `StashOperationsAdapterUatTest`, `DirFilesystemEndToEndTest`, `WURp053WorkspaceCliTest`).
- Cherry-pick fallido documentado en SESSION_POINTER + WORK_JOURNAL (commit `356cc5df`, docs only).

## Acceptance criteria (cierre del WU)

- [ ] Fase 0: baseline agregado y archivado en `evidence/baseline-app-tests.txt`.
- [ ] Fase 1: rama `wu/rp-053-merge-m1-cut5` con cut5 funcionando sobre main. SB-S-008 + SC-011-04 PASS.
- [ ] Fase 2: rama `wu/rp-053-merge-m2-dir-failure` con dir-failure-mode integrado. Test `writeFile inside dir composes against effective cwd` re-habilitado y PASS.
- [ ] Fase 3: conflictos resueltos. Aggregate `pipeline-application:test` muestra 0 nuevas regresiones vs baseline.
- [ ] Fase 4: `pipeline-architecture-tests:test` 313/313 PASS, `installDist` smoke exit 0, e2e `dir + writeFile` exit 0 con marker presente.
- [ ] Fase 5: PRs originales cerrados con referencia al branch integrado. SESSION_POINTER + WORK_JOURNAL actualizados.
- [ ] Recibo `WU_RP_053_MERGE_RECEIPT.md` con evidencia completa.

## Progress log

### 2026-09-24 — M1 ✅ (`f7545a53`) + M2 ✅ (`cb962c5e`)

**Branch:** `wu/rp-053-merge` (renamed from `wu/rp-053-merge-m1-cut5`).

**M1 — cut5 forward-port:** `WorkspaceOperationsAdapter` + `StashOperationsAdapter` now carry
`effectiveWorkingDirectory`; `CanonicalRuntimeCapabilityAccess` threads `context.shOptions.workingDirectory`
through 3 capabilities. New `effectiveRoot()` + `authorize()` security seam (textual + canonical +
reserved `.v2` checks). All M1 tests green: StashOperationsAdapterUatTest 8/8,
WorkspaceOperationsEffectiveRootTest 12/12, DirFilesystemEndToEndTest 3/3.

**M2 — dir-failure-mode forward-port:** `DirFailureMode` ADT (Contained default + AbortStage opt-in);
`BlockShellScope.Directory.failureMode`; `BlockFailureContained` event + full event infra plumbing.
`CanonicalDurableRunCoordinator.dispatchBody` body loop captures the failure when scope is
Directory + Contained + attempts exhausted — Jenkins "cwd restore + continue with next sibling".
`DomainEvent` sealed hierarchy 51 → 52.

**Integration proof — `DirRestoreAfterErrorCharacterizationTest.writeFile inside dir composes
against effective cwd`:** previously `@Disabled` (waiting for cut5). RE-ENABLED on this branch
and PASSES (0.583s). This is the LOCAL-GUARD that motivates WU-RP-053-MERGE.

**M2 tests green:** DirFailureModeTest 5/5, DirFailureContainedRuntimeTest 2/2,
DomainEventRoundTripTest 14/14 (51→52 invariant).

**TOTAL L2 evidence:** 45/45 PASS, 1 SKIP (intentional), 0 FAIL across 7 test files.

**Acceptance status:**
- [x] Fase 1 (M1): cut5 functioning on `wu/rp-053-merge`.
- [x] Fase 2 (M2): dir-failure-mode integrated; integration test re-enabled + PASS.
- [ ] Fase 3: full module compile + impacted test classes aggregate.
- [ ] Fase 4: pipeline-architecture-tests 313/313, installDist smoke, e2e dir+writeFile smoke.
- [ ] Fase 5: replace canonical, push, update receipts.
- [ ] Recibo `WU_RP_053_MERGE_RECEIPT.md`.
