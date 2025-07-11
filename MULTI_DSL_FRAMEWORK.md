# Multi-DSL Scripting Framework

This document demonstrates the new generic scripting framework that supports multiple DSL types.

## Key Changes

### 1. New PipelineScriptRunner API

**Before:**
```kotlin
PipelineScriptRunner.evalWithScriptEngineManager(
    scriptPath, 
    configPath, 
    jarLocation, 
    logger
)
```

**After:**
```kotlin
PipelineScriptRunner.evalWithScriptEngineManager(
    scriptPath, 
    configPath, 
    dslType = "pipeline", // New parameter
    logger
)
```

### 2. Supported DSL Types

- **"pipeline"** - Existing pipeline DSL (backward compatible)
- **"task"** - New simple task DSL

### 3. Example Usage

#### Pipeline DSL (existing)
```kotlin
// script.pipeline.kts
pipeline {
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")
            }
        }
    }
}

// Usage
PipelineScriptRunner.evalWithScriptEngineManager(
    "script.pipeline.kts", 
    "config.yaml", 
    "pipeline"
)
```

#### Task DSL (new)
```kotlin
// task.kts
TaskDefinition(
    name = "build-task",
    description = "Build the project",
    command = "./gradlew build",
    environment = mapOf("BUILD_TYPE" to "release")
)

// Usage  
PipelineScriptRunner.evalWithScriptEngineManager(
    "task.kts", 
    "config.yaml", 
    "task"
)
```

### 4. Adding New DSL Types

To add a new DSL type:

1. Create a definition class (like `TaskDefinition`)
2. Implement `ScriptEvaluator<YourDefinition>`
3. Implement `PipelineExecutor<YourDefinition>`
4. Implement `AgentManager<YourDefinition>`
5. Implement `ConfigurationLoader<YourConfig>`
6. Register in `DefaultExecutorResolver`

### 5. Docker Simplification

The Docker build process is now simplified:

- No more executable path detection
- Always uses pre-installed runner: `/usr/local/bin/pipeline-runner.jar`
- Only copies script and config files

## Framework Architecture

```
PipelineOrchestrator
├── DslEvaluatorRegistry (maps DSL types to evaluators)
├── ExecutorResolver (resolves components by DSL type)
├── ScriptEvaluator<T> (evaluates scripts)
├── ConfigurationLoader<T> (loads config)
├── PipelineExecutor<T> (executes definitions)
└── AgentManager<T> (handles agent execution)
```

This architecture provides:
- **Pluggability**: Easy to add new DSL types
- **Separation of Concerns**: Each component has a specific responsibility  
- **Backward Compatibility**: Existing pipeline DSL works unchanged
- **Extensibility**: New features can be added per DSL type