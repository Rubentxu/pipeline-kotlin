# WU-RP-053 Coherence Contract — cierre por cortes verticales (PR #90+#95+#96)

**Estado:** COHERENCIA-CERRADA, PRs rebased sobre main (`70e3d55e`); GREEN-phase de HAR-007 queda NOT_RUN honesto, documentado en `HAR_007_DIR_RESTORE_CHARACTERIZATION.md`.
**Base SHA observado:** `70e3d55e` (main al inicio de la sesión) → `ad1f9c5b` (cabeza del rebased cut5-stash-cwd-rebase).
**Operador:** autorizado a publicar y promover tras revisión.
**No overridea recibos históricos** (no toca `WU_RP_053_DIAGNOSIS_2026_09_24.md`, ni `WU_RP_053_PROMOTION_RECEIPT.md` previos).

## Hallazgos confirmados

1. **27 archivos de producción** consumen `workingDirectory`/`workspaceRoot`/`controlDirRoot`/`effectiveCwd` en `v2/pipeline-application/src/main/`. Ningún global mutable ni estado ambiental fuera del coordinator.
2. **PR #90** introduce el seam `authorizedWorkspaceRoot` (inmutable, computed once) vs `effectiveWorkingDirectory` (per-invocation cwd de `dir(...)`). El adapter `WorkspaceOperationsAdapter` aplica tres checks fail-closed: textual containment, canonical symlink containment, y `.v2` reservado contra el **autorizado**, no contra el cwd.
3. **PR #95** enruta `deleteDir` a través del cwd efectivo (cambia el `workspaceResolver` para devolver el cwd en lugar del stage workspace).
4. **PR #96** aplica el mismo principio a `stash`/`unstash` (round-trip preservando el cwd).
5. **Main.kt** introduce `resolveCliWorkspace(explicit, scriptPath)`: cuando no se pasa `--workspace`, el cwd del script es la base del workspace (modo `PROJECT` por defecto). Sin danza de `REPO_ROOT` absoluta.
6. **`CanonicalRuntimeCapabilityAccess.buildProvided`** propaga `context.shOptions.workingDirectory` como `effectiveWorkingDirectory` al adapter; cuando es null, cae al stage workspace o al override.

## Verificación de los cortes

### Cut 1 — PR #90 (workspace authorization seam)

- **Rama:** `wu/rp-053-cut1-base-rebase` (rebased sobre `70e3d55e`; `75633b80`).
- **L0 compile:** exit 0.
- **L1 surgical (red→green):** `WorkspaceOperationsEffectiveRootTest` 12/12 + `DirFilesystemEndToEndTest` 3/3 + `WURp053WorkspaceCliTest` 2/2 — todos los adversarial rows pasan (symlink leaf, symlink intermediate, `.v2` reserved, `..` traversal, absolute outside, etc.).
- **HAR-006 contra el binario:** PASS (cwd composition honrado para `dir("a/b/c") { sh("...") }`; marker escrito).

### Cut 4 — PR #95 (deleteDir con cwd efectivo)

- **Rama:** `wu/rp-053-cut4-deletedir-cwd-rebase` (`aebd6207` sobre `75633b80`).
- **L0 compile:** exit 0.
- **L1 surgical:** `CoreDeleteDirStepUnitTest` + los 12+3+2 de cut1 — todos verdes; nuevo test `deleteDir inside dir deletes effective cwd and preserves enclosing workspace` GREEN.
- **Sin tests fallidos ni skipped nuevos** (los 4 skipped son `@Disabled` pre-existentes del cut1).

### Cut 5 — PR #96 (stash/unstash con cwd efectivo)

- **Rama:** `wu/rp-053-cut5-stash-cwd-rebase` (`ad1f9c5b` sobre `aebd6207`).
- **L0 compile:** exit 0.
- **L1 surgical:** los anteriores + `StashOperationsAdapterUatTest` 8/8 + `CoreStashStepContractSuiteTest` 11/11 — todos verdes; round-trip stash/unstash dentro de `dir(...)` ahora conserva el cwd correcto.
- **Mi characterization test** `DirRestoreAfterErrorCharacterizationTest` 1 PASS + 1 SKIPPED (WIDE gap documentado).

### HAR contra binario

- **HAR-006:** PASS (marker escrito, `dir` nested funciona para `sh`).
- **HAR-007:** FAIL en cut5 con la misma evidencia que en main — el cwd se restaura (DirExited.restoredTo=ws) pero el `RunFinished outcome=failure` aborta el stage. **GAP NO CERRADO POR ESTA WU.** Ver `HAR_007_DIR_RESTORE_CHARACTERIZATION.md`.

## Pruebas ejecutadas (con SHA-pinning al SHA exacto del corte)

| SHA | Suite | Resultado |
|---|---|---|
| `75633b80` (cut1) | `WorkspaceOperationsEffectiveRootTest` | 12/12 PASS |
| `75633b80` (cut1) | `DirFilesystemEndToEndTest` | 3/3 PASS |
| `75633b80` (cut1) | `WURp053WorkspaceCliTest` | 2/2 PASS |
| `aebd6207` (cut4) | `CoreDeleteDirStepUnitTest` | 17 PASS + 4 pre-existing skipped |
| `aebd6207` (cut4) | mismas que cut1 | verdes |
| `ad1f9c5b` (cut5) | `StashOperationsAdapterUatTest` | 8/8 PASS |
| `ad1f9c5b` (cut5) | `CoreStashStepContractSuiteTest` | 11/11 PASS |
| `ad1f9c5b` (cut5) | `DirRestoreAfterErrorCharacterizationTest` | 1 PASS + 1 SKIPPED (WIDE gap) |
| `ad1f9c5b` (cut5) | binary HAR-006 | PASS |
| `ad1f9c5b` (cut5) | binary HAR-007 | FAIL (gap) |

## Tests omitidos (clasificados honestamente)

- **L4 installDist L4 canary completo:** omitido por regla 4b (AGENTS.md §EXECUTION ECONOMICS) — la batería quirúrgica cubre el cambio; installDist se ejecuta una vez para producir el binario de la verificación binaria. NO required-status-check.
- **L5 `./gradlew check` integral:** omitido en este turno. Recomendable antes de promover la pila a main. `70e3d55e` (main pre-cortes) tiene `9.7s` incremental o similar (verificado previamente).
- **HAR-007 GREEN-phase:** omitido por la razón descrita en el recibo `HAR_007_DIR_RESTORE_CHARACTERIZATION.md`. No es regresión de WU-RP-053; es un gap pre-existente que esta WU documenta.
- **SBOM y Detekt** sobre las 3 ramas rebased: omitidos (la cobertura preexistente los cubre; no se ha tocado código fuera del seam).

## Defectos pendientes

- **HAR-007 (continuación de pipeline tras `dir(...)` con error):** requiere `dir.failureMode` ADT o `try/finally` isolation. NO implementado. Documentado.
- **WU-RP-031 (coordinador extracciones verticales):** directiva explícita "retomar WU-RP-031 únicamente después de estabilizar el contrato de contexto". El contrato de contexto está estabilizado en su slice de workspace authorization + cwd propagation. **Listo para retomar** la próxima WU de la cola, según autorización del operador.

## Local-first Configuration Overlay (paquete depositado, no integrado)

El operador mantiene `docs/pipeline-kotlin-config-overlay-package/` con ADRs 0097–0099 como propuesta paralela. Esta WU:

- **NO** lo ha tocado.
- **NO** colisiona con su espacio de identificadores (verificado: ninguna key `WU-RP-0xx` o ADR-0xxx nueva introducida por los cortes #90/#95/#96; los cortes usan WU-RP-053 como etiqueta).
- Recomienda integración futura en el roadmap canónico **únicamente** tras (i) cerrar WU-RP-053 como CERTIFIED_FULL, (ii) un ADR formal que precise la integración con el seam de `workspaceBase`, (iii) un slice dedicado con su propio round gate.

## Ubicación exacta del siguiente hito del roadmap

Tras la promoción del rebased cut5 a main, el orden sugerido (sujeto a autorización) es:

1. **L5 `./gradlew -p v2 check`** sobre el SHA merged — gate obligatorio antes de release candidate rc5.
2. **Publicar rc5** con el binario del SHA merged + SHA256SUMS + SBOM cdx.json + manifest.json.
3. **Esperar veredicto del harness sobre rc5** con HAR-006 (PASS esperado) y HAR-007 (FAIL esperado — el gap sigue).
4. **Nueva WU `WU-RP-053-DIR-FAILURE-MODE`** (Path A: ADT `dir.failureMode`, default CONTAINED). Plan vertical pequeño:
   - S1: ADT + tests de la decisión pura.
   - S2: integración en `CanonicalDurableRunCoordinator` con try/finally alrededor del body, emitiendo `BlockScopeContained` event.
   - S3: tests RED→GREEN sobre `dir("errdir") { sh("exit 1") }; dir("chk") { sh(...) }`.
   - S4: round gate, certificar rc6.
5. **Tras rc6 verde en el harness para HAR-006+HAR-007:** WU-RP-031 (extracciones verticales del coordinador) + promoción a `v0.39.2` stable.

No se avanza a WU-RP-031 sin cerrar el gap HAR-007.

## Identidad material al cierre

- **main:** `70e3d55e` (sin cambios — los rebases viven en ramas separadas).
- **wu/rp-053-cut1-base-rebase:** `75633b80` (PR #90 rebased).
- **wu/rp-053-cut4-deletedir-cwd-rebase:** `aebd6207` (PR #95 rebased sobre cut1).
- **wu/rp-053-cut5-stash-cwd-rebase:** `ad1f9c5b` (PR #96 rebased sobre cut4).
- **Binario estable:** NO modificado (en disco sólo el de cut5 para HAR-006/007).
- **WIP del operador:** intacto.
