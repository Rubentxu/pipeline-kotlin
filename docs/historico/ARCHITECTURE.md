# Multi-DSL Architecture Documentation

## Overview

The Pipeline System has been refactored to support not only the Pipeline DSL but also third-party Kotlin DSL libraries. This document describes the extensible architecture that allows integration of multiple DSL types while maintaining security, performance, and consistency.

## Architecture Components

### 1. Core DSL Engine Interface (`DslEngine<TResult>`)

The foundation of the multi-DSL system is the generic `DslEngine` interface that defines standard operations for any DSL:

```kotlin
interface DslEngine<TResult : Any> {
    val engineId: String
    val engineName: String
    val supportedExtensions: Set<String>
    
    suspend fun compile(scriptFile: File, context: DslCompilationContext): DslCompilationResult<TResult>
    suspend fun execute(compiledScript: CompiledScript, context: DslExecutionContext): DslExecutionResult<TResult>
    suspend fun validate(scriptContent: String, context: DslCompilationContext): DslValidationResult
    // ... other methods
}
```

### 2. DSL Engine Registry (`DslEngineRegistry`)

Manages registration and discovery of DSL engines:

- **Engine Registration**: Register/unregister DSL engines by ID
- **File Extension Mapping**: Automatic engine selection based on file extensions
- **Capability Queries**: Find engines by supported capabilities
- **Thread-Safe Operations**: Concurrent access to engine registry

### 3. DSL Manager (`DslManager`)

Central orchestrator for all DSL operations:

```kotlin
class DslManager {
    suspend fun <TResult : Any> executeFile(scriptFile: File): DslExecutionResult<TResult>
    suspend fun <TResult : Any> executeContent(scriptContent: String, engineId: String): DslExecutionResult<TResult>
    suspend fun validateContent(scriptContent: String, engineId: String): DslValidationResult
    fun registerEngine(engine: DslEngine<*>)
    // ... other operations
}
```

### 4. Built-in Engine Implementations

#### Pipeline DSL Engine (`PipelineDslEngine`)
- Handles `.pipeline.kts` files
- Integrates with existing pipeline execution system
- Supports pipeline-specific features

#### Generic Kotlin DSL Engine (`GenericKotlinDslEngine`)
- Flexible engine for third-party DSL libraries
- Configurable script definitions and imports
- Customizable result extraction

### 5. Security and Isolation

#### Execution Contexts
- **DslCompilationContext**: Controls compilation security policies
- **DslExecutionContext**: Manages runtime security and resource limits
- **Security Policies**: Configurable security levels (DEFAULT, RESTRICTED, PERMISSIVE)

#### Isolation Levels
- `NONE`: No isolation
- `THREAD`: Thread-level isolation
- `CLASSLOADER`: ClassLoader isolation (implemented)
- `PROCESS`: Process isolation (planned)
- `CONTAINER`: Container isolation (planned)

### 6. Event System Integration

All DSL executions publish domain events:

```kotlin
sealed class DslExecutionEvent {
    data class Started(...)
    data class Completed(...)
    data class Failed(...)
    data class Warning(...)
}
```

### 7. Performance Features

- **Compilation Caching**: Shared cache across all engines
- **Execution Statistics**: Performance monitoring per engine
- **Resource Limits**: Memory, CPU, and time constraints

## Usage Examples

### 1. Basic Pipeline DSL (Existing)

```kotlin
// File: myapp.pipeline.kts
pipeline {
    agent {
        docker { image = "openjdk:21-jdk" }
    }
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")
            }
        }
    }
}
```

### 2. Third-Party DSL Integration

#### Gradle Kotlin DSL Integration
```kotlin
val dslManager = DslManager(config)

// Register Gradle DSL engine
val gradleEngine = dslManager.createThirdPartyEngine(
    engineId = "gradle-kotlin-dsl",
    engineName = "Gradle Kotlin DSL",
    supportedExtensions = setOf(".gradle.kts"),
    scriptDefinitionClass = GradleScript::class
) {
    defaultImports("org.gradle.api.*", "org.gradle.kotlin.dsl.*")
    capabilities(DslCapability.COMPILATION_CACHING, DslCapability.TYPE_CHECKING)
}

dslManager.registerEngine(gradleEngine)

// Execute Gradle script
val result = dslManager.executeContent<Any>(
    scriptContent = """
        plugins {
            kotlin("jvm") version "1.9.21"
        }
        dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib")
        }
    """,
    engineId = "gradle-kotlin-dsl"
)
```

#### Docker Compose DSL Integration
```kotlin
val composeEngine = dslManager.createThirdPartyEngine(
    engineId = "docker-compose-dsl",
    engineName = "Docker Compose DSL",
    supportedExtensions = setOf(".compose.kts"),
    scriptDefinitionClass = DockerComposeScript::class
) {
    defaultImports("com.example.docker.*")
    resultExtractor { result ->
        // Extract Docker Compose configuration
        mapOf("services" to result.returnValue.value)
    }
}

// Execute Docker Compose script
val composeResult = dslManager.executeContent<Any>(
    scriptContent = """
        services {
            service("web") {
                image = "nginx:latest"
                ports = listOf("80:80")
            }
        }
    """,
    engineId = "docker-compose-dsl"
)
```

### 3. Automatic Engine Detection

```kotlin
// Automatically selects engine based on file extension
val pipelineFile = File("build.pipeline.kts")  // Uses PipelineDslEngine
val gradleFile = File("build.gradle.kts")      // Uses GradleDslEngine  
val composeFile = File("docker.compose.kts")   // Uses ComposeDslEngine

val pipelineResult = dslManager.executeFile<PipelineResult>(pipelineFile)
val gradleResult = dslManager.executeFile<Any>(gradleFile)
val composeResult = dslManager.executeFile<Any>(composeFile)
```

### 4. DSL Validation

```kotlin
// Validate without execution
val validationResult = dslManager.validateContent(
    scriptContent = """
        pipeline {
            stages {
                stage("Test") {
                    steps {
                        sh("echo 'Hello World'")
                    }
                }
            }
        }
    """,
    engineId = "pipeline-dsl"
)

when (validationResult) {
    is DslValidationResult.Valid -> println("Script is valid")
    is DslValidationResult.Invalid -> {
        println("Validation errors:")
        validationResult.errors.forEach { error ->
            println("  - ${error.message} at line ${error.location?.line}")
        }
    }
}
```

## Extension Points

### 1. Custom DSL Engines

Create engines for any Kotlin DSL library:

```kotlin
class CustomDslEngine : DslEngine<CustomResult> {
    override val engineId = "custom-dsl"
    override val supportedExtensions = setOf(".custom.kts")
    
    override suspend fun compile(scriptFile: File, context: DslCompilationContext): DslCompilationResult<CustomResult> {
        // Custom compilation logic
    }
    
    override suspend fun execute(compiledScript: CompiledScript, context: DslExecutionContext): DslExecutionResult<CustomResult> {
        // Custom execution logic
    }
}
```

### 2. Plugin-Based Engines

Engines can be loaded dynamically through the plugin system:

```kotlin
class DslEnginePlugin : Plugin {
    override fun initialize(context: PluginInitializationContext) {
        val engine = createMyDslEngine()
        // Register engine through plugin context
    }
}
```

### 3. Custom Security Policies

Define custom security policies for specific DSL types:

```kotlin
val restrictedPolicy = DslSecurityPolicy(
    allowNetworkAccess = false,
    allowFileSystemAccess = false,
    allowReflection = false,
    sandboxEnabled = true
)

val executionContext = DslExecutionContext(
    securityPolicy = restrictedPolicy,
    resourceLimits = DslResourceLimits(
        maxMemoryMb = 128,
        maxCpuTimeMs = 30_000
    )
)
```

## Security Considerations

### 1. Sandboxing
- Each DSL execution runs in an isolated context
- Security policies control access to system resources
- ClassLoader isolation prevents interference between engines

### 2. Resource Limits
- Memory, CPU, and time constraints per execution
- Configurable limits per DSL type
- Automatic termination of runaway scripts

### 3. Validation
- Syntax validation before execution
- Type checking where supported
- Security policy validation

## Performance Features

### 1. Compilation Caching
- Shared cache across all DSL engines
- Configurable cache policies (memory/disk)
- TTL and LRU eviction strategies

### 2. Monitoring
- Execution statistics per engine
- Performance metrics collection
- Event-driven monitoring system

### 3. Concurrent Execution
- Thread-safe engine registry
- Concurrent DSL execution support
- Resource pooling for optimal performance

## Future Enhancements

### 1. Advanced Isolation
- GraalVM Isolate integration (in progress)
- Process-level isolation
- Container-based execution

### 2. IDE Integration
- Language server protocol support
- Code completion for DSL contexts
- Syntax highlighting and error reporting

### 3. Hot Reload
- Dynamic script reloading
- Incremental compilation
- Live DSL development

### 4. Distributed Execution
- Remote DSL execution
- Cluster-aware DSL orchestration
- Distributed caching

## Migration Guide

### From Single DSL to Multi-DSL

1. **Existing Pipeline Scripts**: No changes required - automatically uses `PipelineDslEngine`

2. **Custom Engines**: Implement `DslEngine<TResult>` interface

3. **Registration**: Use `DslManager.registerEngine()` instead of direct instantiation

4. **Execution**: Use `DslManager.executeFile()` or `executeContent()` methods

### Example Migration

Before:
```kotlin
val pipelineEngine = PipelineEngine(config)
val result = pipelineEngine.execute(scriptFile)
```

After:
```kotlin
val dslManager = DslManager(config)
val result = dslManager.executeFile<PipelineResult>(scriptFile)
```

## Conclusion

The multi-DSL architecture provides a flexible, secure, and performant foundation for executing various types of Kotlin DSL scripts. It maintains backward compatibility with existing pipeline scripts while enabling integration of third-party DSL libraries with consistent security and performance characteristics.