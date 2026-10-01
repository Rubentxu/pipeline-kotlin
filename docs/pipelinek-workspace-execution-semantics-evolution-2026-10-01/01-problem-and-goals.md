# 01 — Diagnóstico, problema y objetivos

## 1. Síntoma de producto

PipelineK nació siguiendo la intuición de Jenkins: el motor dispone de un workspace y los jobs trabajan dentro de él. En el modo histórico, cuando el usuario no proporciona `--workspace`, PipelineK crea un árbol de trabajo bajo almacenamiento temporal/controlado.

Ese modelo funciona bien cuando el pipeline **obtiene el proyecto durante la ejecución** —por ejemplo mediante `checkout`— pero falla como experiencia local-first cuando el usuario ya está dentro del proyecto:

```bash
cd project
pipelinek run pipeline.kts
```

Un Step como:

```kotlin
sh("./gradlew build")
```

puede ejecutarse en un workspace temporal distinto del proyecto y fallar porque `./gradlew` no existe allí. El workaround actual:

```bash
pipelinek run --workspace . pipeline.kts
```

es correcto pero fácil de olvidar y no expresa el modelo conceptual completo.

## 2. Evidencia en el código actual

### 2.1 `WorkspaceResolver` mezcla origen y sharing

En `WorkspaceResolver.kt`:

```kotlin
if (workspaceBase != null) return workspaceBase
return controlDirRoot.resolve("workspace").resolve("${safeName}-${stageIndex}")
```

La nulabilidad de `workspaceBase` decide simultáneamente:

- si el workspace es aportado por el usuario o generado por PipelineK;
- si todos los stages comparten una raíz o reciben un directorio por stage.

Son dos decisiones independientes y no deben codificarse en un `Path?`.

### 2.2 `ShOptions` intenta representar dos autoridades

El runtime contiene:

```kotlin
data class ShOptions(
    val workspaceRoot: Path,
    ...,
    val workingDirectory: Path? = null,
)
```

Y varios puntos calculan:

```kotlin
workingDirectory ?: workspaceRoot
```

Esto revela la semántica real:

```text
workspaceRoot = frontera estable
workingDirectory = cwd efectivo
```

El `cwd` no debería ser nullable. Su valor inicial es el workspace root.

### 2.3 `WorkspaceIdentity.workspaceRoot` puede contener en realidad el cwd

`CanonicalRuntimeCapabilityAccess` calcula actualmente el root efectivo con:

```kotlin
val effectiveWorkspaceRoot =
    context.shOptions.workingDirectory ?: context.shOptions.workspaceRoot

WorkspaceIdentity(workspaceRoot = effectiveWorkspaceRoot)
```

Esto corrige algunos Steps bajo `dir`, pero semánticamente convierte una propiedad llamada `workspaceRoot` en "current directory". El nombre deja de ser una invariante fiable.

### 2.4 Los filesystem Steps pueden reconstruir otra autoridad

`WorkspaceOperationsAdapter` crea un nuevo `WorkspaceResolver(controlDirRoot, workspaceBase)` para `writeFile`, `readFile` y `fileExists`.

Ese patrón permite que un Step observe una ruta distinta a la que observa `sh`, aunque ambos estén dentro del mismo `dir(...)`.

### 2.5 La aplicación ya ha sufrido el problema en dogfooding

Dos commits recientes son evidencia operacional, no teórica:

- `c3026a72` — los smokes anidados de Gradle/Maven fallaban porque el `pipelinek run` interior no llevaba `--workspace` y ejecutaba en un scratch temporal.
- `46eea758` — se añadió un witness para comprobar que las rutas ejecutadas por el pipeline self-hosted existen realmente en el checkout; el incidente original fue precisamente una invocación sin `--workspace`.

Por tanto, el problema debe tratarse como **semántica de producto**, no como documentación de usuario.

## 3. Drift terminológico

Los siguientes nombres aparecen o han aparecido con significados cercanos pero no idénticos:

- `workspace`;
- `workspaceBase`;
- `workspaceRoot`;
- `workingDirectory`;
- `workingDir`;
- `currentDirectory`;
- `controlDirRoot`;
- `rootProject` / conceptos de project root.

### Decisión

El vocabulario canónico será:

| Término | Significado único |
|---|---|
| `InvocationDirectory` | cwd del proceso de usuario en el momento de invocar PipelineK |
| `PipelineDefinitionPath` | ubicación del `.pipeline.kts`; no define el workspace |
| `WorkspaceRoot` | frontera estable autorizada del workspace |
| `WorkingDirectory` / `cwd` | directorio efectivo del contexto de ejecución actual |
| `WorkspaceLease` | propiedad/lifecycle/origen de una raíz de workspace |
| `WorkspaceAllocationPolicy` | cómo se comparte/asigna workspace entre run/stage/branch |
| `ControlRoot` | journal, locks, durable state y otros datos internos |
| `Artifact/Stash store` | almacenamiento durable específico; no cwd |

`rootProject` queda reservado al dominio concreto que lo posea (por ejemplo Gradle) y no se empleará como sinónimo genérico de workspace.

## 4. Objetivos

### G1 — local-first sin flags ceremoniales

```bash
cd repo
pipelinek run pipeline.kts
```

debe ejecutar por defecto sobre `repo`.

### G2 — conservar scratch como capacidad explícita

```bash
pipelinek run --isolated pipeline.kts
```

debe preservar el caso de uso Jenkins-like donde PipelineK posee el workspace.

### G3 — una única semántica para todos los Steps

Si `dir("a")` deriva `cwd=/workspace/a`, todos los Steps workspace-relative dentro del bloque deben observar ese cwd salvo que su contrato declare deliberadamente `WORKSPACE_ROOT`.

### G4 — separar seguridad de resolución

Resolver una ruta y autorizarla son decisiones diferentes:

```text
relative input
 -> choose anchor
 -> normalize/resolve
 -> authorize against workspace boundary
 -> perform effect
```

### G5 — preservar arquitectura funcional/hexagonal

Las decisiones de modo, anchor, sharing y seguridad deben expresarse como ADTs/funciones puras. Los adaptadores hacen I/O.

### G6 — migración incremental

No se reemplazan de una vez los consumidores de `WorkspaceResolver`. Cada familia obtiene caracterización RED, cambio mínimo, UAT y receipt.

## 5. No objetivos

Esta WU no debe:

- implementar runners remotos;
- inventar un nuevo sandbox OS/container;
- convertir PipelineK en detector mágico de repositorios;
- buscar automáticamente `.git`, `settings.gradle`, `pom.xml`, etc. para decidir el workspace;
- modificar el significado de `controlRoot`;
- rehacer journal/replay;
- crear una segunda API de shell paralela a `sh`;
- convertir `dir` en acceso arbitrario al filesystem del host;
- corregir de paso todos los documentos históricos del roadmap.

## 6. Causa raíz resumida

```text
Path? workspaceBase
   ├─ decide propiedad/origen
   ├─ decide sharing
   ├─ influye en cwd
   └─ se reconstruye en adapters

ShOptions
   ├─ workspaceRoot
   └─ workingDirectory?

WorkspaceIdentity.workspaceRoot
   └─ a veces = cwd
```

La solución es sustituir este estado implícito por un contexto tipado con invariantes.
