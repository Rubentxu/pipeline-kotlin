# 02 — Especificación normativa de Workspace & Execution Location

Palabras **MUST**, **MUST NOT**, **SHOULD** y **MAY** son normativas.

## 1. Conceptos

### 1.1 InvocationDirectory

Directorio absoluto normalizado desde el que el usuario invoca `pipelinek`.

- MUST capturarse una sola vez en el boundary CLI.
- MUST NOT cambiar durante un run.
- MUST NOT inferirse desde la ubicación del `.pipeline.kts`.

### 1.2 PipelineDefinitionPath

Ruta del pipeline a compilar/ejecutar.

- Puede estar dentro o fuera del workspace.
- MUST NOT convertirse implícitamente en workspace root.

Ejemplo válido:

```bash
cd /repos/app
pipelinek run ~/.pipelinek/pipelines/ci.pipeline.kts
```

Resultado:

```text
InvocationDirectory  = /repos/app
PipelineDefinition   = ~/.pipelinek/pipelines/ci.pipeline.kts
WorkspaceRoot        = /repos/app
cwd                  = /repos/app
```

### 1.3 WorkspaceRoot

Frontera estable dentro de la que se resuelven y autorizan los paths workspace-scoped.

- MUST ser absoluta y normalizada.
- MUST permanecer estable durante la vida de una lease.
- `dir(...)` MUST NOT cambiarla.

### 1.4 WorkingDirectory (`cwd`)

Directorio efectivo de la operación actual.

- MUST existir como valor no-null.
- Al iniciar un scope: `cwd == workspace.root`.
- Un `dir` hijo deriva un `cwd` nuevo inmutable.
- Al salir del bloque reaparece el contexto padre; no existe un `chdir` global que deshacer.

### 1.5 ControlRoot

Almacenamiento interno de PipelineK para journal, locks, durable control state y estructuras asociadas.

- MUST NOT ser base para resolver argumentos de ruta del usuario.
- MUST NOT filtrarse como `cwd` por ausencia de workspace.

## 2. Workspace requests

La intención CLI se normaliza primero a una de estas formas cerradas:

```kotlin
sealed interface WorkspaceRequest {
    data object AttachInvocationDirectory : WorkspaceRequest
    data class AttachExplicit(val path: Path) : WorkspaceRequest
    data object ManagedIsolated : WorkspaceRequest
}
```

No se modela con `Path?`.

## 3. Modos CLI

### 3.1 Modo local por defecto

```bash
pipelinek run pipeline.kts
```

MUST producir:

```text
WorkspaceRequest.AttachInvocationDirectory
workspace.root = invocationDirectory
cwd            = workspace.root
ownership      = USER
lifecycle      = EXTERNAL
```

### 3.2 Workspace explícito

```bash
pipelinek run --workspace /repo pipeline.kts
```

MUST producir:

```text
WorkspaceRequest.AttachExplicit(/repo)
workspace.root = /repo
cwd            = /repo
ownership      = USER
lifecycle      = EXTERNAL
```

`--workspace .` sigue siendo válido y equivalente al default cuando el invocation directory es `.` resuelto absolutamente.

### 3.3 Modo aislado/managed

```bash
pipelinek run --isolated pipeline.kts
```

MUST producir una lease propiedad de PipelineK:

```text
ownership = PIPELINEK
lifecycle = RUN_MANAGED
```

El layout físico exacto es responsabilidad del allocator/adaptador.

### 3.4 Exclusión mutua

Esto MUST fallar en parse/admission:

```bash
pipelinek run --workspace /repo --isolated pipeline.kts
```

No debe existir una precedencia silenciosa.

## 4. Allocation/sharing es otra dimensión

Origen y sharing no son lo mismo.

```kotlin
sealed interface WorkspaceAllocationPolicy {
    data object RunShared : WorkspaceAllocationPolicy
    data object StageIsolated : WorkspaceAllocationPolicy
    data object BranchIsolated : WorkspaceAllocationPolicy // reservada/futura
}
```

Defaults iniciales:

| Request | Allocation inicial |
|---|---|
| AttachInvocationDirectory | `RunShared` |
| AttachExplicit | `RunShared` |
| ManagedIsolated | `StageIsolated` para preservar el comportamiento scratch existente durante la migración |

La política no se expone todavía como flag público. Separarla ahora evita que un futuro cambio de sharing vuelva a requerir reinterpretar `workspaceBase`.

## 5. Invariantes

### INV-WS-001 — root estable

`workspace.root` no cambia al entrar/salir de `dir`.

### INV-WS-002 — cwd total

`cwd` nunca es null.

### INV-WS-003 — cwd confinado

Para cualquier contexto workspace-scoped:

```text
canonical(cwd) ∈ canonical(workspace.root)
```

### INV-WS-004 — control plane separado

Ninguna ruta de usuario se resuelve respecto a `controlRoot`.

### INV-WS-005 — definition != workspace

La ubicación del script no cambia el workspace salvo opción explícita futura.

### INV-WS-006 — scopes inmutables

`dir`, `withEnv`, `timeout`, credenciales y otros overlays derivan valores hijos; no mutan el contexto padre/global.

### INV-WS-007 — ownership explícito

El runtime no deduce si puede borrar una raíz buscando `.git/.hg/.svn` como autoridad primaria.

### INV-WS-008 — resolver antes de I/O

Todos los workspace Steps resuelven y autorizan su target antes del primer efecto.

### INV-WS-009 — no escapes implícitos

Una ruta workspace-scoped no puede escapar del root mediante `..`, symlinks o rutas absolutas externas.

### INV-WS-010 — replay estable

Una operación reanudada conserva la identidad de workspace/working-directory requerida por su contrato durable o falla por divergencia; nunca se desplaza silenciosamente a `user.dir`.

### INV-WS-011 — concurrent context isolation

Dos branches/scopes concurrentes no comparten mutable cwd.

### INV-WS-012 — single authority

Un Step no puede reconstruir otra semántica de workspace a partir de `controlDirRoot + stageName + stageIndex` si el runtime ya le suministra `ExecutionLocation`.

## 6. Semántica de `dir`

`dir(path)` representa un cambio relativo de cwd dentro del workspace.

Normativo:

```text
dir("backend")       ACCEPT
dir("backend/api")   ACCEPT
dir(".")             ACCEPT (no-op semántico)
dir("../outside")    REJECT
dir("/tmp/foo")      REJECT
```


Algoritmo:

```text
base   = current.cwd
input  = relative path
candidate = normalize(base / input)
authorize(candidate, workspace.root)
child.cwd = candidate
child.workspace = parent.workspace
```

Una ruta absoluta no usa `dir` como escape hatch. Si en el futuro se necesita acceso host fuera del workspace, deberá existir otra capability/policy explícita.

## 7. Path anchors

Los Steps workspace-aware declaran su base conceptual:

```kotlin
sealed interface PathAnchor {
    data object CurrentDirectory : PathAnchor
    data object WorkspaceRoot : PathAnchor
}
```

`ControlRoot` no forma parte de este ADT público/semántico. Los datos de control usan puertos especializados.

### Anchor resolution

```text
CurrentDirectory -> executionLocation.cwd
WorkspaceRoot    -> executionLocation.workspace.root
```

Después se aplica normalización y autorización.

## 8. Absolute paths

Para APIs workspace-scoped:

- una ruta absoluta **dentro** de `workspace.root` MAY aceptarse si el contrato histórico del Step lo requiere;
- una ruta absoluta **fuera** de `workspace.root` MUST rechazarse por defecto;
- lectura arbitraria del host requiere una capability diferente y explícita.

Esto es una desviación de seguridad deliberada frente a APIs Jenkins que en algunos Steps permiten paths absolutos del nodo.

## 9. Operaciones destructivas

### 9.1 Workspace Attached/User-owned

En la raíz:

- `deleteDir()` MUST fail-closed;
- `cleanWs()` MUST fail-closed por defecto;
- no se elimina la raíz ni su contenido completo por accidente.

En un subdirectorio (`dir("build") { deleteDir() }`) la operación MAY ejecutarse si el path pasa confinamiento.

### 9.2 Workspace Managed/PipelineK-owned

PipelineK MAY limpiar/eliminar la raíz según lifecycle/policy.

### 9.3 Override futuro

Si se requiere borrar deliberadamente un workspace attached, el permiso debe ser una policy explícita con nombre destructivo; no un detector heurístico de VCS ni un booleano perdido en un executor.

## 10. Variables de entorno

Si PipelineK publica variables de compatibilidad:

```text
WORKSPACE       = workspace.root
PIPELINEK_CWD   = cwd       (si se decide exponer)
```

`WORKSPACE` MUST NOT cambiar al entrar en `dir`.

## 11. `pwd()`

`pwd()` MUST devolver `cwd`.

`pwd(tmp=true)` debe derivar su temporal de la identidad del **cwd actual**, manteniendo determinismo/replay según el contrato certificado existente; no debe volver a usar `workspace.root` por comodidad.

## 12. Errores tipados sugeridos

```kotlin
sealed interface WorkspacePathError {
    data class InvalidPath(val raw: String) : WorkspacePathError
    data class AbsolutePathNotAllowed(val raw: String) : WorkspacePathError
    data class EscapesWorkspace(val raw: String, val root: Path) : WorkspacePathError
    data class SymlinkEscape(val path: Path, val root: Path) : WorkspacePathError
    data class ProtectedWorkspaceRoot(val operation: String, val root: Path) : WorkspacePathError
    data class WorkspaceUnavailable(val reason: String) : WorkspacePathError
}
```

Los errores esperados no deben depender de exceptions como control normal de dominio.
