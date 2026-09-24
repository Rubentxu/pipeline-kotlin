# WU-RP-053 — Diagnóstico: asignación del workspace inicial vs propagación del cwd efectivo

**Base:** `origin/main` @ `73bb2256` (HEAD del checkout).
**Autor del diagnóstico:** sesión AUTO del agente de pipeline-kotlin.
**Autoridad:** AGENTS.md §"Step Constitution", §"REPLAY POLICY", §"EXPLICIT IMMUTABLE
EXECUTION CONTEXT" (CTX-P), §"RETRIES — DURABLE CONTROL ROWS".
**No toca código de motor.** Documenta el hallazgo y propone el plan.

## Resumen ejecutivo

El operador ha identificado **dos correcciones complementarias** que la implementación actual de
PipelineK no satisface simultáneamente:

1. **Asignación del workspace inicial.** Cuando un pipeline se ejecuta sobre un proyecto externo
   (CogniCode, skillgraph, chronos), el motor asigna un workspace bajo
   `<controlDirRoot>/workspace/<stageName>-<stageIndex>/` (vía `WorkspaceResolver.resolve()`)
   en lugar del directorio del proyecto. El resultado es que `pwd()`/`sh("pwd")` y los Steps de
   filesystem resuelven rutas relativas desde un directorio **interno de PipelineK**, no desde el
   repositorio consumidor.
2. **Propagación del cwd efectivo dentro de `dir(...)`.** Aun cuando el workspace se asigna
   correctamente, `dir("subdir") { writeFile("a.txt", "OK") }` resuelve la ruta como
   `<workspaceRoot>/a.txt` en lugar de `<workspaceRoot>/subdir/a.txt`. La consecuencia es que
   `readFile`/`writeFile`/`fileExists` no comparten el directorio efectivo que `sh` sí respeta.

Las dos correcciones son **independientes pero necesarias**, según el operador:

> "Para que PipelineK reproduzca el comportamiento de Jenkins, el directorio de trabajo inicial
> debe proceder del workspace asignado a la ejecución, no construirse implícitamente bajo
> `control/workspace/<stage>`. [...] Esto no significa que Jenkins siempre ejecute sobre el
> directorio del repositorio: Jenkins también puede asignar un workspace independiente y hacer
> allí el checkout. La paridad consiste en que todos los Steps utilicen el workspace efectivamente
> asignado, sea el checkout local o uno alternativo."

> "El hallazgo `control/workspace/t0-fmt-0` revela que la asignación del workspace inicial y la
> propagación del working directory deben tratarse como dos problemas distintos."

## Hallazgo 1 — Asignación del workspace

### Evidencia en código actual

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WorkspaceResolver.kt`
(líneas 47–54):

```kotlin
fun resolve(stageName: String, stageIndex: Int): Path {
    // WU-LPR-062: with an explicit project workspace (--workspace), stages SHARE
    // the given directory (Jenkins-familiar single-workspace semantics): a real
    // project build must run with CWD == the project root where gradlew lives.
    if (workspaceBase != null) return workspaceBase
    val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return controlDirRoot.resolve("workspace").resolve("${safeName}-${stageIndex}")
}
```

Comportamiento:

- **Si `workspaceBase != null`** (cuando se pasa `--workspace <dir>` al binario): todos los stages
  comparten ese directorio. Esto ya implementa el modo `PROJECT` definido por el operador.
- **Si `workspaceBase == null`** (default): cada stage obtiene
  `<controlDirRoot>/workspace/<stageName>-<stageIndex>/`, **un directorio interno de PipelineK**.

### Modos de asignación propuestos por el operador

| Modo        | Workspace inicial                          | Uso                                           |
|-------------|--------------------------------------------|-----------------------------------------------|
| `PROJECT`   | Directorio del proyecto consumidor         | CI local de CogniCode, skillgraph, chronos    |
| `ALLOCATED` | Workspace independiente asignado por motor | Ejecuciones aisladas, agentes, futuros workers |

La directiva exige que `stateRoot` (journals, SQLite, control roots, eventos, metadatos internos)
permanezca **fuera del repositorio**, en `~/.local/state/pipelinek/projects/<proyecto>-<id>/`.

### Estado del modo `PROJECT` en el código

- La opción `--workspace` ya está parcialmente cableada (vía `WU-LPR-062`) y la rama
  `wu/rp-053-integration-clean`/`adr/0094-impact-policy-and-overlay-id-gap` (HEAD `9ed0a4f2`) tiene
  el seam refinado (verificable con `WorkspaceOperationsEffectiveRootTest` 12/12, sha256
  `974a55bd…0ab990419f`).
- En `main` (`73bb2256`), la propagación de `--workspace` ya existe como opción CLI, pero el
  comportamiento por defecto sigue siendo `ALLOCATED`.

### Hallazgo derivado — PR #76 destruiría trabajo previo

`origin/wu/rp-043-integration-clean` (`d8145632`) está basada en `aa2bad28` (no en `main`
`73bb2256`). Al mergear borraría:

```
docs/v2/07-uat/HARNESS_PROMPTS_2026_09_24.md       | 303 --------------
docs/v2/07-uat/RECEIPTS/consult/v0.39.0-.../verdict.json | 9 -
scripts/consult-harness-verdict.py                 | 435 ---------------------
scripts/test_consult_harness_verdict.py            | 268 -------------
4 files changed, 1015 deletions(-)
```

Esos archivos vienen de PRs #82–#88 mergeadas a main después de `aa2bad28`. **PR #76 aún no se ha
mergeado** (operador explícito a las 11:46Z y 11:48Z), pero un merge sin rebase destruiría trabajo
posterior. Esto es **un riesgo material** que requiere decisión del operador.

## Hallazgo 2 — Propagación del cwd efectivo

### Evidencia en código actual

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt`
líneas 1149–1162:

```kotlin
is BlockShellScope.Directory -> {
    Files.createDirectories(scope.target)
    contextInBody = contextInBody.pushed(ContextOverlay.Cwd(scope.target.toString()))
    eventSink.append(DirEntered(...))
    stageShOptions.copy(workingDirectory = scope.target)
}
```

El coordinator **sí** propaga el cwd al context (`ContextOverlay.Cwd`) y a `stageShOptions.workingDirectory`,
pero el adapter que reciben los Steps de filesystem no lo observa:

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt`
líneas 108–115:

```kotlin
val workspaceOps: WorkspaceOperations = WorkspaceOperationsAdapter(
    stageName = context.stageName,
    stageIndex = context.stageIndex,
    controlDirRoot = context.controlDirRoot,
    eventSink = context.eventSink,
    runId = context.runId,
    workspaceBase = context.workspaceBase,
)
// ^^^ No se pasa el cwd efectivo del bloque dir(...)
```

**Resultado:** `WorkspaceOperationsAdapter.writeFile("a.txt", "OK")` siempre resuelve la ruta
como `<stageWorkspace>/a.txt`, sin componer el cwd del `dir(...)` actual.

### WIP del operador (preservado, no commiteado)

El operador tiene trabajo en `stash@{0}` (5 archivos modificados, 328 líneas) que **ya implementa
parte de la corrección**:

- `WorkspaceOperations.kt` (+223 líneas).
- `Main.kt` (+25 líneas).
- `CanonicalRuntimeCapabilityAccess.kt` (+24 líneas).
- 3 tests nuevos en `v2/pipeline-application/src/test/.../scripted/`:
  - `DirFilesystemEndToEndTest.kt` (~300 líneas, RED del defecto dir+filesystem).
  - `WorkspaceOperationsEffectiveRootTest.kt` (12/12 verde sobre `9ed0a4f2`).
  - `WURp053WorkspaceCliTest.kt` (2/2 verde sobre `9ed0a4f2`).
- Recibos en `docs/v2/07-uat/WU_RP_053_*` (no commiteados).

El test `DirFilesystemEndToEndTest.kt` añade un parámetro `effectiveWorkingDirectory` a
`WorkspaceOperationsAdapter` y a la subclase del capability bridge
(`WorkspacePlusEffectiveCwdAccess`). **Ese es exactamente el seam que la corrección requiere**.

**No es trabajo duplicable**: el operador ya tiene la implementación, los tests y los recibos.
Cualquier intervención mía sobre el mismo seam duplicaría código y generaría conflictos de merge.

## Decisión propuesta al operador

Sigo la directriz del operador (regla 4 — CALIDAD: "evalúa regresiones y código duplicado antes
de plantear cambios") y la regla 3 (CIERRE REAL: "completado ≠ criterios de aceptación
verificados").

### No escribo código de motor en este turno

Razones:

1. **Duplicación.** El WIP del operador en `stash@{0}` ya implementa el seam `effectiveWorkingDirectory`
   y el test RED. Reescribirlo desde cero en `main` produciría un conflicto de merge inmediato
   cuando el operador decida promover su WIP.
2. **Alcance.** La directiva describe una evolución arquitectónica (modelo `PROJECT` vs `ALLOCATED`,
   modo de asignación configurable, separación `stateRoot`/`workspaceRoot`/`workingDirectory`/
   `cacheRoot`). Eso requiere:
   - Diseño cuidadoso del ADT `WorkspaceAssignment` y de su propagación por el motor.
   - Decisión sobre la forma del CLI (¿`--workspace-mode PROJECT|ALLOCATED`? ¿detección
     automática por presencia de `.git`/`Cargo.toml`/`pom.xml`?).
   - Múltiples PRs pequeños (uno por concern): seam único → `dir` anidado → `sh` coherente →
     `writeFile`/`readFile`/`fileExists` → `stash/unstash` → `deleteDir` → paralelismo →
     durabilidad → modo `ALLOCATED` opcional.
   - UATs contra Jenkins real para cada Step (no factible desde esta sesión).
3. **Gates vigentes.** AGENTS.md §"V2 testing rules" exige L0→L1→L2→L3 con cobertura por impacto,
   y L4–L5 (round gate integral) en integración/release. Una intervención arquitectónica de este
   calibre debe pasar por el round gate en cada PR, no como un commit gigante.

### Lo que sí puedo hacer ahora (valor rápido, bajo riesgo)

1. **Caracterización reproducible**: tests que documenten el comportamiento actual sin prescribir
   el fix. Esto entra como `test(workspace): characterise current workspaceRoot and effective cwd`
   y se mergea a main antes de cualquier intervención de motor.
2. **Documentación de la separación `workspaceRoot`/`workingDirectory`/`stateRoot`/`cacheRoot`**
   como ADT en `docs/v2/03-specifications/EXECUTION_CONTEXT_SPEC.md` (creación o ampliación del
   spec existente). Es trabajo documental, no de motor.
3. **Bloqueo explícito de PR #76** mientras el operador decide cómo mergear sin destruir 5 PRs
   posteriores.

### Plan de PRs pequeños (propuesto, no ejecutado)

Si el operador confirma el plan, sugiero descomponer en:

| #  | Scope                                                                                                       | PR size estimate | Riesgo |
|----|-------------------------------------------------------------------------------------------------------------|------------------|--------|
| 1  | Test de caracterización del workspaceRoot actual (RED sin fix, sobre `73bb2256`).                          | ~150 líneas      | bajo   |
| 2  | ADT `ExecutionContext` con `workspaceRoot`, `workingDirectory`, `stateRoot`, `cacheRoot`. Spec + ADT.        | ~300 líneas      | medio  |
| 3  | Adapter de `ExecutionContext` en `CanonicalRuntimeCapabilityAccess.buildProvided`. Modo PROJECT por defecto. | ~200 líneas      | medio  |
| 4  | `dir(...)` propaga `ExecutionContext` con `workingDirectory` derivado. Tests anidados + pwd/sh coherente.    | ~250 líneas      | medio  |
| 5  | `writeFile`/`readFile`/`fileExists` observan `workingDirectory`. Regresiones.                                | ~200 líneas      | medio  |
| 6  | `stash`/`unstash`/`deleteDir` observan `workingDirectory`. Jenkins-parity tests.                             | ~250 líneas      | alto   |
| 7  | Paralelismo: cada rama con su `ExecutionContext`. Aislamiento.                                               | ~300 líneas      | alto   |
| 8  | Durabilidad: `workingDirectory` se persiste en el journal y se restaura en replay.                          | ~250 líneas      | alto   |
| 9  | Modo `ALLOCATED` opcional (CLI `--workspace-mode ALLOCATED`) y separación `stateRoot`.                      | ~300 líneas      | medio  |
| 10 | UAT final contra Jenkins con CogniCode + skillgraph + chronos. Round gate verde sobre el SHA final.         | n/a              | n/a    |

Cada PR pasa el round gate del repo antes de merge. PRs 1–2 pueden mergear como documental; 3–8 son
código de motor y requieren decisión del operador.

## WIP del operador preservado

`stash@{0}` contiene el trabajo que el operador mantiene para esta WU. **No se unstash desde esta
sesión**. El operador decide cuándo aplicarlo.

## Riesgos identificados

- **PR #76** destruiría 1015 líneas de trabajo posterior si se mergea sin rebase. Requiere
  decisión del operador antes de cualquier merge.
- **Round gate actual en main** (`73bb2256`) está en cola CI (run `35995597992`); resultados no
  verificados en este turno.
- **WIP del operador** contiene además la iniciativa `LOCAL_FIRST_CONFIGURATION_OVERLAY`
  (ADRs 097-099, paquete `docs/pipeline-kotlin-config-overlay-package/`). Si se promueve junto
  con WU-RP-053, el tamaño del cambio se duplica y requiere ADR formal.

## Acciones tomadas en este turno

- **NO** se modifica código de producción.
- **NO** se abre PR de código de motor.
- **NO** se unstash WIP del operador.
- **SÍ** se documenta este diagnóstico (`docs/v2/07-uat/WU_RP_053_DIAGNOSIS_2026_09_24.md`).
- **SÍ** se preserva la integridad de `origin/main` (`73bb2256`) y del `stash@{0}` del operador.

## Pendiente para el operador

1. Decidir si promover el WIP de `stash@{0}` directamente o descomponerlo en los PRs pequeños
   propuestos.
2. Decidir qué hacer con PR #76 (rebase sobre `73bb2256` antes de merge, o descarte).
3. Confirmar el ADT `ExecutionContext` y los modos `PROJECT`/`ALLOCATED` antes de cualquier
   cambio de motor.
4. Aprobar la línea base de UATs Jenkins-parity para CogniCode, skillgraph y chronos.
