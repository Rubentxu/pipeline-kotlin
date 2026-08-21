# Jenkins Familiarity Contract

## Propósito

Definir qué significa “familiar a Jenkins” sin prometer compatibilidad binaria o de ejecución.

## Niveles

### F0 — Naming
Mismo nombre y concepto general.

### F1 — Surface
Nombre y parámetros principales equivalentes.

### F2 — Behavioral
Semántica observable compatible para los casos documentados.

### F3 — Migration
El migrador puede convertir automáticamente el uso Jenkins correspondiente.

Cada Step declara su nivel.

## Metadata propuesta

```kotlin
@JenkinsSurface(
    step = "sh",
    plugin = "workflow-durable-task-step",
    compatibility = CompatibilityLevel.BEHAVIORAL
)
@Step(...)
context(process: ProcessExecutor)
suspend fun sh(...): ShellResult
```

## Primer catálogo

| Jenkins surface | V2 | Objetivo |
|---|---|---|
| `echo` | `echo` | F3 |
| `sh` | `sh` | F3 |
| `error` | `error` | F3 |
| `retry` | `retry` | F3 |
| `timeout` | `timeout` | F3 |
| `git` | `git` | F2/F3 |
| `checkout` | `checkout` | F2 |
| `withCredentials` | `withCredentials` | F2/F3 |
| `usernamePassword` | `usernamePassword` | F3 |
| `junit` | `junit` | F2 |
| `archiveArtifacts` | `archiveArtifacts` | F2 |
| `stash/unstash` | propio | F1/F2 |
| `input` | `input` | F2, CONTROLLER_CONTROL |
| `container` | `container` | F2 |
| Kubernetes `inheritFrom` | igual concepto | F2 |
| Kubernetes `yaml/yamlFile` | igual concepto | F2 |

## Regla

Cuando imitar Jenkins perjudique type safety, durability, seguridad o reproducibilidad, se preserva el modelo mental y se documenta una desviación explícita.
