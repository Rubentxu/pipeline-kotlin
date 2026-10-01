# 10 — Investigación y referencias

## 1. Jenkins

### `ws` vs `dir`

Jenkins separa allocation de workspace y current directory:

- `ws`: asigna workspace, normalmente elegido/bloqueado por Jenkins;
- `dir`: cambia el current directory y los paths relativos de los Steps internos.

Referencia:

- https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/

Conclusión adoptada: **workspace allocation != current directory**.

### Basic Steps

Documentación relevante:

- `pwd`: current directory;
- `readFile`: path relativo con root en current directory;
- `writeFile`: named file in current directory;
- `deleteDir`: recursively delete current directory;
- `stash`: current working directory is base;
- `fileExists`: relative path is relative to current working directory.

Referencia:

- https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/

Conclusión adoptada: la mayoría de filesystem Steps deben usar `CURRENT_DIRECTORY`.

### `archiveArtifacts`

La documentación expresa que la base directory es "the workspace".

Referencia:

- https://www.jenkins.io/doc/pipeline/steps/core/

Conclusión: **no asumir** cómo interactúa con `dir`; ejecutar fixture diferencial.

### `publishHTML`

`reportDir` se documenta relativo al workspace.

Referencia:

- https://www.jenkins.io/doc/pipeline/steps/htmlpublisher/

Conclusión: differential required antes de congelar anchor.

## 2. GitHub Actions

GitHub Actions separa el checkout/workspace del `working-directory` de un `run`, que puede configurarse por step/job/workflow.

Referencia:

- https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax

Conclusión adoptada: un runner local debe distinguir project workspace de cwd efectivo de una operación.

## 3. CircleCI

CircleCI define `working_directory` del job (default `~/project`), el `checkout` aterriza allí por defecto, y la configuración de ejecución trabaja desde ese directorio.

Referencia:

- https://circleci.com/docs/reference/configuration-reference/

Conclusión: source checkout y working directory son una decisión explícita del job, no una consecuencia de dónde esté almacenado el control plane.

## 4. Dagger

Dagger diferencia objetos `Directory`/workspace del `workdir` del contenedor (`withWorkdir`). Las APIs recientes de Workspace distinguen paths relativos al workspace cwd y paths absolutos respecto al workspace root.

Referencias:

- https://docs.dagger.io/reference/api/directory/
- https://docs.dagger.io/reference/api/container/
- https://docs.dagger.io/reference/sdks/typescript/

Conclusión adoptada: **boundary/root + cwd** es una abstracción reusable y no exclusiva de Jenkins.

## 5. Qué NO copiamos

- No copiamos agentes/nodes de Jenkins como estructura obligatoria local.
- No copiamos paths absolutos arbitrarios de Jenkins cuando debilitan confinement.
- No copiamos YAML de GitHub/CircleCI.
- No introducimos container snapshot semantics de Dagger.
- No detectamos automáticamente project root.

Se adopta la idea transversal, no la implementación de ningún producto.

## 6. Evidencia interna consultada

Snapshot `main @ 754ddda0bc3b7c35619a67f80f241691d78536d5`, incluyendo:

- `WorkspaceResolver.kt`;
- `ShOptions.kt`;
- `WorkspaceIdentity.kt`;
- `CanonicalRuntimeCapabilityAccess.kt`;
- `WorkspaceOperations.kt`;
- `CompositionRoot.kt`;
- `CliParser.kt`;
- `CanonicalStructuralDecisions.kt`;
- `WU_RP_053R_C1_REFERENCE_SEMANTICS_RECEIPT.md`;
- `WU_RP_053R_C2_REDS_DISCRIMINANTES_RECEIPT.md`;
- `WU_RP_053R_C3_1_DELETE_DIR_FIX_RECEIPT.md`;
- commits `c3026a72` y `46eea758`.

Las referencias internas son evidencia del snapshot; si el código cambia antes de apply, RP034-A debe regenerar el inventario.
