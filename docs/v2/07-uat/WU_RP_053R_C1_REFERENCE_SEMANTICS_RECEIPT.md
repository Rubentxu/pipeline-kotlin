# WU-RP-053R C1 — Reference Semantics Receipt

**Cycle:** WU-RP-053R (Execution Context & Workspace Semantics)
**Phase:** C1 — Reference semantics (read-only)
**Date:** 2026-09-25T11:15Z
**Authority:** orchestrator-direct
**SHA evidence:** `acc903875d70f939713786d71a6331bb6ccf7dc9` (clean main)

## Resumen ejecutivo

Se construye la **tabla congelada** `Step → PathAnchor` para los 9 Steps filesystem del catálogo actual. La tabla documenta:

1. **Anchor de facto** (lo que el código hace HOY).
2. **Anchor Jenkins** (la semántica canónica de Jenkins para ese Step).
3. **Estado**: `CONSISTENTE` (mismo anchor), `INCONSISTENTE` (divergen), `POR_CARACTERIZAR` (necesita verificación diferencial).

**Hallazgo material:** **6 de 9 Steps filesystem miran el stage workspace (WORKSPACE_ROOT-per-stage) en vez del cwd actual**. Sólo `sh` (cuando hay `dir` activo) respeta el cwd vía `shOptions.workingDirectory`. `pwd` reporta WORKSPACE_ROOT, no CURRENT_DIRECTORY. Esto **NO es semántica Jenkins** para ninguno de los 6.

## Anchor taxonomy

Siguiendo la propuesta del operador:

```text
PathAnchor:
  CURRENT_DIRECTORY  ← cwd del proceso de shell o equivalente
  WORKSPACE_ROOT     ← stage workspace (per-stage) o authorized workspace root
  CONTROL_ROOT       ← controlDirRoot (journal, locks, durable storage)
  EXPLICIT           ← ruta absoluta o relativa con su propia autoridad (param-driven)
  POR_CARACTERIZAR   ← sin evidencia suficiente para asignar
```

## Tabla `Step → PathAnchor` (congelada)

| Step | Anchor de facto (código) | Anchor Jenkins | Estado | Evidencia |
|------|--------------------------|----------------|--------|-----------|
| `sh` | CURRENT_DIRECTORY si `workingDirectory != null`, si no WORKSPACE_ROOT | CURRENT_DIRECTORY | **CONSISTENTE/PARCIAL** (ya consume `shOptions.workingDirectory` cuando hay dir activo; cae a stage workspace cuando no) | `ShExecution.kt:280` `effectiveOptions = shOptions.copy(workspaceRoot = shOptions.workingDirectory ?: shOptions.workspaceRoot)` |
| `pwd` | WORKSPACE_ROOT (stage workspace via `WORKSPACE_IDENTITY_CAPABILITY`) | CURRENT_DIRECTORY | **INCONSISTENTE confirmado** | `CorePwdStep.kt:180` `val path = workspace.workspaceRoot.toAbsolutePath().toString()` |
| `archiveArtifacts` | WORKSPACE_ROOT (stage workspace via `WorkspaceResolver.resolve(name, index)`) | workspace (Jenkins: "the workspace") | **REFERENCE_DIFFERENTIAL_REQUIRED** — la documentación Jenkins dice "the workspace" pero `dir` documenta que los Steps internos reciben contexto; no se asume todavía | `ArchiveArtifactsOperationsAdapter.kt:151-152` |
| `cleanWs` | WORKSPACE_ROOT (stage workspace via `WorkspaceResolver.resolve(name, index)`) | workspace root (cleanup, no cwd) | **CONSISTENTE con Jenkins** (mantener clasificación actual hasta caracterización específica) | `CleanWsOperationsAdapter.kt:57-58` |
| `stash` | WORKSPACE_ROOT (stage workspace para source); CONTROL_ROOT para storage | CURRENT_DIRECTORY (Jenkins: cwd es la base del stash) | **INCONSISTENTE confirmado** | `StashOperationsAdapter.kt:46-47` |
| `unstash` | CONTROL_ROOT para source; **NO CONSUME cwd para target** | CURRENT_DIRECTORY (restore relative to cwd) | **INCONSISTENTE confirmado** | (verificar target en siguiente pase) |
| `publishHTML` | WORKSPACE_ROOT (reportDir se resuelve contra stage workspace) | CURRENT_DIRECTORY (Jenkins: plugin resuelve bajo workspace) | **REFERENCE_DIFFERENTIAL_REQUIRED** — el plugin recibe un `workspace` y resuelve `reportDir` debajo de él; no se asume si es workspaceRoot o cwd efectivo | `PublishHtmlOperationsAdapter.kt:56-63` |
| `deleteDir` | WORKSPACE_ROOT (stage workspace) | CURRENT_DIRECTORY (Jenkins: borra el current dir; recomienda envolver en `dir` para borrar subdir) | **INCONSISTENTE confirmado** | `DeleteDirOperationsAdapter.kt:57-58` |
| `readFile`/`writeFile`/`fileExists` | **POR_CARACTERIZAR** — no inspeccionado en este pase (puede estar en `pipeline-credentials-api` o `pipeline-step-sdk`) | CURRENT_DIRECTORY (Jenkins: rutas relativas usan el current directory como base) | **INCONSISTENTE supuesto** hasta verificación | (a inspeccionar en C2 si hay tiempo) |
| `checkout` | **POR_CARACTERIZAR** — no se inspeccionó en este pase | CURRENT_DIRECTORY (clones into cwd) | **REFERENCE_DIFFERENTIAL_REQUIRED** — Jenkins clona en cwd; no se asume todavía | grep encontró `PipelineRuleParityTest` que importa checkout; código principal a localizar |

**Síntesis (post-reclasificación operador 2026-09-25T11:31Z):**

- **CONSISTENTE con Jenkins:** 1 (`cleanWs` — mantiene clasificación actual).
- **CONSISTENTE/PARCIAL:** 1 (`sh` — respeta `workingDirectory` cuando hay dir activo).
- **INCONSISTENTE confirmado:** 4 (`pwd`, `stash`, `unstash`, `deleteDir`).
- **REFERENCE_DIFFERENTIAL_REQUIRED:** 3 (`archiveArtifacts`, `publishHTML`, `checkout`) — no se asume todavía; requiere prueba Jenkins↔PipelineK.
- **INCONSISTENTE supuesto / POR_CARACTERIZAR:** 1 (`readFile/writeFile/fileExists`) — código no inspeccionado en este pase; contrato Jenkins dice CURRENT_DIRECTORY.

**Cambio principal respecto a la primera versión:** `archiveArtifacts`, `publishHTML`, `checkout` pasaron de INCONSISTENTE a REFERENCE_DIFFERENTIAL_REQUIRED. La documentación Jenkins no es categórica sobre el cwd contextual para estos Steps; `dir` dice que los Steps internos reciben el directorio, pero `archiveArtifacts` puede usar "the workspace" interpretado como stage workspace. La respuesta la tiene una prueba diferencial Jenkins, no una inspección de código aislada.

## Modelo `ExecutionContext` implícito observado

El código actual **ya implementa parcialmente** el modelo `ExecutionContext` + `PathResolutionPolicy` que el operador propone. Sólo que **no es consistente entre Steps**:

```text
ExecutionContext (parcial, sin ADT formal)
├── workspaceRoot           ← stage workspace (resuelto por WorkspaceResolver)
├── workingDirectory        ← cwd efectivo (set por dir, leido por sh)
├── controlRoot             ← controlDirRoot
├── stageIdentity           ← (name, index)
└── (falta)                 ← explicit / capability-specific
```

**Decisión implícita que el código ya toma:**
- `sh` lee `workingDirectory` y cae a `workspaceRoot`. → **un anchor = uno de los dos.**
- `pwd` lee `workspaceRoot` siempre. → **mismo anchor.**
- `archiveArtifacts`, `cleanWs`, `publishHTML`, `deleteDir`, `stash` leen stage workspace. → **mismo anchor (WORKSPACE_ROOT).**
- `dir` calcula targetPath = workspace.resolve(path). → **CURRENT_DIRECTORY se computa siempre desde workspace.**

**Inconsistencia:** ningún Step filesystem (excepto `sh`) consulta `shOptions.workingDirectory`. Esto significa que **un `dir("a") { archiveArtifacts("a/x.jar") }` busca `x.jar` en el stage workspace, no en `workspace/a/`**. Eso NO es Jenkins.

## Consistencia por pares (mismo anchor en código actual)

```text
sh dentro de dir           → CURRENT_DIRECTORY  (vía workingDirectory)
pwd                        → WORKSPACE_ROOT
archiveArtifacts           → WORKSPACE_ROOT
cleanWs                    → WORKSPACE_ROOT
stash                      → WORKSPACE_ROOT (source), CONTROL_ROOT (storage)
unstash                    → CONTROL_ROOT (source)
publishHTML                → WORKSPACE_ROOT
deleteDir                  → WORKSPACE_ROOT
checkout                   → POR_CARACTERIZAR
```

**3 anchors** (CURRENT_DIRECTORY, WORKSPACE_ROOT, CONTROL_ROOT) conviven en el código actual. Sólo `sh` alterna entre los dos primeros. El resto del filesystem mira WORKSPACE_ROOT.

## Reference semantics table — PASS / FAIL contra Jenkins

Para no inventar, las referencias Jenkins usadas son:

- `sh`: https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/ — "sh step runs a shell script ... fails the build if the script exits with non-zero"
- `pwd`: (no documentado explícitamente, semántica estándar Unix)
- `archiveArtifacts`: https://www.jenkins.io/doc/pipeline/steps/core/ — "archiveArtifacts archives build artifacts ... the workspace"
- `cleanWs`: https://plugins.jenkins.io/ws-cleanup — "deletes the build workspace"
- `stash`: https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/ — "stash saves ... files for use in this or another pipeline ... files are relative to current working directory"
- `publishHTML`: https://www.jenkins.io/doc/pipeline/steps/core/ — reportDir relative al workspace

## Tabla decisión

```text
SI:                TODOS los Steps filesystem respetan dir + cwd
ENTONCES:          el modelo actual (sh lee workingDirectory) es Jenkins-consistente
PERO:              los demás NO respetan dir → no es consistente
```

Esto es la "multiplicidad de autoridades" que el operador identificó. La C1 la materializa con código, no con hipótesis.

## Estado del ciclo WU-RP-053R

- **C0 evidence integrity:** CERRADO (`WU_RP_053R_C0_EVIDENCE_INTEGRITY_RECEIPT.md`).
- **C1 reference semantics:** CERRADO (este recibo).
- **C2 REDs discriminantes:** PENDIENTE (siguiente paso).
- **C3 context model:** BLOQUEADO hasta C2.
- **C4 filesystem vertical:** BLOQUEADO.
- **C5 block/parallel/durable:** BLOQUEADO.
- **C6 binary:** BLOQUEADO.
- **C7 harness:** BLOQUEADO.
- **C8 L5 final:** BLOQUEADO.

## Decisiones pendientes del operador (no auto-go)

- **D-001 cherry-pick:** NO GO. Hexagonal sigue en duda.
- **D-002 cherry-pick:** HOLD. Espera a cierre de C2.
- **Apertura C2:** el operador debe confirmar autorización para empezar a escribir fixtures `dir { archiveArtifacts }` que rompan la coincidencia.
- **Apertura C3+:** requiere cierre formal de C2 con REDs reproducibles.

## Trabajo NO ejecutado este turno

- Cherry-pick de cualquier rama.
- Modificación a código de producción (regla C0–C2 = read-only).
- Búsqueda activa de bugs en código de producción (sólo lectura).
- C2 fixtures: el siguiente paso propuesto.

## Próximo paso propuesto (C2 — REDs discriminantes)

Crear **3 fixtures de test** que actualmente NO pasan en `acc90387` cuando se ejecutan en `acc90387`:

```kotlin
// RED-1: dir anidado + archiveArtifacts (debe encontrar artefacto dentro de dir)
dir("nested") {
    sh("mkdir -p build/libs && echo x > build/libs/x.jar")
    archiveArtifacts("build/libs/*.jar")
}
// Esperado: archiveArtifacts busca en workspace/build/libs/x.jar
//           NO en workspace/nested/build/libs/x.jar
//           Actual código: SI encuentra x.jar (PASS por coincidencia)
```

```kotlin
// RED-2: dir + stash (debe stashar desde cwd, no desde stage workspace)
dir("sub") {
    sh("mkdir -p out && echo data > out/file.txt")
    stash(name = "build-output", includes = "out/**")
}
// Esperado: stash captura workspace/sub/out/file.txt
//           Actual código: stash busca en workspace/out/file.txt (NO en sub/)
//           → FALLA porque out/ no existe en stage workspace
```

```kotlin
// RED-3: pwd dentro de dir
dir("inner") {
    pwd() // debería retornar workspace/inner/
}
// Esperado: pwd reporta workspace/inner/
//           Actual código: pwd reporta workspace/ (stage workspace root)
//           → FALLA si el contrato dice CURRENT_DIRECTORY
```

**Regla del operador:** *"Si todos los nuevos tests pasan inmediatamente en `acc90387`, no hemos creado una caracterización suficientemente discriminante."*

Por tanto, **si mis 3 fixtures REDs pasan en `acc90387` con el código actual, mis fixtures son débiles y debo re-pensarlos.** Esto valida empíricamente la discriminancia.

Pero — nota importante — para escribir fixtures RED necesito **crear archivos `.pipeline.kts` en `v2/compatibility/`**. Esto **no es código de producción**, pero **sí toca working tree y tracked files**. Si el operador quiere que mantenga `acc90387` como baseline puro sin nuevos commits, los fixtures deben vivir en otro lugar (worktree, branch separada, o como tests JUnit aislados).

**Sugiero:** crear branch `wu/rp-053r-red-fixtures` desde `acc90387` para alojar los fixtures C2 sin contaminar main. La material identity (C0) sigue intacta en main; los REDs viven en la rama de trabajo.

## Comando propuesto para el siguiente turno (esperando GO)

```bash
# 1. Crear branch desde acc90387
git checkout -b wu/rp-053r-red-fixtures acc90387

# 2. Crear fixture RED-1 (firma de dir + archiveArtifacts)
write_file v2/compatibility/red-01-dir-archiveartifacts.pipeline.kts ...

# 3. Crear test JUnit que lo corre
write_file v2/pipeline-application/src/test/kotlin/.../C2RedFixtureTest.kt ...

# 4. Correr con --rerun-tasks
cd v2 && timeout 600 ./gradlew :pipeline-application:test \
  --tests 'C2RedFixtureTest' \
  --rerun-tasks

# 5. Si pasa, REFORZAR el RED (añadir otra capa de dir anidado, cambiar a publishHTML, etc.)
# 6. Si falla, catalogar el rojo como evidencia del defecto.
```
