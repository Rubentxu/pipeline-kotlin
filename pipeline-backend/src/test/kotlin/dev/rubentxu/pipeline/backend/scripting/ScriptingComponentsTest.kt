package dev.rubentxu.pipeline.backend.scripting

import dev.rubentxu.pipeline.scripting.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.nio.file.Files

class ScriptingComponentsTest {

    @Test
    fun `ExecutionContext should create correctly`() {
        val context = ExecutionContext(
            environment = mapOf("DSL_TYPE" to "pipeline"),
            dslType = "pipeline"
        )
        
        assertEquals("pipeline", context.dslType)
        assertEquals("pipeline", context.environment["DSL_TYPE"])
    }

    @Test
    fun `TaskDefinition should create correctly`() {
        val tasks = listOf("task1", "task2", "task3")
        val taskDef = TaskDefinition(tasks)
        
        assertEquals(3, taskDef.tasks.size)
        assertEquals("task1", taskDef.tasks[0])
    }

    @Test
    fun `DslEvaluatorRegistry should register and retrieve evaluators`() {
        val registry = DslEvaluatorRegistry<String>()
        val mockEvaluator = object : ScriptEvaluator<String> {
            override fun evaluate(scriptPath: Path): Result<String> {
                return Result.success("test")
            }
        }
        
        registry.register("test-dsl", mockEvaluator)
        
        val retrievedEvaluator = registry.getEvaluator("test-dsl")
        assertNotNull(retrievedEvaluator)
        assertEquals(setOf("test-dsl"), registry.getSupportedTypes())
    }

    @Test
    fun `ExecutorResolver should resolve correct manager`() {
        val mockManager = object : AgentManager<String> {
            override fun canHandle(context: ExecutionContext): Boolean {
                return context.dslType == "test"
            }
            
            override fun execute(definition: String, config: Any, files: List<Path>): Result<Any> {
                return Result.success("executed")
            }
        }
        
        val resolver = ExecutorResolver(listOf(mockManager))
        val context = ExecutionContext(emptyMap(), "test")
        
        val resolvedManager = resolver.resolve(context)
        assertNotNull(resolvedManager)
        assertTrue(resolvedManager.canHandle(context))
    }

    @Test
    fun `TaskDslEvaluator should parse simple text correctly`() {
        // Create a temporary file with task content
        val tempFile = Files.createTempFile("test", ".txt")
        val content = """
            # This is a comment
            task1
            task2
            
            task3
        """.trimIndent()
        
        Files.write(tempFile, content.toByteArray())
        
        val evaluator = TaskDslEvaluator()
        val result = evaluator.evaluate(tempFile)
        
        assertTrue(result.isSuccess)
        val taskDef = result.getOrThrow()
        assertEquals(3, taskDef.tasks.size)
        assertEquals(listOf("task1", "task2", "task3"), taskDef.tasks)
        
        // Clean up
        Files.delete(tempFile)
    }
}