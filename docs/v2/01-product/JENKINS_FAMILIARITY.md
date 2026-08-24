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

| Jenkins surface | V2 | Objetivo | Estado |
|---|---|---|---|
| `echo` | `echo` | F3 | IMPLEMENTED in M2-R3 |
| `sh` | `sh` | F3 | IMPLEMENTED in M2-R3 |
| `error` | `error` | F3 | IMPLEMENTED in M2-R3 |
| `sleep` | `sleep` | F3 | IMPLEMENTED in M2-R3 |
| `retry` | `retry` | F3 | M3 BACKLOG |
| `timeout` | `timeout` | F3 | M3 BACKLOG |
| `git` | `git` | F2/F3 | M3 BACKLOG |
| `checkout` | `checkout` | F2 | M3 BACKLOG |
| `withCredentials` | `withCredentials` | F2/F3 | M3 BACKLOG |
| `usernamePassword` | `usernamePassword` | F3 | M3 BACKLOG |
| `junit` | `junit` | F2 | M3 BACKLOG |
| `archiveArtifacts` | `archiveArtifacts` | F2 | M3 BACKLOG |
| `stash/unstash` | propio | F1/F2 | M3 BACKLOG |
| `input` | `input` | F2, CONTROLLER_CONTROL | M3 BACKLOG |
| `container` | `container` | F2 | M3 BACKLOG |
| Kubernetes `inheritFrom` | igual concepto | F2 | M3 BACKLOG |
| Kubernetes `yaml/yamlFile` | igual concepto | F2 | M3 BACKLOG |

> **Nota:** Las filas marcadas como `IMPLEMENTED in M2-R3` tienen `@JenkinsSurface` annotation en `StepExecutors.kt` y metadata JSON en `META-INF/pipeline/step-metadata/`. Las filas marcadas como `M3 BACKLOG` son candidates para M3+ cuando la infraestructura de durable replay, manifest de plugins, y credential resolver estén disponibles.

## Regla

Cuando imitar Jenkins perjudique type safety, durability, seguridad o reproducibilidad, se preserva el modelo mental y se documenta una desviación explícita.
