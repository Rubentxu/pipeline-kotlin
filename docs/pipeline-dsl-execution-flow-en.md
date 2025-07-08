# Pipeline DSL Execution Flow - Sequence Diagrams

This document describes the internal execution flow of the Pipeline DSL, from compilation to step execution.

## Table of Contents

1. [DSL Compilation](#dsl-compilation)
2. [Pipeline Construction](#pipeline-construction)
3. [Stage Execution](#stage-execution)
4. [Step Execution](#step-execution)
5. [Plugin System](#plugin-system)
6. [Post-Execution Handling](#post-execution-handling)

## DSL Compilation

This diagram shows how a pipeline script is compiled from source code to an executable script.

```mermaid
sequenceDiagram
    participant ScriptRunner as PipelineScriptRunner
    participant Engine as PipelineDslEngine
    participant KotlinScript as Kotlin Scripting API
    participant Cache as ScriptCache
    participant SecurityPolicy
    participant Compiler as KotlinCompiler

    ScriptRunner->>Engine: compile(scriptSource)
    activate Engine
    
    Engine->>Cache: getCached(scriptHash)
    activate Cache
    alt script in cache
        Cache-->>Engine: CompiledScript
        Engine-->>ScriptRunner: CompiledScript (cached)
        deactivate Cache
        deactivate Engine
    else not in cache
        Cache-->>Engine: null
        deactivate Cache
        
        Engine->>SecurityPolicy: createSecurityConfiguration()
        activate SecurityPolicy
        Note over SecurityPolicy: Define policies:<br/>- No System.exit()<br/>- No reflection<br/>- No file access outside workspace
        SecurityPolicy-->>Engine: SecurityConfig
        deactivate SecurityPolicy
        
        Engine->>KotlinScript: createCompilationConfiguration()
        activate KotlinScript
        Note over KotlinScript: Configure:<br/>- Implicit receivers (PipelineBlock)<br/>- Default imports<br/>- Security restrictions
        
        KotlinScript->>Compiler: compile(script, configuration)
        activate Compiler
        
        alt compilation error
            Compiler-->>KotlinScript: CompilationError
            KotlinScript-->>Engine: ScriptCompilationException
            Engine-->>ScriptRunner: throw CompilationException
            deactivate Compiler
            deactivate KotlinScript
            deactivate Engine
        end
        
        Compiler-->>KotlinScript: CompiledScript
        deactivate Compiler
        
        KotlinScript-->>Engine: CompiledScript
        deactivate KotlinScript
        
        Engine->>Cache: store(scriptHash, CompiledScript)
        activate Cache
        Cache-->>Engine: stored
        deactivate Cache
        
        Engine-->>ScriptRunner: CompiledScript
        deactivate Engine
    end
```

## Pipeline Construction

This diagram shows how the pipeline structure is built from the compiled DSL.

```mermaid
sequenceDiagram
    participant ScriptRunner as PipelineScriptRunner
    participant CompiledScript
    participant PipelineFunc as pipeline() function
    participant PipelineBlock
    participant AgentBlock
    participant EnvBlock as EnvironmentBlock
    participant StagesBlock
    participant StageBlock
    participant PipelineDefinition
    participant Pipeline

    ScriptRunner->>CompiledScript: evaluate()
    activate CompiledScript
    
    CompiledScript->>PipelineFunc: pipeline { ... }
    activate PipelineFunc
    Note over PipelineFunc: Global function that<br/>creates PipelineBlock
    
    PipelineFunc->>PipelineBlock: new PipelineBlock()
    activate PipelineBlock
    
    PipelineFunc->>PipelineBlock: apply(userDslBlock)
    Note over PipelineBlock: Executes user's<br/>DSL block
    
    PipelineBlock->>AgentBlock: agent { docker { ... } }
    activate AgentBlock
    AgentBlock->>AgentBlock: configure DockerAgent
    AgentBlock-->>PipelineBlock: Agent configured
    deactivate AgentBlock
    
    PipelineBlock->>EnvBlock: environment { ... }
    activate EnvBlock
    EnvBlock->>EnvBlock: set environment variables
    EnvBlock-->>PipelineBlock: EnvVars configured
    deactivate EnvBlock
    
    PipelineBlock->>StagesBlock: stages { ... }
    activate StagesBlock
    
    loop for each defined stage
        StagesBlock->>StageBlock: stage("name") { ... }
        activate StageBlock
        
        StageBlock->>StageBlock: configure steps block
        Note over StageBlock: Steps block will be<br/>executed later
        
        StageBlock-->>StagesBlock: Stage configured
        deactivate StageBlock
    end
    
    StagesBlock-->>PipelineBlock: List<Stage> configured
    deactivate StagesBlock
    
    PipelineBlock->>PipelineDefinition: new PipelineDefinition(blocks)
    activate PipelineDefinition
    
    PipelineDefinition-->>PipelineFunc: PipelineDefinition
    deactivate PipelineBlock
    deactivate PipelineDefinition
    
    PipelineFunc-->>CompiledScript: PipelineDefinition
    deactivate PipelineFunc
    
    CompiledScript-->>ScriptRunner: PipelineDefinition
    deactivate CompiledScript
    
    ScriptRunner->>PipelineDefinition: build()
    activate PipelineDefinition
    
    PipelineDefinition->>Pipeline: new Pipeline(config)
    activate Pipeline
    Note over Pipeline: Converts definition<br/>to executable pipeline
    
    Pipeline->>Pipeline: registerEventHandlers()
    Pipeline->>Pipeline: validateConfiguration()
    
    Pipeline-->>PipelineDefinition: Pipeline instance
    deactivate Pipeline
    
    PipelineDefinition-->>ScriptRunner: Pipeline ready to execute
    deactivate PipelineDefinition
```

## Stage Execution

This diagram shows how pipeline stages are executed sequentially.

```mermaid
sequenceDiagram
    participant PipelineRunner
    participant Pipeline
    participant EventBus
    participant StageExecutor
    participant Agent
    participant StageBlock
    participant StepsBlock
    participant PostExecution

    PipelineRunner->>Pipeline: executeStages()
    activate Pipeline
    
    Pipeline->>EventBus: emit(PipelineStarted)
    activate EventBus
    EventBus-->>Pipeline: event dispatched
    deactivate EventBus
    
    Pipeline->>Pipeline: initializeGlobalAgent()
    
    loop for each stage
        Pipeline->>EventBus: emit(StageStarted)
        activate EventBus
        EventBus-->>Pipeline: event dispatched
        deactivate EventBus
        
        Pipeline->>StageExecutor: run(stage, context)
        activate StageExecutor
        
        StageExecutor->>Agent: getOrCreate()
        activate Agent
        alt agent does not exist
            Agent->>Agent: createDockerContainer()
            Agent->>Agent: startContainer()
        end
        Agent-->>StageExecutor: agent ready
        deactivate Agent
        
        StageExecutor->>StageBlock: new StageBlock(stage)
        activate StageBlock
        
        StageExecutor->>StageBlock: apply(stage.dslBlock)
        Note over StageBlock: Execute stage's<br/>DSL block
        
        StageBlock->>StepsBlock: steps { ... }
        activate StepsBlock
        Note over StepsBlock: Execute all<br/>defined steps
        
        alt step fails
            StepsBlock-->>StageBlock: StepFailedException
            StageBlock-->>StageExecutor: StageFailure
            deactivate StepsBlock
            deactivate StageBlock
            
            StageExecutor->>PostExecution: runStagePost(failure)
            activate PostExecution
            PostExecution-->>StageExecutor: post executed
            deactivate PostExecution
            
            StageExecutor-->>Pipeline: StageFailure
            deactivate StageExecutor
            
            Pipeline->>EventBus: emit(StageFailed)
            Pipeline->>Pipeline: stopExecution()
            Pipeline-->>PipelineRunner: FAILURE
            deactivate Pipeline
        else steps successful
            StepsBlock-->>StageBlock: success
            deactivate StepsBlock
            
            StageBlock-->>StageExecutor: StageSuccess
            deactivate StageBlock
            
            StageExecutor->>PostExecution: runStagePost(success)
            activate PostExecution
            PostExecution-->>StageExecutor: post executed
            deactivate PostExecution
            
            StageExecutor-->>Pipeline: StageSuccess
            deactivate StageExecutor
            
            Pipeline->>EventBus: emit(StageCompleted)
        end
    end
    
    Pipeline->>EventBus: emit(PipelineCompleted)
    Pipeline-->>PipelineRunner: SUCCESS
    deactivate Pipeline
```

## Step Execution

This diagram details how different types of steps are executed within a stage.

```mermaid
sequenceDiagram
    participant StepsBlock
    participant StepFactory
    participant ShStep
    participant EchoStep
    participant WriteFileStep
    participant Shell
    participant Logger
    participant FileSystem
    participant SecurityManager
    participant Agent

    StepsBlock->>StepsBlock: executeSteps()
    activate StepsBlock
    
    loop for each defined step
        alt sh step type
            StepsBlock->>StepFactory: createShStep(command)
            activate StepFactory
            StepFactory->>ShStep: new ShStep(command)
            StepFactory-->>StepsBlock: ShStep
            deactivate StepFactory
            
            StepsBlock->>ShStep: execute(context)
            activate ShStep
            
            ShStep->>Shell: new Shell(workingDir)
            activate Shell
            
            ShStep->>SecurityManager: validateCommand(command)
            activate SecurityManager
            alt command not allowed
                SecurityManager-->>ShStep: SecurityException
                ShStep-->>StepsBlock: StepFailedException
                deactivate SecurityManager
                deactivate ShStep
                deactivate Shell
            end
            SecurityManager-->>ShStep: command validated
            deactivate SecurityManager
            
            ShStep->>Shell: execute(command, agent)
            
            Shell->>Agent: runInContainer(command)
            activate Agent
            Note over Agent: Execute command<br/>in Docker container
            Agent-->>Shell: CommandResult(output, exitCode)
            deactivate Agent
            
            alt exitCode != 0
                Shell-->>ShStep: CommandFailedException
                ShStep-->>StepsBlock: StepFailedException
                deactivate Shell
                deactivate ShStep
            end
            
            Shell-->>ShStep: output
            deactivate Shell
            
            ShStep-->>StepsBlock: stepResult
            deactivate ShStep
            
        else echo step type
            StepsBlock->>StepFactory: createEchoStep(message)
            activate StepFactory
            StepFactory->>EchoStep: new EchoStep(message)
            StepFactory-->>StepsBlock: EchoStep
            deactivate StepFactory
            
            StepsBlock->>EchoStep: execute(context)
            activate EchoStep
            
            EchoStep->>Logger: log(level, message)
            activate Logger
            Logger-->>EchoStep: logged
            deactivate Logger
            
            EchoStep-->>StepsBlock: success
            deactivate EchoStep
            
        else writeFile step type
            StepsBlock->>StepFactory: createWriteFileStep(file, content)
            activate StepFactory
            StepFactory->>WriteFileStep: new WriteFileStep(file, content)
            StepFactory-->>StepsBlock: WriteFileStep
            deactivate StepFactory
            
            StepsBlock->>WriteFileStep: execute(context)
            activate WriteFileStep
            
            WriteFileStep->>SecurityManager: validateFilePath(file)
            activate SecurityManager
            alt path outside workspace
                SecurityManager-->>WriteFileStep: SecurityException
                WriteFileStep-->>StepsBlock: StepFailedException
                deactivate SecurityManager
                deactivate WriteFileStep
            end
            SecurityManager-->>WriteFileStep: path validated
            deactivate SecurityManager
            
            WriteFileStep->>FileSystem: writeFile(file, content)
            activate FileSystem
            FileSystem-->>WriteFileStep: file written
            deactivate FileSystem
            
            WriteFileStep-->>StepsBlock: success
            deactivate WriteFileStep
        end
    end
    
    StepsBlock-->>StepsBlock: all steps completed
    deactivate StepsBlock
```

## Plugin System

This diagram shows how plugins are discovered and loaded in the system.

```mermaid
sequenceDiagram
    participant Pipeline
    participant PluginManager
    participant ServiceLoader
    participant MavenResolver
    participant PluginRegistry
    participant ClassLoader as PluginClassLoader
    participant Plugin as StepPlugin
    participant SecurityValidator

    Pipeline->>PluginManager: loadPlugins(pluginConfig)
    activate PluginManager
    
    PluginManager->>PluginManager: loadBuiltInPlugins()
    
    PluginManager->>ServiceLoader: load(Step.class)
    activate ServiceLoader
    Note over ServiceLoader: Search for Step<br/>implementations in classpath
    ServiceLoader->>ServiceLoader: findServiceProviders()
    ServiceLoader-->>PluginManager: List<Step> builtInSteps
    deactivate ServiceLoader
    
    alt external plugins configured
        PluginManager->>MavenResolver: resolve(pluginDependencies)
        activate MavenResolver
        
        loop for each dependency
            MavenResolver->>MavenResolver: downloadArtifact(groupId, artifactId, version)
            Note over MavenResolver: Download JAR from<br/>Maven repository
            
            MavenResolver->>SecurityValidator: validateJar(jarFile)
            activate SecurityValidator
            SecurityValidator->>SecurityValidator: checkSignature()
            SecurityValidator->>SecurityValidator: verifySHA256()
            alt jar invalid
                SecurityValidator-->>MavenResolver: SecurityException
                MavenResolver-->>PluginManager: PluginLoadException
                deactivate SecurityValidator
                deactivate MavenResolver
            end
            SecurityValidator-->>MavenResolver: jar validated
            deactivate SecurityValidator
        end
        
        MavenResolver-->>PluginManager: List<File> pluginJars
        deactivate MavenResolver
        
        loop for each plugin jar
            PluginManager->>PluginClassLoader: new URLClassLoader(jarUrl, parent)
            activate PluginClassLoader
            Note over PluginClassLoader: Create isolated<br/>ClassLoader for plugin
            
            PluginManager->>ServiceLoader: load(Step.class, classLoader)
            activate ServiceLoader
            ServiceLoader->>PluginClassLoader: loadClass("META-INF/services/Step")
            PluginClassLoader-->>ServiceLoader: service definitions
            
            ServiceLoader->>Plugin: newInstance()
            activate Plugin
            Plugin-->>ServiceLoader: Step instance
            deactivate Plugin
            
            ServiceLoader-->>PluginManager: List<Step> pluginSteps
            deactivate ServiceLoader
            deactivate PluginClassLoader
        end
    end
    
    PluginManager->>PluginRegistry: registerSteps(allSteps)
    activate PluginRegistry
    
    loop for each step
        PluginRegistry->>PluginRegistry: validateStepName(step.name)
        alt duplicate name
            PluginRegistry-->>PluginManager: DuplicateStepException
        end
        
        PluginRegistry->>PluginRegistry: register(stepName, stepInstance)
    end
    
    PluginRegistry-->>PluginManager: registration complete
    deactivate PluginRegistry
    
    PluginManager-->>Pipeline: plugins loaded
    deactivate PluginManager
```

## Post-Execution Handling

This diagram shows how post-execution actions are handled at different levels.

```mermaid
sequenceDiagram
    participant Pipeline
    participant Stage
    participant PostExecutor as PostExecutionHandler
    participant AlwaysBlock
    participant SuccessBlock
    participant FailureBlock
    participant CleanupManager
    participant NotificationService

    alt stage post-execution
        Stage->>PostExecutor: runPostExecution(stageResult)
        activate PostExecutor
        
        PostExecutor->>PostExecutor: determineBlocks(result)
        Note over PostExecutor: Select blocks based on<br/>stage result
        
        alt result is FAILURE
            PostExecutor->>FailureBlock: execute()
            activate FailureBlock
            FailureBlock->>FailureBlock: run failure actions
            FailureBlock-->>PostExecutor: executed
            deactivate FailureBlock
        else result is SUCCESS
            PostExecutor->>SuccessBlock: execute()
            activate SuccessBlock
            SuccessBlock->>SuccessBlock: run success actions
            SuccessBlock-->>PostExecutor: executed
            deactivate SuccessBlock
        end
        
        PostExecutor->>AlwaysBlock: execute()
        activate AlwaysBlock
        AlwaysBlock->>CleanupManager: cleanupResources()
        activate CleanupManager
        CleanupManager-->>AlwaysBlock: cleaned
        deactivate CleanupManager
        AlwaysBlock-->>PostExecutor: executed
        deactivate AlwaysBlock
        
        PostExecutor-->>Stage: post-execution complete
        deactivate PostExecutor
    end
    
    alt pipeline post-execution
        Pipeline->>PostExecutor: runPostExecution(pipelineResult)
        activate PostExecutor
        
        PostExecutor->>PostExecutor: aggregateResults()
        Note over PostExecutor: Combine results from<br/>all stages
        
        alt any stage failed
            PostExecutor->>FailureBlock: execute()
            activate FailureBlock
            
            FailureBlock->>NotificationService: sendFailureNotification()
            activate NotificationService
            NotificationService-->>FailureBlock: notification sent
            deactivate NotificationService
            
            FailureBlock->>FailureBlock: archiveFailureLogs()
            FailureBlock-->>PostExecutor: executed
            deactivate FailureBlock
        else all stages succeeded
            PostExecutor->>SuccessBlock: execute()
            activate SuccessBlock
            
            SuccessBlock->>SuccessBlock: archiveArtifacts()
            SuccessBlock->>NotificationService: sendSuccessNotification()
            activate NotificationService
            NotificationService-->>SuccessBlock: notification sent
            deactivate NotificationService
            
            SuccessBlock-->>PostExecutor: executed
            deactivate SuccessBlock
        end
        
        PostExecutor->>AlwaysBlock: execute()
        activate AlwaysBlock
        
        AlwaysBlock->>CleanupManager: cleanupPipeline()
        activate CleanupManager
        CleanupManager->>CleanupManager: stopContainers()
        CleanupManager->>CleanupManager: removeTempFiles()
        CleanupManager->>CleanupManager: releaseResources()
        CleanupManager-->>AlwaysBlock: all cleaned
        deactivate CleanupManager
        
        AlwaysBlock-->>PostExecutor: executed
        deactivate AlwaysBlock
        
        PostExecutor-->>Pipeline: post-execution complete
        deactivate PostExecutor
    end
```

## Architecture Notes

### Design Principles

1. **Separation of Concerns**: Each component has a clear and well-defined responsibility
2. **Security by Default**: All operations go through security validations
3. **Extensibility**: The plugin system allows adding new steps without modifying the core
4. **Isolation**: Plugins run in separate ClassLoaders to avoid conflicts

### Key Security Points

- **Script Sandboxing**: Scripts run with restrictive policies
- **Command Validation**: sh commands are validated before execution
- **Agent Isolation**: Commands execute in Docker containers
- **Plugin Validation**: Plugin JARs are verified before loading

### Implementation References

- `PipelineDslEngine`: Main compilation engine
- `Pipeline`: Execution orchestrator
- `StageExecutor`: Individual stage executor
- `StepsBlock`: Step container and executor
- `PluginManager`: Plugin system manager