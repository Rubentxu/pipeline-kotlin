# Especificación de producto — Agent-First Secretless Execution

## 1. Problema

Los agentes de codificación trabajan principalmente mediante shell/CLI. Cuando una operación necesita credenciales, los patrones habituales fuerzan al agente o al proceso que éste controla a recibir el secreto:

```bash
TOKEN=$(...)
export TOKEN
curl -H "Authorization: Bearer $TOKEN" ...
```

Esto aumenta el riesgo de fuga por:

- prompts y tool traces;
- argv;
- environment;
- stdout/stderr;
- historial de shell;
- logs/eventos;
- ficheros persistentes de configuración;
- procesos hijos;
- plugins no confiables.

PipelineK ya controla ejecución, redacción, durable state, Steps y capabilities. La oportunidad es convertirlo en una fachada de ejecución agent-first donde el agente expresa **qué operación quiere realizar** y **qué identidad/capacidad necesita**, sin conocer cómo se materializa.

## 2. Objetivos funcionales

1. Ejecutar comandos con profiles semánticos:

```bash
pipelinek command --profile github-release -- gh release create ...
```

2. Ejecutar cualquier Step registrado sin crear una `.pipeline.kts`:

```bash
pipelinek step scm-git.checkout --input ...
```

3. Usar las mismas capacidades desde `pipeline.kts`:

```kotlin
withCredentials(profile("nexus-release")) {
    sh("./gradlew publish")
}
```

4. Mantener la forma Jenkins-compatible:

```kotlin
withCredentials(
    usernamePassword("nexus", "NEXUS_USER", "NEXUS_PASS")
) {
    sh("./gradlew publish")
}
```

sin cambio semántico.

5. Permitir que un Step declare necesidades credenciales semánticas sin conocer providers concretos.

6. Seleccionar automáticamente la estrategia más segura disponible:

```text
signer/proxy > protocol helper > short-lived lease > config overlay > isolated exec > raw env/file
```

sujeto a compatibilidad y policy.

7. Generar config overlays efímeros para herramientas que lo requieran.

8. Proporcionar salida orientada a agentes con reducción de ruido y filtros seguros.

9. Mantener evidencias, eventos, replay y policy en el mismo motor.

## 3. Objetivos no funcionales

- backward compatibility verificable;
- fail-closed ante posture insuficiente;
- mínima vida de credential leases;
- cero secretos en eventos estructurados;
- limpieza fiable de overlays;
- plugins sensibles fuera del proceso por defecto;
- capacidad de introspección de Steps/profiles para agentes;
- ninguna dependencia obligatoria de ASV: ASV es backend fuerte opcional;
- Linux-first inicial, con comportamiento explícito en otras plataformas.

## 4. Personas/consumidores

### Agente

Quiere realizar una operación sin manejar secretos ni escribir configuración compleja.

### Autor de pipeline

Quiere el mismo modelo en `pipeline.kts`, con semántica durable y scopes claros.

### Operador

Quiere definir profiles, providers, policy, audiences y minimum posture.

### Autor de plugin

Quiere declarar que su Step necesita `git.fetch`, `registry.publish`, etc. sin implementar un vault.

## 5. Invariantes de producto

### P-01

Un profile nunca equivale a un secreto. Es una referencia a policy + capacidad.

### P-02

El agente no debe necesitar saber si la credencial está en LocalSecretStore, Secret Service, ASV, Vault o un cloud store.

### P-03

El Step no debe necesitar conocer el provider.

### P-04

La herramienta puede necesitar una projection específica; ésta es responsabilidad de un adapter.

### P-05

La posture real debe ser visible y auditable.

### P-06

Una operación que requiere `STRONG_SECRETLESS` falla si sólo existe `RAW_PROCESS_EXPOSURE`.

### P-07

El modo inline y `pipeline.kts` son fachadas del mismo runtime, no productos separados.

## 6. UX objetivo

### Operación simple

```bash
pipelinek command --profile nexus-publish -- ./gradlew publish
```

### Step tipado

```bash
pipelinek step scm-git.checkout \
  --set url=https://github.com/Rubentxu/pipeline-kotlin \
  --set branch=main \
  --profile github-read
```

### Pipeline

```kotlin
pipeline {
    stage("Release") {
        withProfile("release") {
            withCredentials(
                profile("nexus-publish"),
                profile("github-release"),
            ) {
                sh("./gradlew publish")
                step("github.release.create", githubReleaseInput(...))
            }
        }
    }
}
```

### Agente

La skill debe preferir:

```text
pipelinek command --profile <semantic-profile> -- <tool> <args>
pipelinek step <step-id> ...
```

sobre leer/exportar secretos.

