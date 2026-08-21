# Visión V2

## Tesis

`pipeline-kotlin` no debe ser “Jenkins Pipeline reescrito en Kotlin”. Debe convertirse en un **runtime distribuido, durable, event-driven y graph-native para CI/CD**, conservando deliberadamente el modelo mental y la ergonomía que hicieron familiar a Jenkins Pipeline.

## Cambio de frontera

```text
Jenkins / SaaS / CLI
        │ commands + projections
        ▼
Control Plane
        │ versioned protocol
        ▼
Worker Runtime
        ├─ compila .pipeline.kts
        ├─ ejecuta Kotlin
        ├─ ejecuta steps
        ├─ mantiene journal local
        └─ publica eventos durables
```

## North Star del developer

```kotlin
pipeline {
    agent {
        kubernetes {
            inheritFrom("java21")
            defaultContainer("builder")
        }
    }

    environment { "CI" set "true" }

    stages {
        stage("Build") {
            steps { sh("./gradlew build") }
        }
        stage("Test") {
            steps { junit(testResults = "**/build/test-results/**/*.xml") }
        }
    }

    post {
        always { archiveArtifacts(artifacts = "**/build/reports/**") }
    }
}
```

Un usuario Jenkins debe reconocer inmediatamente `pipeline`, `agent`, `stages`, `stage`, `steps`, `post`, `sh`, `junit` y `archiveArtifacts`. La implementación interna no necesita CPS, `VirtualChannel`, `Launcher`, `FilePath` ni Remoting.

## Diferenciadores

### Familiaridad sin compatibilidad accidental
Se copian nombres, conceptos y firmas de alto valor. La compatibilidad real se declara explícitamente por Step.

### Kotlin moderno
Kotlin 2.4 aporta context parameters estables y K2 como base única. Las capabilities se pueden expresar sin Service Locator global.

### Durable execution sin CPS
El runtime registra operaciones durables y sus resultados. Al reanudar, reejecuta el programa pero devuelve resultados ya confirmados en lugar de repetir side effects.

### Workers efímeros como caso normal
Kubernetes Pods son una fuente natural de workers. La desaparición de un Pod es un evento esperado y recuperable.

### Event log + grafos
El log append-only es la verdad. Plan Graph, Execution Graph, Provenance Graph, Jenkins FlowGraph y observabilidad son proyecciones.

### Supply chain nativa
Commit, pipeline digest, compiler/runtime, plugin digests, worker image, artifacts, SBOM, firmas y deployment forman una cadena de procedencia navegable.

### Fork / diff / experimentación
Un run puede bifurcarse en un punto seguro para comparar runtime, worker image, plugin version, cache policy o configuración sin alterar el original.

## Definición de éxito

V2 será un éxito cuando:

- pipelines Jenkins comunes migren con mínima fricción;
- el controller Jenkins no compile ni evalúe el pipeline del usuario;
- la pérdida de un worker no destruya el run;
- todos los estados relevantes sean explicables mediante eventos;
- cualquier artifact desplegado sea trazable hasta commit, pipeline, steps, worker y evidencias;
- los plugins evolucionen independientemente del core;
- el motor pueda ejecutarse también sin Jenkins.
