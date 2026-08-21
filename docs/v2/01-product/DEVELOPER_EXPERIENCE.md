# Developer Experience

## Objetivo

Que un desarrollador Jenkins perciba V2 como “Jenkins Pipeline con Kotlin y mejores garantías”, no como un framework CI/CD nuevo que obliga a reaprender conceptos.

## Principio de mínima sorpresa

Conservar:
- nombres (`pipeline`, `stage`, `steps`, `post`, `sh`, `git`, `junit`);
- estructura declarative;
- conceptos `agent`, `environment`, `credentialsId`, `retry`, `timeout`, `parallel`;
- comportamiento general observable.

Mejorar deliberadamente:
- parámetros Kotlin `name = value`;
- retornos tipados en lugar de cambios dinámicos de tipo;
- IDs y resultados tipados;
- diagnostics de compilación;
- autocomplete/LSP;
- error taxonomy;
- provenance y graph inspection.

## Ejemplo de resultado tipado

```kotlin
val result = sh(
    script = "git describe --tags",
    returnStdout = true
)

if (result.stdout.trim().startsWith("v")) {
    echo("release")
}
```

`ShellResult` puede exponer `stdout`, `stderr`, `exitCode`, `duration`, `attemptId`; el DSL mantiene familiaridad sin replicar `Any`.

## Declarative vs scripted

El esqueleto declarativo debe ser analizable antes de ejecutar:

```kotlin
pipeline {
    stages {
        stage("Build") { ... }
        stage("Release") { ... }
    }
}
```

La lógica dinámica se concentra en:

```kotlin
script {
    val tag = sh(...).stdout
    if (tag.startsWith("v")) deploy(tag)
}
```

No es una frontera de seguridad, sino de análisis, predictibilidad y tooling.

## Tooling mínimo

- autocomplete de DSL y plugins;
- quick documentation de Step;
- errores source-mapped;
- `pipeline validate`;
- `pipeline explain` para plan skeleton;
- `pipeline graph`;
- `pipeline replay --dry-run`;
- `pipeline migrate Jenkinsfile` en fases posteriores;
- snippets/recipes de equivalencias Jenkins → Kotlin.

## Error UX

Los errores deben responder:

1. qué falló;
2. dónde;
3. qué Step/plugin produjo el fallo;
4. si es user/build/infrastructure/policy/system;
5. si es retryable;
6. attempt/worker asociado;
7. event causal root;
8. siguiente acción sugerida, cuando sea determinista.

Ejemplo:

```text
BUILD_STEP_FAILED PK-SH-004
Pipeline: release #142
Stage: Test
Step: sh ./gradlew test
Attempt: 2
Worker: k8s/java21-7fc2
Exit code: 1
Source: .pipeline.kts:42:13
Cause event: evt_01...
Retryable: no (build failure)
```
