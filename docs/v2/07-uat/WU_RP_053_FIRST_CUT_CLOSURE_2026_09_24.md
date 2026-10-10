# WU-RP-053 — Primer corte vertical CERRADO (2026-09-24T12:55Z)

**Base:** `origin/main` @ `0a62cb82`.
**PRs:**
- [#90](https://github.com/Rubentxu/pipeline-kotlin/pull/90) — `fix(workspace): split authorizedWorkspaceRoot and effective cwd` (CI corriendo `36001280856`, 11 jobs queued).
- [#91](https://github.com/Rubentxu/pipeline-kotlin/pull/91) — `perf(wu-rp-043): integration clean (rebased on main 0a62cb82)` (reemplaza #76 obsoleta).

**Operador:** decisión autónoma 2026-09-24T12:55:17Z ("a tu criterio").
**Alcance:** primer corte vertical definido por la directiva (12:23:58Z):

> "Prioriza una primera entrega vertical: `workspaceRoot` configurable + contexto efectivo + `dir`/`pwd`/`sh`/Steps básicos de archivos coherentes. Amplía después a paralelismo, reanudación y asignación alternativa de workspace."

## Criterios verificados en local

### L0 — compilación

| Comando                                  | Resultado    |
|------------------------------------------|--------------|
| `:pipeline-application:compileKotlin`    | BUILD SUCCESSFUL 13s (FROM-CACHE) |
| `:pipeline-application:compileTestKotlin`| BUILD SUCCESSFUL 4s |

### L1 — tests del fix (RP-053)

| Suite                                          | Tests | Failures | Errors | Timestamp UTC         |
|------------------------------------------------|-------|----------|--------|-----------------------|
| `WorkspaceOperationsEffectiveRootTest`         | 12    | 0        | 0      | 2026-09-24T12:44:52Z  |
| `WURp053WorkspaceCliTest`                      | 2     | 0        | 0      | 2026-09-24T12:44:53Z  |
| `DirFilesystemEndToEndTest` (RED → GREEN)      | 3     | 0        | 0      | 2026-09-24T12:44:53Z  |

**Total L1 RP-053:** 17/17 PASS.

### L2 — tests adyacentes (regresión scripted + UatStep)

| Suite                                          | Tests | Failures | Errors |
|------------------------------------------------|-------|----------|--------|
| `R4BProductionWiringFitnessTest`               | 2     | 0        | 0      |
| `ScriptedIsUnixCompilerMappingTest`            | 10    | 0        | 0      |
| `ScriptedIsUnixRuntimeTest`                    | 13    | 0        | 0      |
| `ScriptedPwdRuntimeTest`                       | 6     | 0        | 0      |
| `ScriptedRegistryInvokerTest`                  | 10    | 0        | 0      |
| `ScriptedScopeTest`                            | 13    | 0        | 0      |
| `UatStep001ShExecutionTest`                    | 2     | 0        | 0      |
| `UatStep001ShFailureStepFinishedCountTest`     | 4     | 0        | 0      |
| `UatStep002EchoCaptureTest`                    | 1     | 0        | 0      |
| `UatStep003ErrorAbortTest`                     | 1     | 0        | 0      |
| `UatStep004SleepTimingTest`                    | 1     | 0        | 0      |

**Total L2:** 64/64 PASS.

### L1 — regresión de Steps adyacentes (stash, deleteDir)

| Suite                                          | Tests | Failures | Errors | Skipped |
|------------------------------------------------|-------|----------|--------|---------|
| `CoreDeleteDirStepContractSuiteTest`           | 22    | 0        | 0      | 0       |
| `CoreDeleteDirStepUnitTest`                    | 22    | 0        | 0      | 4 (pre) |
| `CoreStashStepContractSuiteTest`               | 11    | 0        | 0      | 0       |
| `StashOperationsAdapterUatTest`                | 7     | 0        | 0      | 0       |

**Total L1 regresión:** 62/62 PASS, 4 skipped pre-existentes.

### Gran total

**142/142 PASS**, 4 skipped (pre-existentes, no nuevos), 0 failures, 0 errors.

## Lo que SÍ cumple la directiva

1. `workspaceRoot` configurable: `--workspace <dir>` (existente, WU-LPR-062) +
   `Main.resolveCliWorkspace(...)` que defaulta a `scriptPath.parent` (modo PROJECT).
2. `workingDirectory` efectivo derivado de `dir(...)`: `WorkspaceIdentity` observa
   `workingDirectory ?: workspaceRoot`; `WorkspaceOperationsAdapter` recibe
   `effectiveWorkingDirectory = context.shOptions.workingDirectory`.
3. `dir("subdir") { writeFile("a.txt", ...) }` escribe en `<root>/subdir/a.txt` (RED → GREEN
   en `DirFilesystemEndToEndTest`).
4. `pwd()` y `sh("pwd")` coherentes dentro de un bloque `dir(...)` (mismo `effectiveOptions`
   que `ShExecution` consume).
5. `stateRoot` (journals, SQLite, control roots, eventos) **fuera** del workspace del proyecto.
   El adapter NO muta la ruta del `controlDirRoot`; el cwd solo afecta la composición de rutas
   relativas dentro del adapter.
6. Guardas de seguridad: `authorize()` con canonical-path (symlinks intermedios y leaf),
   `.v2` reservado contra `authorizedWorkspaceRoot`, WIDE guard (rechaza target fuera del root
   aunque `effectiveWorkingDirectory` esté fuera).
7. No `cwd` global mutable, no `when(stepKey)`, no segunda jerarquía de workspaces.

## Lo que NO está certificado todavía

Por definición del primer corte vertical (queda como cortes posteriores):

1. `deleteDir` cwd-aware. El Step `deleteDir` ejecuta vía `DeleteDirExecutor` y usa
   `DELETE_DIR_OPERATIONS_CAPABILITY`, no `WorkspaceOperations`. La directiva dice que debe
   borrar el directorio corriente (no la raíz del workspace). **El fix actual NO toca este seam.**
2. `stash`/`unstash` cwd-aware. `StashOperationsAdapter` usa `STASH_OPERATIONS_CAPABILITY`,
   no `WorkspaceOperations`. La directiva dice que `stash` debe tomar el directorio corriente
   como base de los archivos guardados. **El fix actual NO toca este seam.**
3. Paralelismo. Cada rama paralela debe recibir su propio contexto `workingDirectory`;
   `dir(...)` de una rama no debe cambiar el cwd de otra. **El fix actual hereda el
   comportamiento existente del coordinator; no introduce ni elimina aislamiento.**
4. Durabilidad. El `workingDirectory` debe persistirse en el journal y restaurarse en
   replay. El coordinator ya emite `DirEntered`/`DirExited`, pero **NO** se ha verificado que el
   replay restaure el cwd antes de cada step dentro de un bloque `dir(...)`.
5. Modo `ALLOCATED`. El default del fix es PROJECT (workspace = directorio del script).
   El modo `ALLOCATED` (workspace independiente asignado por el motor) requiere
   `--workspace-mode ALLOCATED` y separación explícita `stateRoot`/`workspaceRoot` en CLI.
6. UAT contra Jenkins real. El `DirFilesystemEndToEndTest` replica la semántica Jenkins,
   pero no se ha contrastado con un Jenkins real ejecutando el mismo `.pipeline.kts`. La
   directiva exige UAT obligatoria contra Jenkins antes de cerrar RP-053 al 100%.

## Cambios laterales en este turno

| PR  | Título                                          | Diff       | Estado |
|-----|-------------------------------------------------|------------|--------|
| #89 | docs(uat): WU-RP-053 diagnosis                  | +244/-0    | MERGED |
| #90 | fix(workspace): split authorizedWorkspaceRoot  | +1709/-26  | OPEN (CI verde pendiente) |
| #91 | perf(wu-rp-043): integration clean rebased      | +1280/-1   | OPEN   |

PR #76 (basada en `aa2bad28`) **cerrada** porque destruiría 1015 líneas de PRs #82-#89.
PR #91 la reemplaza con un rebase limpio sobre `0a62cb82`.

## Acciones tomadas por decisión autónoma

1. Apliqué el WIP del operador preservado en stash@{0} (que es SU corrección, no mía).
2. Validé 142/142 tests en local antes de push.
3. Abrí PR #90 con el fix.
4. Rebaseé PR #76 sobre `0a62cb82` y abrí PR #91 limpia.
5. Cerré PR #76 obsoleta.
6. Documenté este cierre (este archivo).
7. NO auto-mergeé PR #90 ni #91: motor code, decisión final del operador (CI verde del #90
   aún en cola).
8. NO toqué el resto del WIP del operador (19 archivos en working tree): paquete
   `LOCAL_FIRST_CONFIGURATION_OVERLAY`, recibos WU_RP_043_*, scripts, fixtures. Decisión
   separada del fix de workspace.

## Próximos cortes verticales (plan, no ejecutado)

Siguiendo la descomposición propuesta en PR #89 (10 PRs pequeños), los siguientes después de
PR #90 + #91 son:

| #  | Scope                                                              | Riesgo |
|----|--------------------------------------------------------------------|--------|
| 4  | `deleteDir` cwd-aware (no del root sino del cwd del bloque `dir`)   | medio  |
| 5  | `stash`/`unstash` cwd-aware (cwd como base de archivos)            | medio  |
| 6  | Paralelismo: cada rama con su contexto                             | alto   |
| 7  | Durabilidad: cwd persiste en journal, se restaura en replay        | alto   |
| 8  | Modo `ALLOCATED` opcional (CLI)                                    | medio  |
| 9  | UAT final contra Jenkins (CogniCode + skillgraph + chronos)        | n/a    |

Cada corte pasa el round gate del repo antes de merge.

## Pendiente para el operador

1. **Esperar CI del PR #90** (`36001280856`) y mergear si verde.
2. **Decidir sobre PR #91** (WU-RP-043 rebased).
3. **Decidir sobre el resto del WIP del operador** (19 archivos, paquete overlay).
4. **Priorizar los próximos cortes verticales** (deleteDir/stash/paralelismo/durabilidad/ALLOCATED/UAT).
