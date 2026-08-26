# Jenkins Familiarity Contract

## Propósito

Definir qué significa “familiar a Jenkins” sin prometer compatibilidad binaria o de ejecución.

## Referencia normativa de firmas

Las firmas canónicas de los steps (nombres exactos de parámetros, tipos Groovy,
defaults, plugin propietario, recuento de instalaciones) están definidas en
[`JENKINS_FAMILIARITY_CATALOG.md`](JENKINS_FAMILIARITY_CATALOG.md), verificadas
contra la referencia oficial de steps de jenkins.io y los árboles de código
`jenkinsci/*` (fecha de verificación: 2026-08-26). **Ese catálogo es la única
fuente de verdad para añadir o modificar firmas v2.** Está prohibido inventar
parámetros o "mejorar" firmas de Jenkins: si una superficie v2 necesita algo
que Jenkins no tiene, se registra como desviación (ver abajo).

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
| `sh` | `sh` | F3 | IMPLEMENTED in M2-R3 (ver desviación DEV-001) |
| `error` | `error` | F3 | IMPLEMENTED in M2-R3 |
| `sleep` | `sleep` | F3 | IMPLEMENTED in M2-R3 |
| `retry` | `retry` | F3 | M3 BACKLOG |
| `timeout` | `timeout` | F3 | M3 BACKLOG |
| `git` | `git` | F2/F3 | M3 BACKLOG |
| `checkout` | `checkout` | F2 | ML/L5 (ROADMAP) |
| `withCredentials` | `withCredentials` | F2/F3 | M3 BACKLOG |
| `usernamePassword` | `usernamePassword` | F3 | M3 BACKLOG |
| `junit` | `junit` | F2 | M3 BACKLOG |
| `archiveArtifacts` | `archiveArtifacts` | F2 | ML/L6 (ROADMAP) |
| `stash/unstash` | propio | F1/F2 | M3 BACKLOG |
| `input` | `input` | F2, CONTROLLER_CONTROL | M3 BACKLOG |
| `container` | `container` | F2 | M3 BACKLOG |
| Kubernetes `inheritFrom` | igual concepto | F2 | M3 BACKLOG |
| Kubernetes `yaml/yamlFile` | igual concepto | F2 | M3 BACKLOG |

> **Nota:** Las filas marcadas como `IMPLEMENTED in M2-R3` tienen `@JenkinsSurface` annotation en `StepExecutors.kt` y metadata JSON en `META-INF/pipeline/step-metadata/`. Las filas marcadas como `M3 BACKLOG` son candidates para M3+ cuando la infraestructura de durable replay, manifest de plugins, y credential resolver estén disponibles. `checkout` y `archiveArtifacts` están ancladas al milestone ML (L5/L6) según `05-roadmap/ROADMAP.md`.

## Desviaciones documentadas

### DEV-001 — `sh(timeoutMs, env)` (2026-08-26, ciclo ML-R2)

**Firma Jenkins real** (workflow-durable-task-step, catálogo §3):
`sh(script, encoding, label, returnStatus, returnStdout)` — **exactamente
5 parámetros**; no existen `timeout` ni `env`.

**Firma v2 actual**:
`sh(command)` posicional (corpus-safe) y
`sh(script, returnStdout = false, timeoutMs: Long? = null, env: Map<String, String>? = null)`.

**Desviación**: v2 añade `timeoutMs` y `env` como parámetros de `sh`. En
Jenkins esas capacidades viven en wrappers/directivas: `timeout(time, unit,
activity) { }` (workflow-basic-steps), `withEnv(List<String>) { }`
(workflow-basic-steps, con semántica `PATH+X=/p` prepend), y la directiva
declarativa `environment { }`.

**Justificación**: el motor de deadlines (watchdog + kill por cookie, estado
terminal `FAILED_TIMEOUT`) y la inyección de env son necesarios para ML/L2 y
para la directiva declarativa `options { timeout() }` ya soportada; exponerlos
como named-args de `sh` es azúcar Kotlin idiomática que no rompe el modelo
mental ni el subconjunto F1 posicional.

**Mapeo de migración**: `timeout(time: n) { sh(...) }` → `sh(script, timeoutMs = n * 1000)`;
`withEnv(['K=v']) { sh(...) }` → `sh(script, env = mapOf("K" to "v"))`;
`environment { K = v }` → `StageSpec.environment` (fiel).

**Retirada prevista**: cuando los wrappers fieles `timeout { }` y `withEnv { }`
se implementen (backlog post-ML), `timeoutMs`/`env` en `sh` se marcan
deprecated en favor de los wrappers y de `StageSpec.environment`.

## Regla

Cuando imitar Jenkins perjudique type safety, durability, seguridad o reproducibilidad, se preserva el modelo mental y se documenta una desviación explícita.

Toda desviación se registra en la sección "Desviaciones documentadas" de este
documento con: firma Jenkins real (citando el catálogo), firma v2,
justificación, mapeo de migración y plan de retirada.
