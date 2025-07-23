package dev.rubentxu.pipeline.integration

import dev.rubentxu.pipeline.context.*
import dev.rubentxu.pipeline.context.managers.*
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.context.modules.*
import dev.rubentxu.pipeline.context.unified.UnifiedPipelineContext
import dev.rubentxu.pipeline.context.unified.UnifiedContextFactory
import dev.rubentxu.pipeline.context.unified.LocalUnifiedPipelineContext
import dev.rubentxu.pipeline.context.unified.EnhancedStepsBlock
import dev.rubentxu.pipeline.context.unified.EnhancedStepsBlockFactory
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.model.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject
import kotlin.test.*

/**
 * Unified Context Integration Tests - Consolidated DSL Integration
 * 
 * These tests validate the unified context approach that consolidates all phases:
 * 
 * 1. **UnifiedPipelineContext**: Single context using SimplePipelineContext
 * 2. **EnhancedStepsBlock**: Improved step execution with service locator
 * 3. **Service Locator Integration**: Clean manager access
 * 4. **Context Scoping**: Isolated execution contexts
 * 5. **Testing Framework**: Easy mocking through service locator
 */
class UnifiedContextIntegrationTest : KoinTest {
    
    // Injected real managers
    private val serviceLocator: IServiceLocator by inject()
    private val parameterManager: IParameterManager by inject()
    private val environmentManager: IEnvironmentManager by inject()
    private val loggerManager: ILoggerManager by inject()
    private val workingDirectoryManager: IWorkingDirectoryManager by inject()
    
    @BeforeEach
    fun setup() {
        startKoin {
            modules(realManagersModule, serviceLocatorModule)
        }
    }
    
    @AfterEach
    fun cleanup() {
        stopKoin()
    }
    
    @Test
    fun `🔧 UnifiedPipelineContext integrates with SimplePipelineContext`() {
        // Given: UnifiedPipelineContext created from service locator
        val unifiedContext = UnifiedContextFactory.create(serviceLocator)
        
        // When: We access context as SimplePipelineContext
        val simpleContext: SimplePipelineContext = unifiedContext
        
        // Then: All SimplePipelineContext capabilities work
        assertNotNull(simpleContext.parameters)
        assertNotNull(simpleContext.environment)
        assertNotNull(simpleContext.logger)
        assertNotNull(simpleContext.workingDirectory)
        assertEquals(serviceLocator, simpleContext.serviceLocator)
        
        // And: Unified context provides enhanced capabilities
        simpleContext.parameters.set("testKey", "testValue")
        assertEquals("testValue", unifiedContext.parameters.get<String>("testKey"))
        
        simpleContext.environment.set("TEST_VAR", "test_value")
        assertEquals("test_value", unifiedContext.environment.get("TEST_VAR"))
    }
    
    @Test
    fun `🏗️ EnhancedStepsBlock provides improved step execution`() = runBlocking {
        // Given: EnhancedStepsBlock with unified context
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We execute enhanced steps
        stepsBlock.executeWithContext {
            echo("Testing enhanced steps block")
            setParam("blockTest", "enhanced-success")
            setEnvVar("BLOCK_ENV", "enhanced-mode")
            sleep(10) // Short sleep for testing
            
            val param = getParam<String>("blockTest")
            val envVar = getEnvVar("BLOCK_ENV")
            
            // Then: All operations work through unified context
            assertEquals("enhanced-success", param)
            assertEquals("enhanced-mode", envVar)
        }
        
        // And: Changes persist in the real managers
        assertEquals("enhanced-success", parameterManager.get<String>("blockTest"))
        assertEquals("enhanced-mode", environmentManager.get("BLOCK_ENV"))
    }
    
    @Test
    fun `🔄 Enhanced shell execution works correctly`() = runBlocking {
        // Given: EnhancedStepsBlock with shell capabilities
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We execute shell commands
        stepsBlock.executeWithContext {
            val result1 = sh("echo 'Hello Enhanced Context'", returnStdout = true)
            sh("echo 'Enhanced integration test'") // Non-returning
            
            // Then: Shell execution returns expected mock results
            assertTrue(result1.contains("Hello Enhanced Context"))
        }
        
        // And: Commands are properly logged
        val logs = loggerManager.getLogger().logs()
        assertTrue(logs.any { it.contains("Executing shell command") })
    }
    
    @Test
    fun `📁 Enhanced file operations use working directory manager`() = runBlocking {
        // Given: EnhancedStepsBlock with file operations
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We perform file operations
        stepsBlock.executeWithContext {
            writeFile("enhanced-test.txt", "Enhanced file content")
            val content = readFile("enhanced-test.txt")
            
            // Then: File operations work through unified context
            assertTrue(content.contains("enhanced-test.txt")) // Mock returns path info
        }
        
        // And: File operations are properly logged
        val logs = loggerManager.getLogger().logs()
        assertTrue(logs.any { it.contains("Writing file") })
        assertTrue(logs.any { it.contains("Reading file") })
    }
    
    @Test
    fun `⚡ Parallel execution works with enhanced context`() = runBlocking {
        // Given: EnhancedStepsBlock with parallel capabilities
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We execute parallel branches
        stepsBlock.executeWithContext {
            parallel(
                "enhanced-branch-a" to {
                    setParam("branch", "enhanced-a")
                    sleep(20)
                    echo("Enhanced Branch A completed")
                },
                "enhanced-branch-b" to {
                    setParam("branch", "enhanced-b")  
                    sleep(10)
                    echo("Enhanced Branch B completed")
                }
            )
        }
        
        // Then: Parallel execution completes successfully
        val logs = loggerManager.getLogger().logs()
        assertTrue(logs.any { it.contains("Enhanced Branch A completed") })
        assertTrue(logs.any { it.contains("Enhanced Branch B completed") })
        assertTrue(logs.any { it.contains("All parallel branches completed") })
    }
    
    @Test
    fun `🔁 Enhanced retry logic with structured logging`() = runBlocking {
        // Given: EnhancedStepsBlock with retry capability
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        var attempts = 0
        
        // When: We use retry with enhanced context
        stepsBlock.executeWithContext {
            val result = retry(maxRetries = 3, delayMillis = 5) {
                attempts++
                if (attempts < 2) {
                    throw RuntimeException("Enhanced retry test failure $attempts")
                }
                setParam("enhancedRetryResult", "success-after-$attempts-attempts")
                "enhanced-retry-successful"
            }
            
            // Then: Retry succeeds with enhanced logging
            assertEquals("enhanced-retry-successful", result)
            assertEquals(2, attempts)
        }
        
        // And: Parameters are correctly set
        assertEquals("success-after-2-attempts", parameterManager.get<String>("enhancedRetryResult"))
        
        // And: Enhanced structured logging works
        val logContext = loggerManager.getContext()
        assertTrue(logContext.containsKey("correlationId"))
    }
    
    @Test
    fun `🧪 Thread-local context works for enhanced @Step compatibility`() = runBlocking {
        // Given: EnhancedStepsBlock with thread-local context
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We access thread-local context (for @Step functions)
        stepsBlock.executeWithContext {
            val currentContext = LocalUnifiedPipelineContext.current
            
            // Then: Thread-local context is properly set
            assertNotNull(currentContext)
            assertTrue(currentContext is UnifiedPipelineContext)
            
            // And: Context provides full functionality
            currentContext.parameters.set("threadLocalEnhanced", "working")
            assertEquals("working", currentContext.parameters.get<String>("threadLocalEnhanced"))
            
            // And: Can be used by simulated @Step functions
            val stepResult = simulateEnhancedStepFunction()
            assertEquals("enhanced-step-executed", stepResult)
        }
    }
    
    @Test
    fun `🔄 Context scoping provides proper isolation`() = runBlocking {
        // Given: EnhancedStepsBlock with scoping
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We create nested scopes
        stepsBlock.executeWithContext {
            setParam("global", "global-enhanced")
            setEnvVar("GLOBAL_VAR", "global-enhanced-env")
            
            withScope("enhanced-nested-scope") {
                // Override in nested scope
                setParam("global", "nested-enhanced")
                setEnvVar("SCOPED_VAR", "scoped-enhanced-env")
                
                // Then: Scoped values take precedence
                assertEquals("nested-enhanced", getParam<String>("global"))
                assertEquals("scoped-enhanced-env", getEnvVar("SCOPED_VAR"))
                assertEquals("global-enhanced-env", getEnvVar("GLOBAL_VAR")) // Inherited
            }
            
            // And: Global scope restored after nested scope
            assertEquals("global-enhanced", getParam<String>("global"))
            assertNull(getEnvVar("SCOPED_VAR")) // Not inherited back
        }
    }
    
    @Test
    fun `📊 Service locator enables enhanced testing`() = runBlocking {
        // Given: Mock implementations for testing
        val mockParameterManager = object : IParameterManager {
            private val params = mutableMapOf<String, Any>()
            override fun set(key: String, value: Any) { params[key] = value }
            override fun <T> get(key: String): T? = params[key] as T?
            override fun <T> get(key: String, defaultValue: T): T = get<T>(key) ?: defaultValue
            override fun has(key: String): Boolean = params.containsKey(key)
            override fun remove(key: String): Boolean = params.remove(key) != null
            override fun getAll(): Map<String, Any> = params.toMap()
            override fun clear() { params.clear() }
            override fun observeParameter(key: String) = TODO("Not needed for test")
            override fun observeAll() = TODO("Not needed for test")
            override fun createScope(name: String): IParameterManager = this // Simple mock scope
        }
        
        val mockLoggerManager = object : ILoggerManager {
            private val logs = mutableListOf<String>()
            override fun getLogger() = object : ILogger {
                override fun info(message: String) { logs.add("INFO: $message") }
                override fun warn(message: String) { logs.add("WARN: $message") }
                override fun debug(message: String) { logs.add("DEBUG: $message") }
                override fun error(message: String) { logs.add("ERROR: $message") }
                override fun system(message: String) { logs.add("SYSTEM: $message") }
                override fun logs(): List<String> = logs
                override fun changeLogLevel(logLevel: LogLevel) {}
                override fun changeConfigurationStrategy(strategy: dev.rubentxu.pipeline.logger.LogConfigurationStrategy) {}
            }
            override fun getLogger(name: String) = getLogger()
            override fun setCorrelationId(correlationId: String) {}
            override fun getCorrelationId(): String? = "mock-correlation-123"
            override fun logStructured(level: LogLevel, message: String, data: Map<String, Any>) {
                logs.add("STRUCTURED[$level]: $message - $data")
            }
            override fun addContext(key: String, value: Any) {}
            override fun removeContext(key: String) {}
            override fun getContext(): Map<String, Any> = mapOf("correlationId" to "mock-correlation-123")
            override fun clearContext() {}
            override fun createScope(name: String) = this
            fun generateCorrelationId() = "mock-correlation-123"
        }
        
        // When: We create enhanced context with test doubles
        val testContext = UnifiedContextFactory.createForTesting(
            mockParameterManager = mockParameterManager,
            mockLoggerManager = mockLoggerManager,
            mockEnvironmentManager = environmentManager, // Use real for this test
            mockWorkingDirectoryManager = workingDirectoryManager // Use real for this test
        )
        
        val testStepsBlock = EnhancedStepsBlock(testContext)
        
        testStepsBlock.executeWithContext {
            setParam("testKey", "mockValue")
            echo("Testing with mocks")
            val result = getParam<String>("testKey")
            
            // Then: Mock implementations are used
            assertEquals("mockValue", result)
        }
        
        // And: Mock received expected interactions
        assertTrue(mockParameterManager.has("testKey"))
        assertEquals("mockValue", mockParameterManager.get<String>("testKey"))
        
        val mockLogs = mockLoggerManager.getLogger().logs()
        assertTrue(mockLogs.any { it.contains("Testing with mocks") })
    }
    
    @Test
    fun `⏱️ Timeout execution works correctly`() = runBlocking {
        // Given: EnhancedStepsBlock with timeout capability
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We execute with timeout (success case)
        stepsBlock.executeWithContext {
            val result = timeout(1000) {
                sleep(50) // Short operation within timeout
                setParam("timeoutTest", "success")
                "operation-completed"
            }
            
            // Then: Operation completes successfully
            assertEquals("operation-completed", result)
            assertEquals("success", getParam<String>("timeoutTest"))
        }
        
        // And: Timeout logging works
        val logs = loggerManager.getLogger().logs()
        assertTrue(logs.any { it.contains("Starting timeout execution") })
    }
    
    @Test
    fun `🔀 Conditional execution works correctly`() = runBlocking {
        // Given: EnhancedStepsBlock with conditional execution
        val stepsBlock = EnhancedStepsBlockFactory.create(serviceLocator)
        
        // When: We use conditional execution through whenCondition
        stepsBlock.executeWithContext {
            // Test true condition
            val result1 = whenCondition(true, {
                setParam("conditionTest", "true-branch")
                "true-executed"
            })
            
            // Test false condition with else
            val result2 = whenCondition(false, {
                "should-not-execute"
            }, {
                setParam("elseTest", "else-branch")  
                "else-executed"
            })
            
            // Then: Conditions execute correctly
            assertEquals("true-executed", result1)
            assertEquals("else-executed", result2)
            assertEquals("true-branch", getParam<String>("conditionTest"))
            assertEquals("else-branch", getParam<String>("elseTest"))
        }
    }
    
    // === Helper Methods ===
    
    /**
     * Simulates an enhanced @Step function using thread-local context
     */
    private fun simulateEnhancedStepFunction(): String {
        val context = LocalUnifiedPipelineContext.current
        context.parameters.set("enhancedStepExecution", "completed")
        context.logger.addContext("enhancedStepFunction", "simulated")
        return "enhanced-step-executed"
    }
}