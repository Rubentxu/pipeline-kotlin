package dev.rubentxu.pipeline.testing.execution

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.testing.PipelineExecutionResult
import dev.rubentxu.pipeline.testing.StepMockHandler
import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder
import dev.rubentxu.pipeline.testing.extensions.InterceptorRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Improved execution context for running Pipeline DSL scripts with method interception.
 * 
 * This replaces the string parsing approach with direct method interception, providing
 * a more reliable and maintainable testing framework.
 */
class InterceptingMockPipelineExecutionContext(
    private val mockHandlers: Map<String, StepMockHandler>,
    private val recorder: StepInvocationRecorder
) {
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
    
    /**
     * Executes a pipeline script with intercepted steps.
     *
     * @param scriptContent Script content.
     * @return Execution result.
     */
    fun executePipelineScript(scriptContent: String): PipelineExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Configure the interceptor registry with our handlers
            InterceptorRegistry.configure(mockHandlers, recorder)
            
            // Create mock pipeline
            val mockPipeline = createMockPipeline()
            
            // Create steps block (will use intercepting extension functions)
            val stepsBlock = dev.rubentxu.pipeline.dsl.StepsBlock(mockPipeline)
            
            // Execute script with real Kotlin script engine for better accuracy
            logger.info("Executing pipeline script with method interception")
            logger.info("Script content length: ${scriptContent.length} characters")
            
            val result = executeScriptWithInterception(scriptContent, mockPipeline, stepsBlock)
            
            when {
                result.reports.any { it.severity >= ScriptDiagnostic.Severity.ERROR } -> {
                    val errors = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                    val errorMessage = errors.joinToString("\n") { "${it.severity}: ${it.message}" }
                    val executionTime = System.currentTimeMillis() - startTime
                    PipelineExecutionResult.Failure(RuntimeException("Script compilation failed: $errorMessage"), executionTime)
                }
                else -> {
                    val executionTime = System.currentTimeMillis() - startTime
                    PipelineExecutionResult.Success(executionTime)
                }
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            PipelineExecutionResult.Failure(e, executionTime)
        } finally {
            // Clean up the interceptor registry
            InterceptorRegistry.clear()
        }
    }
    
    /**
     * Creates a mock pipeline for testing.
     *
     * @return Mocked Pipeline instance.
     */
    private fun createMockPipeline(): Pipeline {
        val mockAgent = DockerAgent(image = "test-image", tag = "latest")
        val mockEnv = EnvVars(mutableMapOf()).apply {
            this["TEST_MODE"] = "true"
            this["WORKSPACE"] = createTempWorkspace().toString()
        }
        val mockPostExecution = PostExecution()
        val mockConfig = object : IPipelineConfig {}
        
        return Pipeline(
            agent = mockAgent,
            stages = emptyList(),
            env = mockEnv,
            postExecution = mockPostExecution,
            pipelineConfig = mockConfig
        )
    }
    
    /**
     * Executes the script using Kotlin scripting with interception.
     *
     * @param scriptContent Script content.
     * @param mockPipeline Mocked pipeline.
     * @param stepsBlock Steps block that will use intercepting extension functions.
     * @return Script evaluation result.
     */
    private fun executeScriptWithInterception(
        scriptContent: String,
        mockPipeline: Pipeline,
        stepsBlock: dev.rubentxu.pipeline.dsl.StepsBlock
    ): ResultWithDiagnostics<EvaluationResult> {
        
        // Create script source
        val scriptSource = scriptContent.toScriptSource()
        
        // Configure compilation
        val compilationConfiguration = createScriptCompilationConfiguration()
        
        // Configure evaluation with intercepting context
        val evaluationConfiguration = createScriptEvaluationConfiguration(mockPipeline, stepsBlock)
        
        // Execute script
        val scriptingHost = BasicJvmScriptingHost()
        return scriptingHost.eval(scriptSource, compilationConfiguration, evaluationConfiguration)
    }
    
    /**
     * Creates the script compilation configuration.
     *
     * @return Compilation configuration.
     */
    private fun createScriptCompilationConfiguration(): ScriptCompilationConfiguration {
        return createJvmCompilationConfigurationFromTemplate<PipelineScript> {
            defaultImports(
                "dev.rubentxu.pipeline.dsl.*",
                "dev.rubentxu.pipeline.model.pipeline.*",
                "dev.rubentxu.pipeline.testing.mocks.*",
                "dev.rubentxu.pipeline.testing.extensions.*"
            )
            
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
            }
            
            ide {
                acceptedLocations(ScriptAcceptedLocation.Everywhere)
            }
        }
    }
    
    /**
     * Creates the script evaluation configuration with intercepting context.
     *
     * @param mockPipeline Mocked pipeline.
     * @param stepsBlock Steps block that will use intercepting extension functions.
     * @return Evaluation configuration.
     */
    private fun createScriptEvaluationConfiguration(
        mockPipeline: Pipeline,
        stepsBlock: dev.rubentxu.pipeline.dsl.StepsBlock
    ): ScriptEvaluationConfiguration {
        return ScriptEvaluationConfiguration {
            implicitReceivers(stepsBlock)
            
            providedProperties(
                "pipeline" to mockPipeline,
                "steps" to stepsBlock,
                "env" to mockPipeline.env
            )
            
            constructorArgs(mockPipeline, stepsBlock)
        }
    }
    
    /**
     * Creates a temporary workspace for testing.
     *
     * @return Path to the temporary directory.
     */
    private fun createTempWorkspace(): Path {
        return Files.createTempDirectory("pipeline-test-workspace")
    }
}