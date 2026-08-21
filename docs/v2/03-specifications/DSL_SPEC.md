# DSL Specification V2

## 1. Propósito

Definir una DSL Kotlin que preserve el modelo mental de Jenkins Pipeline, sea analizable, tipada, durable y desacoplada del runtime Jenkins.

## 2. Extensión y entry point

Extensión recomendada: `.pipeline.kts`.

```kotlin
pipeline {
    agent { ... }
    environment { ... }
    options { ... }
    stages { ... }
    post { ... }
}
```

## 3. Capas del DSL

### Declarative skeleton
Debe poder descubrirse/validarse sin ejecutar side effects:
- agent/profile;
- stages y nombres;
- options;
- environment declarations;
- post blocks;
- static when/requirements cuando sea posible.

### Scripted durable block
`script {}` habilita Kotlin arbitrario dentro de las reglas del runtime durable.

```kotlin
script {
    val tag = sh(script = "git describe --tags", returnStdout = true).stdout.trim()
    if (tag.startsWith("v")) {
        deploy(tag)
    }
}
```

## 4. Elementos mínimos

### `pipeline`
Una sola raíz por definition.

### `agent`
- `none()`
- `local()`
- `label(...)` como compatibility sugar
- `workerPool(name)`
- `kubernetes { ... }`

### `environment`
No debe materializar secretos en el modelo.

```kotlin
environment {
    "CI" set "true"
    "REGISTRY" set parameter("registry")
}
```

### `stages/stage`
Stage names deben tener stable logical IDs derivados de source location + explicit key opcional.

```kotlin
stage("Build", id = "build") { ... }
```

### `steps`
Sólo Step façades registradas o `script {}`.

### `post`
- always
- success
- failure
- cancelled
- unstable (si se soporta outcome equivalente)

## 5. Parallel

Superficie familiar:

```kotlin
parallel(
    "linux" to { sh("./test-linux.sh") },
    "windows" to { sh("test.bat") }
)
```

Internamente crea Frames tipados y join explícito. El orden de completion no altera la identidad lógica de cada rama.

## 6. Retry

```kotlin
retry(3) {
    sh("curl ...")
}
```

Debe generar Attempt IDs distintos. Retry policy puede filtrar `FailureKind`.

```kotlin
retry(
    maxAttempts = 3,
    on = setOf(FailureKind.INFRASTRUCTURE, FailureKind.NETWORK)
) { ... }
```

## 7. Timeout

Timeout es durable control flow; su deadline debe persistirse como timestamp/clock domain y no depender sólo de una coroutine timer en memoria.

## 8. `when`

Se ofrecen dos formas:

```kotlin
whenCondition {
    branch("main")
    parameter("deploy", equals = "true")
}
```

para análisis estático y:

```kotlin
script {
    if (...) { ... }
}
```

para lógica dinámica.

## 9. API typing

Evitar `Any` en superficie pública. Ejemplo:

```kotlin
data class ShellResult(
    val exitCode: Int,
    val stdout: String?,
    val stderr: String?,
    val duration: Duration
)
```

## 10. Source mapping

Cada Stage/Step descriptor runtime conserva:
- source file digest;
- line/column range;
- symbol/step name;
- plugin identity;
- logical ID.

## 11. Imports

El ScriptDefinition proporciona imports mínimos de API pública. Los plugins añaden façades mediante un classpath/metadata cerrado. No `wholeClasspath` del worker.

## 12. Compatibilidad

La semántica externa de la DSL se versiona independientemente de Kotlin/compiler:

```text
DSL API 2.x
Scripting Adapter kotlin24
Runtime 2.x
```

Un update de Kotlin no debe obligar a cambiar `.pipeline.kts` si no cambia la DSL API.
