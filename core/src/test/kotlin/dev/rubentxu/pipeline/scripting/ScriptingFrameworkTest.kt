package dev.rubentxu.pipeline.scripting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ScriptingFrameworkTest : StringSpec({
    
    "should create DSL evaluator registry" {
        val registry = DslEvaluatorRegistry()
        
        val pipelineEvaluator = PipelineDslEvaluator()
        val taskEvaluator = TaskDslEvaluator()
        
        registry.registerEvaluator("pipeline", pipelineEvaluator)
        registry.registerEvaluator("task", taskEvaluator)
        
        val retrievedPipelineEvaluator = registry.getEvaluator<Any>("pipeline")
        val retrievedTaskEvaluator = registry.getEvaluator<Any>("task")
        
        retrievedPipelineEvaluator shouldNotBe null
        retrievedTaskEvaluator shouldNotBe null
        
        registry.getRegisteredDslTypes() shouldBe setOf("pipeline", "task")
    }
    
    "should create execution context" {
        val context = ExecutionContext(
            environment = mapOf("TEST" to "value"),
            dslType = "pipeline"
        )
        
        context.dslType shouldBe "pipeline"
        context.environment["TEST"] shouldBe "value"
    }
    
    "should create task definition" {
        val task = TaskDefinition(
            name = "test-task",
            description = "Test task description",
            command = "echo 'hello world'",
            environment = mapOf("ENV_VAR" to "value")
        )
        
        task.name shouldBe "test-task"
        task.description shouldBe "Test task description"
        task.command shouldBe "echo 'hello world'"
        task.environment["ENV_VAR"] shouldBe "value"
    }
    
    "should create default executor resolver" {
        val resolver = DefaultExecutorResolver()
        
        // Test that it can resolve components for known DSL types
        val pipelineConfigLoader = resolver.resolveConfigurationLoader("pipeline")
        val taskConfigLoader = resolver.resolveConfigurationLoader("task")
        
        pipelineConfigLoader shouldNotBe null
        taskConfigLoader shouldNotBe null
        
        val pipelineExecutor = resolver.resolvePipelineExecutor("pipeline")
        val taskExecutor = resolver.resolvePipelineExecutor("task")
        
        pipelineExecutor shouldNotBe null
        taskExecutor shouldNotBe null
        
        val pipelineAgentManager = resolver.resolveAgentManager("pipeline")
        val taskAgentManager = resolver.resolveAgentManager("task")
        
        pipelineAgentManager shouldNotBe null
        taskAgentManager shouldNotBe null
    }
})