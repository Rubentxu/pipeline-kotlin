package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.context.managers.*
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.context.modules.*
import dev.rubentxu.pipeline.logger.model.LogLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.context.GlobalContext
import org.koin.test.KoinTest
import org.koin.test.inject
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Test for Real Manager implementations with Koin integration
 */
class RealManagersTest : KoinTest {
    
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
    fun `Real Parameter Manager works correctly`() = runBlocking {
        // Given: Real parameter manager
        assertNotNull(parameterManager)
        assertTrue(parameterManager is KoinParameterManager)
        
        // When: We set parameters
        parameterManager.set("testKey", "testValue")
        parameterManager.set("numberKey", 42)
        parameterManager.set("booleanKey", true)
        
        // Then: Parameters should be retrievable
        assertEquals("testValue", parameterManager.get<String>("testKey"))
        assertEquals(42, parameterManager.get<Int>("numberKey"))
        assertEquals(true, parameterManager.get<Boolean>("booleanKey"))
        
        // And: Default values work
        assertEquals("default", parameterManager.get("nonExistent", "default"))
        
        // And: Has/remove works
        assertTrue(parameterManager.has("testKey"))
        assertTrue(parameterManager.remove("testKey"))
        assertFalse(parameterManager.has("testKey"))
        
        // And: Reactive access works
        val allFlow = parameterManager.observeAll()
        val currentState = allFlow.value
        assertTrue(currentState.containsKey("numberKey"))
        assertTrue(currentState.containsKey("booleanKey"))
    }
    
    @Test
    fun `Real Environment Manager works correctly`() {
        // Given: Real environment manager
        assertNotNull(environmentManager)
        assertTrue(environmentManager is KoinEnvironmentManager)
        
        // When: We set environment variables
        environmentManager.set("TEST_VAR", "test_value")
        environmentManager.setSecret("SECRET_KEY", "secret_value")
        
        // Then: Environment variables should be accessible
        assertEquals("test_value", environmentManager.get("TEST_VAR"))
        assertEquals("secret_value", environmentManager.getSecret("SECRET_KEY"))
        
        // And: System environment should be inherited
        assertNotNull(environmentManager.get("PATH")) // System PATH should be available
        
        // And: Default values work
        assertEquals("default", environmentManager.get("NON_EXISTENT", "default"))
        
        // And: Template resolution works
        environmentManager.set("NAME", "Pipeline")
        val template = "Hello \${NAME}!"
        assertEquals("Hello Pipeline!", environmentManager.resolveTemplate(template))
        
        // And: Scoped environment works
        val scopedEnv = environmentManager.createScope("test-scope")
        scopedEnv.set("SCOPE_VAR", "scope_value")
        assertEquals("scope_value", scopedEnv.get("SCOPE_VAR"))
        assertEquals("test_value", scopedEnv.get("TEST_VAR")) // Inherited from parent
    }
    
    @Test
    fun `Real Logger Manager works correctly`() {
        // Given: Real logger manager
        assertNotNull(loggerManager)
        assertTrue(loggerManager is KoinLoggerManager)
        
        // When: We use logging features
        val correlationId = "test-correlation-123"
        loggerManager.setCorrelationId(correlationId)
        loggerManager.addContext("component", "test-component")
        loggerManager.addContext("version", "1.0.0")
        
        // Then: Correlation ID should be set
        assertEquals(correlationId, loggerManager.getCorrelationId())
        
        // And: Context should be stored
        val context = loggerManager.getContext()
        assertEquals("test-component", context["component"])
        assertEquals("1.0.0", context["version"])
        assertEquals(correlationId, context["correlationId"])
        
        // And: Structured logging should work
        loggerManager.logStructured(
            LogLevel.INFO, 
            "Test structured log",
            mapOf("action" to "test", "result" to "success")
        )
        
        // And: Logger creation should work
        val logger = loggerManager.getLogger("test-logger")
        assertNotNull(logger)
        logger.info("Test message")
        assertTrue(logger.logs().any { it.contains("Test message") })
        
        // And: Scoped logger should work
        val scopedLogger = loggerManager.createScope("test-scope")
        assertNotNull(scopedLogger)
        assertTrue(scopedLogger.getContext().containsKey("scope"))
    }
    
    @Test
    fun `Real Working Directory Manager works correctly`() {
        // Given: Real working directory manager  
        assertNotNull(workingDirectoryManager)
        assertTrue(workingDirectoryManager is KoinWorkingDirectoryManager)
        
        // When: We use directory operations
        val currentDir = workingDirectoryManager.getCurrentDirectory()
        assertNotNull(currentDir)
        
        // Then: Path resolution should work
        val resolvedPath = workingDirectoryManager.resolve("test.txt")
        assertTrue(resolvedPath.toString().endsWith("test.txt"))
        
        // And: Temp directory creation should work
        val tempDir = workingDirectoryManager.getTempDirectory()
        assertNotNull(tempDir)
        assertTrue(Files.exists(tempDir))
        
        // And: Directory existence check should work
        assertTrue(workingDirectoryManager.exists(currentDir.toString()))
        assertFalse(workingDirectoryManager.exists("/nonexistent/path"))
        
        // And: Scoped directory manager should work
        val scopedManager = workingDirectoryManager.createScope(
            "test-scope", 
            currentDir.resolve("test-scope")
        )
        assertNotNull(scopedManager)
        
        // Cleanup temp directory
        workingDirectoryManager.cleanup()
    }
    
    @Test
    fun `Service Locator integrates with Real Managers`() {
        // Given: Service locator with real managers
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        
        // When: We access managers through service locator
        val paramManager = serviceLocator.get<IParameterManager>()
        val envManager = serviceLocator.get<IEnvironmentManager>()
        val logManager = serviceLocator.get<ILoggerManager>()
        val wdManager = serviceLocator.get<IWorkingDirectoryManager>()
        
        // Then: All managers should be real implementations
        assertTrue(paramManager is KoinParameterManager)
        assertTrue(envManager is KoinEnvironmentManager)
        assertTrue(logManager is KoinLoggerManager)
        assertTrue(wdManager is KoinWorkingDirectoryManager)
        
        // And: They should be singletons
        val paramManager2 = serviceLocator.get<IParameterManager>()
        assertTrue(paramManager === paramManager2)
    }
    
    @Test
    fun `SimplePipelineContext works with Real Managers`() {
        // Given: SimplePipelineContext with real managers
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = object : SimplePipelineContext {
            override val serviceLocator: IServiceLocator = serviceLocator
            override fun getLegacyLogger() = logger.getLogger()
        }
        
        // When: We use context convenience methods
        context.setParam("contextParam", "contextValue")
        context.environment.set("CONTEXT_VAR", "context_env_value")
        context.logger.addContext("contextTest", "active")
        
        // Then: All operations should work
        assertEquals("contextValue", context.getParam<String>("contextParam"))
        assertEquals("context_env_value", context.getEnvVar("CONTEXT_VAR"))
        assertTrue(context.logger.getContext().containsKey("contextTest"))
        
        // And: Working directory operations should work
        val currentDir = context.workingDirectory.getCurrentDirectory()
        assertNotNull(currentDir)
        
        // And: Legacy logger should work
        val legacyLogger = context.getLegacyLogger()
        assertNotNull(legacyLogger)
        legacyLogger.info("Legacy logger test")
    }
}