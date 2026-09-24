# WU-RP-053-FOLLOWUP — PLAN: --workspace-mode=project|legacy opt-in flag + re-habilitar WURp053 CLI tests

**Estado:** PLANNED (auto-run, modo AUTO)
**Base:** `wu/rp-053-merge @ c7d6ef01` (release-ready, gate green, push pendiente operador)
**Branch de trabajo:** `wu/rp-053-followup-workspace-mode`
**Criterio roadmap:** RP-3 ROADMAP §3 (coherence contract) + lección del WU-RP-053-MERGE

## Contexto y motivación

WU-RP-053-MERGE ([recibo cerrado](../07-uat/WU_RP_053_MERGE_RECEIPT.md)) integró dos slices
de forma gradual y validada, pero el commit `3e9fc4aa` (Main.kt `resolveCliWorkspace` que
cambiaba el default de `workspaceBase` a `scriptPath.parent`) **rompió 7 UATs pre-existentes**
que asumían el legacy per-stage workspace layout (SB-S-001/SB-006/SB-008/UAT-L7-TC-004/
SC-011-04/fixture10/corpus).

**Decisión del cierre WU-RP-053-MERGE:** revertir el CLI default change (`e08b063e`) +
`@Disabled` `WURp053WorkspaceCliTest` con class-level docs apuntando a que el modelo cut5
(`effectiveWorkingDirectory`) está integrado y tested, pero el **CLI default change queda
detrás de un opt-in flag** para preservar los 7 UATs.

**Este WU entrega ese opt-in flag.** Implementa la directiva del operador 12:23:58Z sobre
**workspace semantics Jenkins-parity** al nivel de CLI, opt-in, sin romper nada existente.

## Estrategia

1. **Flag nuevo `--workspace-mode=project|legacy`** (default = `legacy`)
   - `project`: workspace = directorio del script (PROJECT mode, cut5, Jenkins-parity)
   - `legacy`: workspace = `<control-root>/workspace/<StageName>-<index>` (per-stage, actual)
2. **Sealed ADT `WorkspaceMode`** con 2 variantes
3. **Resolver determinístico** `resolveCliWorkspaceBase(scriptPath, controlRoot, mode)`
4. **`PipelineCliConfig`** gana un campo `workspaceMode: WorkspaceMode`
5. **`runCanonicalPipeline` + `runScriptedFrontend`** aceptan el modo y aplican el resolver
6. **`WURp053WorkspaceCliTest` RE-HABILITADO** — pasa con `--workspace-mode=project`
7. **7 UATs preservados** — siguen pasando con default `--workspace-mode=legacy`

## Acceptance criteria

- [ ] `WorkspaceMode` ADT + 2 variantes (Project + Legacy)
- [ ] `--workspace-mode=project|legacy` flag parseado correctamente
- [ ] Default = `legacy` (preserva comportamiento actual + 7 UATs)
- [ ] `project` mode usa `scriptPath.parent` como workspace base (PROJECT mode cut5)
- [ ] `WURp053WorkspaceCliTest` RE-HABILITADO y los 2 tests PASS
- [ ] **Cero regresión** sobre los 7 UATs pre-existentes (SB-S-001/SB-006/SB-008/UAT-L7-TC-004/SC-011-04/fixture10/corpus)
- [ ] Architecture fitness 313/313 PASS
- [ ] Detekt verde
- [ ] Recibo `WU_RP_053_FOLLOWUP_RECEIPT.md`

## Tests a añadir/re-habilitar

| Archivo | Acción | Tests |
|---|---|---|
| `application/cli/WURp053WorkspaceCliTest.kt` | RE-ENABLE (quitar `@Disabled`) | 2 PASS con `--workspace-mode=project` |
| `application/cli/WorkspaceModeCliTest.kt` (NEW) | NEW | 3 tests: flag parses, default legacy, project mode uses scriptPath.parent |

## Riesgo identificado

| Riesgo | Mitigación |
|---|---|
| Flag --workspace-mode mal parseado en edge cases (typos, mixed-case) | Validación: enum exacto `project` o `legacy`, otro valor → error tipado |
| Default change rompe UATs | Default = legacy (actual comportamiento); opt-in explícito para cambiar |
| Modifica firma de `PipelineCliConfig` → ripple effect | Constructor data class; tests con default param = backward compatible |

## Reglas aplicables

- AGENTS.md §CIERRE REAL: cada fase se cierra solo cuando su evidencia (XML, recibo) está fresca.
- AGENTS.md §TESTING QUIRÚRGICO: solo los tests afectados (WURp053 + nuevos).
- Conventional Commits estricto: cada commit = un cambio lógico atómico.
- Regla 4 del prompt-overlay: SEMVER derivado del historial (este WU es `feat` con opt-in
  flag → MINOR bump candidato si se cierra release, pero SEMVER lo decide el operador).

## Out-of-scope (NO en este WU)

- Cambio del CLI default a `project` (requiere decisión de producto sobre los 7 UATs afectados).
- Re-arquitectura del workspace model — el modelo cut5 ya está integrado (M1 WU-RP-053-MERGE).
- Modificación de `WorkspaceOperationsAdapter` o `StashOperationsAdapter` — fuera de scope.
