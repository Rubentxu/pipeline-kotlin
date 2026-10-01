# 04 — Step → PathAnchor matrix

## 1. Objetivo

Congelar explícitamente la base desde la que cada Step interpreta rutas relativas. `dir(...)` debe modificar únicamente los Steps anclados a `CURRENT_DIRECTORY`.

Estados:

- **CONFIRMED** — referencia Jenkins o contrato PipelineK suficiente.
- **TARGET** — decisión PipelineK propuesta.
- **DIFFERENTIAL_REQUIRED** — no cambiar producción hasta ejecutar caracterización específica.
- **INTERNAL_STORE** — no es un workspace anchor; usa port/control store.

## 2. Matriz

| Step/familia | Jenkins/reference | Estado actual PipelineK observado | Target | Acción |
|---|---|---|---|---|
| `sh` | current dir bajo `dir` | usa `workingDirectory ?: workspaceRoot` | `CURRENT_DIRECTORY` | migrar a `ExecutionLocation.cwd` |
| `pwd()` | current directory | capability puede exponer cwd bajo nombre workspaceRoot | `CURRENT_DIRECTORY` | corregir naming/authority |
| `pwd(tmp=true)` | temp asociado al current directory | contrato durable propio | `CURRENT_DIRECTORY` identity | conservar determinismo; test nested dir |
| `dir(path)` | relative path; cambia current dir | deriva `workingDirectory` | scope derivation | rechazar absolute/escape; root estable |
| `readFile` | current directory | adapter reconstruye WorkspaceResolver | `CURRENT_DIRECTORY` | migrar |
| `writeFile` | current directory | adapter reconstruye WorkspaceResolver | `CURRENT_DIRECTORY` | migrar |
| `fileExists` relative | current working directory | adapter reconstruye WorkspaceResolver | `CURRENT_DIRECTORY` | migrar; absolute externo reject por policy |
| `deleteDir()` | elimina current directory | substrate workspace-oriented | `CURRENT_DIRECTORY` | migrar + root protection |
| `stash` | current working directory es base | WorkspaceResolver + durable stash store | `CURRENT_DIRECTORY` source | migrar source; store sigue interno |
| `unstash` | restaura al workspace/contexto actual | WorkspaceResolver | `CURRENT_DIRECTORY` target | differential nested-dir + migrar |
| `checkout` | SCM checkout en FilePath/context actual | WorkspaceIdentity/SCM seam | `CURRENT_DIRECTORY` | caracterización + migración |
| `junit.results` | report paths normalmente relativos al execution workspace | WorkspaceIdentity | `CURRENT_DIRECTORY` por consistencia de scope | fixture dentro de `dir` obligatoria |
| `readJSON/readYaml/...` file inputs | utility steps operan sobre current working dir | WorkspaceIdentity en SDK | `CURRENT_DIRECTORY` | migración de capability |
| `writeJSON/writeYaml` | current working dir | WorkspaceIdentity | `CURRENT_DIRECTORY` | migración |
| `zip/tar` base | Jenkins utility puede usar current working dir | WorkspaceIdentity/workspace APIs | `CURRENT_DIRECTORY` salvo parámetro explícito | migración |
| `unzip/untar` target | current working dir | WorkspaceIdentity | `CURRENT_DIRECTORY` | migración |
| `sha256`/file utilities | path relativo del contexto | WorkspaceIdentity | `CURRENT_DIRECTORY` | migración |
| `archiveArtifacts` | docs: base directory is workspace | actualmente resolver stage workspace | **DIFFERENTIAL_REQUIRED** | Jenkins fixture con/sin `dir` antes de decidir |
| `publishHTML.reportDir` | docs: relative to workspace | resolver stage workspace | **DIFFERENTIAL_REQUIRED** | Jenkins fixture con/sin `dir` |
| `cleanWs` | delete workspace | workspace root | `WORKSPACE_ROOT` | conservar anchor + ownership safety |
| journal/locks/replay rows | n/a | controlRoot | `INTERNAL_STORE` | no exponer como PathAnchor |
| artifact retention store | n/a | controlRoot/artefacts | `INTERNAL_STORE` | source anchor separado de store |
| stash durable store | n/a | controlRoot/stashes | `INTERNAL_STORE` | source/target separados del store |

## 3. Regla de `dir`

Para cualquier Step `S`:

```text
anchor(S) == CURRENT_DIRECTORY
    => dir("a") cambia su resolución

anchor(S) == WORKSPACE_ROOT
    => dir("a") no cambia su base
```

No se permiten excepciones ocultas en adapters.

## 4. Differential fixtures obligatorios

### DF-ARCH-001

```groovy
node {
  writeFile file: 'root.txt', text: 'root'
  dir('sub') {
    writeFile file: 'inside.txt', text: 'inside'
    archiveArtifacts artifacts: '*.txt'
  }
}
```

Observar qué artefactos se archivan en Jenkins real y congelar la equivalencia/deviación.

### DF-HTML-001

Crear `root-report` y `sub/report`, ejecutar `publishHTML` dentro de `dir('sub')`, observar la ruta efectiva.

### DF-UNSTASH-001

`stash` desde `dir('producer')`; `unstash` dentro de `dir('consumer')`; verificar target real.

### DF-JUNIT-001

Generar XML en `dir('module')`, consumirlo con ruta relativa y verificar base efectiva.

## 5. Fitness de catálogo

Crear una fuente declarativa de clasificación, por ejemplo:

```kotlin
data class WorkspacePathSemantics(
    val anchor: PathAnchor,
    val access: WorkspacePathAccess,
)
```

No es necesario que todos los Steps la expongan públicamente en `StepDescriptor` en el primer corte. Sí debe existir una autoridad testable que impida que nuevos Steps workspace-aware omitan su clasificación.

Posible evolución posterior: incluir `PathSemantics` en metadata/capability contract si demuestra valor para plugins externos.
