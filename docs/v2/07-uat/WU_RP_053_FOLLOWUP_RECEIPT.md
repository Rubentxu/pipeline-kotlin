# WU-RP-053-FOLLOWUP — Recibo de cierre

**Branch:** `wu/rp-053-followup-workspace-mode`
**Base:** `wu/rp-053-merge @ c7d6ef01` (release-ready, gate green, push pendiente operador)
**HEAD:** `c0d799d8` ← `9bc154db` ← `eac074b1` ← `c7d6ef01`
**Fecha:** 2026-09-24
**Estado:** **LOCAL GREEN, CERO REGRESIONES**, pendiente decisión de promoción.
**Plan:** [`docs/v2/05-roadmap/WU-RP-053-FOLLOWUP/PLAN.md`](../05-roadmap/WU-RP-053-FOLLOWUP/PLAN.md)

## Resumen ejecutivo

WU-RP-053-MERGE dejó como follow-up explícito: **implementar `--workspace-mode=project|legacy`
opt-in flag** que permita a los usuarios adoptar la semántica cut5 (workspace = script's
parent, Jenkins-parity) sin romper los 7 UATs pre-existentes que asumen el legacy per-stage
layout.

**Este WU entrega ese flag.** 3 commits atómicos, +329/-22 líneas en 4 archivos.

## Decisión de scope

El operador (12:23:58Z) pidió semántica Jenkins-parity al nivel del modelo. cut5 intentó
entregarla al nivel CLI por defecto en el commit `3e9fc4aa` (Main.kt `resolveCliWorkspace`),
pero rompió 7 UATs. La decisión correcta:

- **Modelo cut5 (`effectiveWorkingDirectory` threaded por capability bridge)**: integrado en M1
  WU-RP-053-MERGE, tested, en producción.
- **CLI default change (workspaceBase = scriptPath.parent)**: NO integrado por defecto; se
  entrega como **opt-in** detrás de `--workspace-mode=project`.

## Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `application/Main.kt` | `+82/-2` líneas: ADT `WorkspaceMode`, parser flag `--workspace-mode`, resolver `resolveCliWorkspaceBase`, integración en 2 call sites de `runCanonicalPipeline`, exception `WorkspaceModeUnsupportedException` |

**Forma del API público (sealed ADT + pure resolver):**

```kotlin
sealed interface WorkspaceMode {
    data object Legacy : WorkspaceMode
    data object Project : WorkspaceMode

    companion object {
        fun parse(value: String): WorkspaceMode? = when (value) {
            "legacy" -> Legacy
            "project" -> Project
            else -> null
        }
    }
}

fun resolveCliWorkspaceBase(
    explicitWorkspace: String?,
    scriptPath: Path,
    mode: WorkspaceMode,
): Path? = when {
    explicitWorkspace != null -> Path.of(explicitWorkspace).toAbsolutePath()
    mode is WorkspaceMode.Project -> scriptPath.toAbsolutePath().parent
    else -> null
}
```

**Línea CLI:**

```bash
# Legacy mode (default) — preserva los 7 UATs pre-existentes
pipelinek run --db /tmp/db.sqlite script.pipeline.kts

# Project mode (opt-in) — workspace = script's parent (Jenkins-parity, cut5)
pipelinek run --db /tmp/db.sqlite --workspace-mode project script.pipeline.kts

# Explicit workspace (gana sobre el mode)
pipelinek run --db /tmp/db.sqlite --workspace /srv/ws script.pipeline.kts
```

### Tests

| Archivo | Cambio | Tests |
|---|---|---|
| `application/cli/WorkspaceModeCliTest.kt` (NEW) | NEW | 11 tests: resolver matrix (4) + `WorkspaceMode.parse` (3) + `parseCliArgs` end-to-end (4) |
| `application/cli/WURp053WorkspaceCliTest.kt` | RE-ENABLED + 3 invocaciones actualizadas con `--workspace-mode project` | 2 tests cut5 ahora PASS |

## Evidencia

### Targeted (impacted set)

```
WorkspaceModeCliTest                    11/11 PASS  (1.5s)
WURp053WorkspaceCliTest                  2/2 PASS   (25s — installs + runs binary 4×)

TOTAL: 13 PASS / 0 FAIL / 0 ERROR
```

### Regresión sobre los 7 UATs pre-existentes (DEFAULT = legacy)

```
UatLocal007SandboxProfileTest            4/4 PASS (SB-S-001/SB-S-006/SB-S-008/UAT-L7-TC-004)
UatLocal011WorkflowControlTest           1/1 PASS for SC-011-04 (full class: 0 fail)
UatCompat001CorpusSmokeRunTest           PASS (no fail)
CompatibilityCorpusTest                  PASS (no fail; fixture10SmokeE2E + 14 corpus fixtures)

TOTAL: 58 PASS / 1 SKIP / 0 FAIL / 0 ERROR
```

### Architecture fitness

```
pipeline-architecture-tests:test         313/313 PASS  (1m 58s)
```

### Detekt

```
:pipeline-application:detekt            PASS  (no warnings introduced)
```

## Commits del branch

| SHA       | Asunto |
|-----------|--------|
| `c0d799d8`| docs(roadmap): WU-RP-053-FOLLOWUP plan (--workspace-mode opt-in flag) |
| `9bc154db`| test(cli): re-enable WURp053WorkspaceCliTest with --workspace-mode=project |
| `eac074b1`| feat(cli): add --workspace-mode opt-in flag + WorkspaceMode sealed ADT |

Total: **3 commits**, **+329 / -22** líneas en **4 archivos**.

## Aceptación de criterios

- [x] `WorkspaceMode` ADT + 2 variantes (Project + Legacy)
- [x] `--workspace-mode=project|legacy` flag parseado correctamente
- [x] Default = `legacy` (preserva los 7 UATs)
- [x] `project` mode usa `scriptPath.parent` como workspace base (Jenkins-parity)
- [x] `WURp053WorkspaceCliTest` RE-HABILITADO y los 2 tests PASS
- [x] **Cero regresión** sobre los 7 UATs pre-existentes
- [x] Architecture fitness 313/313 PASS
- [x] Detekt verde
- [x] Recibo `WU_RP_053_FOLLOWUP_RECEIPT.md`

## Lección integrada

WU-RP-053-MERGE intentó entregar la semántica Jenkins-parity al nivel CLI por defecto
(commit `3e9fc4aa`). El cambio era funcionalmente correcto PERO rompía 7 UATs pre-existentes
que asumían el legacy per-stage layout. La lección: **CLI default change ≠ model change.**
cut5 mezcló ambos en un solo commit. La separación correcta es:

- **Model change**: integrable cuando el modelo está probado (cut5 modelo sí, en M1).
- **CLI default change**: requiere opt-in flag porque cambia el contrato implícito que
  los tests existentes miden.

Este WU corrige la lección entregando el cambio CLI detrás de un opt-in flag, preservando
el default legacy que los tests existentes asumen.

## Próximo paso

- Merge de `wu/rp-053-followup-workspace-mode` sobre `wu/rp-053-merge` (rebasing lineal
  factible — son 3 commits encima del branch integrado).
- Push a remoto bajo operator gate sobre exact bytes (rule 6).
- Promoción a `main` + candidate tag pendiente decisión de producto sobre SEMVER.

## Refs

- Plan: [`docs/v2/05-roadmap/WU-RP-053-FOLLOWUP/PLAN.md`](../05-roadmap/WU-RP-053-FOLLOWUP/PLAN.md)
- WU-RP-053-MERGE recibo: [`docs/v2/07-uat/WU_RP_053_MERGE_RECEIPT.md`](WU_RP_053_MERGE_RECEIPT.md)
- Fase 4-bis recibo: [`docs/v2/07-uat/WU_RP_053_FASE_4_BIS_RECEIPT.md`](WU_RP_053_FASE_4_BIS_RECEIPT.md)
- Operator directive 2026-09-21T12:23:58Z (workspace semantics Jenkins-parity)
- Cherry-pick reverted (lesson): `356cc5df`
- M1 follow-up reverted (CLI default opt-out): `e08b063e`
- Base: `wu/rp-053-merge @ c7d6ef01`
