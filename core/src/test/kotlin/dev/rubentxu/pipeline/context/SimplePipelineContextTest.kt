package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.context.managers.*
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.model.LogLevel
import dev.rubentxu.pipeline.logger.LogConfigurationStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test for SimplePipelineContext with real manager interfaces
 */
class SimplePipelineContextTest {
    
    // Mock implementations for testing
    class MockParameterManager : IParameterManager {
        private val parameters = mutableMapOf<String, Any>()
        private val parameterFlow = MutableStateFlow(parameters.toMap())
        
        override fun set(key: String, value: Any) {
            parameters[key] = value
            parameterFlow.value = parameters.toMap()
        }
        
        override fun <T> get(key: String): T? = parameters[key] as? T
        
        override fun <T> get(key: String, defaultValue: T): T = parameters[key] as? T ?: defaultValue
        
        override fun has(key: String): Boolean = parameters.containsKey(key)
        
        override fun remove(key: String): Boolean = parameters.remove(key) != null
        
        override fun getAll(): Map<String, Any> = parameters.toMap()
        
        override fun clear() = parameters.clear()
        
        override fun observeParameter(key: String): StateFlow<Any?> = MutableStateFlow(parameters[key])
        
        override fun observeAll(): StateFlow<Map<String, Any>> = parameterFlow
        
        override fun createScope(name: String): IParameterManager = MockParameterManager() // Simple mock scope
    }
    
    class MockEnvironmentManager : IEnvironmentManager {
        private val environment = mutableMapOf<String, String>()
        
        override fun get(name: String): String? = environment[name]
        
        override fun get(name: String, defaultValue: String): String = environment[name] ?: defaultValue
        
        override fun set(name: String, value: String) { environment[name] = value }
        
        override fun getSecret(name: String): String? = environment["SECRET_$name"]
        
        override fun setSecret(name: String, value: String) { environment["SECRET_$name"] = value }
        
        override fun has(name: String): Boolean = environment.containsKey(name)
        
        override fun getAll(): Map<String, String> = environment.toMap()
        
        override fun remove(name: String): Boolean = environment.remove(name) != null
        
        override fun clear() = environment.clear()
        
        override fun createScope(name: String): IEnvironmentManager = MockEnvironmentManager()
        
        override fun resolveTemplate(template: String): String = template
    }
    
    class MockLoggerManager : ILoggerManager {
        private var correlationId: String? = null
        private val context = mutableMapOf<String, Any>()
        
        override fun getLogger(): ILogger = MockPipelineLogger()
        
        override fun getLogger(name: String): ILogger = MockPipelineLogger()
        
        override fun setCorrelationId(correlationId: String) { this.correlationId = correlationId }
        
        override fun getCorrelationId(): String? = correlationId
        
        override fun logStructured(level: LogLevel, message: String, data: Map<String, Any>) {
            // Mock implementation
        }
        
        override fun addContext(key: String, value: Any) { context[key] = value }
        
        override fun removeContext(key: String) { context.remove(key) }
        
        override fun getContext(): Map<String, Any> = context.toMap()
        
        override fun clearContext() = context.clear()
        
        override fun createScope(name: String): ILoggerManager = MockLoggerManager()
    }
    
    class MockWorkingDirectoryManager : IWorkingDirectoryManager {
        private var currentDir: Path = Paths.get(System.getProperty("user.dir"))
        
        override fun getCurrentDirectory(): Path = currentDir
        
        override fun setWorkingDirectory(path: Path) { currentDir = path }
        
        override fun setWorkingDirectory(path: String) { currentDir = Paths.get(path) }
        
        override fun resolve(relativePath: String): Path = currentDir.resolve(relativePath)
        
        override fun resolve(relativePath: Path): Path = currentDir.resolve(relativePath)
        
        override fun isPathAllowed(path: Path): Boolean = true
        
        override fun exists(path: String): Boolean = Paths.get(path).toFile().exists()
        
        override fun exists(path: Path): Boolean = path.toFile().exists()
        
        override fun createDirectory(path: String): Path {
            val dirPath = Paths.get(path)
            dirPath.toFile().mkdirs()
            return dirPath
        }
        
        override fun createDirectory(path: Path): Path {
            path.toFile().mkdirs()
            return path
        }
        
        override fun getTempDirectory(): Path = Paths.get(System.getProperty("java.io.tmpdir"))
        
        override fun cleanup() {}
        
        override fun createScope(name: String, basePath: Path): IWorkingDirectoryManager = MockWorkingDirectoryManager()
    }
    
    class MockPipelineLogger : ILogger {
        private val logMessages = mutableListOf<String>()
        
        override fun info(message: String) { logMessages.add("INFO: $message") }
        override fun warn(message: String) { logMessages.add("WARN: $message") }
        override fun error(message: String) { logMessages.add("ERROR: $message") }
        override fun debug(message: String) { logMessages.add("DEBUG: $message") }
        override fun system(message: String) { logMessages.add("SYSTEM: $message") }
        override fun logs(): List<String> = logMessages.toList()
        override fun changeLogLevel(logLevel: LogLevel) {}
        override fun changeConfigurationStrategy(logConfigurationStrategy: LogConfigurationStrategy) {}
    }
    
    class TestSimplePipelineContext(
        override val serviceLocator: IServiceLocator
    ) : SimplePipelineContext {
        override fun getLegacyLogger(): ILogger = MockPipelineLogger()
    }
    
    private val testModule = module {
        single<IParameterManager> { MockParameterManager() }
        single<IEnvironmentManager> { MockEnvironmentManager() }
        single<ILoggerManager> { MockLoggerManager() }
        single<IWorkingDirectoryManager> { MockWorkingDirectoryManager() }
        single<IServiceLocator> { KoinServiceLocator(get()) }
    }
    
    @BeforeEach
    fun setup() {
        startKoin {
            modules(testModule)
        }
    }
    
    @AfterEach 
    fun cleanup() {
        stopKoin()
    }
    
    @Test
    fun `SimplePipelineContext can access parameter manager`() {
        // Given: A simple pipeline context
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = TestSimplePipelineContext(serviceLocator)
        
        // When: We access the parameter manager
        val paramManager = context.parameters
        
        // Then: Manager should work correctly
        assertNotNull(paramManager)
        
        // Test parameter operations
        paramManager.set("testKey", "testValue")
        assertEquals("testValue", paramManager.get<String>("testKey"))
        assertTrue(paramManager.has("testKey"))
        
        // Test convenience methods
        context.setParam("convenienceKey", 42)
        assertEquals(42, context.getParam<Int>("convenienceKey"))
        assertEquals(100, context.getParam("nonExistentKey", 100))
    }
    
    @Test
    fun `SimplePipelineContext can access environment manager`() {
        // Given: A simple pipeline context
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = TestSimplePipelineContext(serviceLocator)
        
        // When: We access the environment manager
        val envManager = context.environment
        
        // Then: Manager should work correctly
        assertNotNull(envManager)
        
        // Test environment operations
        envManager.set("TEST_VAR", "test_value")
        assertEquals("test_value", envManager.get("TEST_VAR"))
        
        // Test convenience methods
        assertEquals("test_value", context.getEnvVar("TEST_VAR"))
        assertEquals("default", context.getEnvVar("NON_EXISTENT", "default"))
        
        // Test secrets
        envManager.setSecret("API_KEY", "secret_value")
        assertEquals("secret_value", context.getSecret("API_KEY"))
    }
    
    @Test
    fun `SimplePipelineContext can access logger manager`() {
        // Given: A simple pipeline context
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = TestSimplePipelineContext(serviceLocator)
        
        // When: We access the logger manager
        val loggerManager = context.logger
        
        // Then: Manager should work correctly
        assertNotNull(loggerManager)
        
        val logger = loggerManager.getLogger()
        assertNotNull(logger)
        
        // Test correlation ID
        loggerManager.setCorrelationId("test-correlation-123")
        assertEquals("test-correlation-123", loggerManager.getCorrelationId())
        
        // Test legacy logger
        val legacyLogger = context.getLegacyLogger()
        assertNotNull(legacyLogger)
    }
    
    @Test
    fun `SimplePipelineContext can access working directory manager`() {
        // Given: A simple pipeline context
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = TestSimplePipelineContext(serviceLocator)
        
        // When: We access the working directory manager
        val wdManager = context.workingDirectory
        
        // Then: Manager should work correctly
        assertNotNull(wdManager)
        
        val currentDir = wdManager.getCurrentDirectory()
        assertNotNull(currentDir)
        
        // Test path resolution
        val resolvedPath = wdManager.resolve("test.txt")
        assertNotNull(resolvedPath)
        assertTrue(resolvedPath.toString().endsWith("test.txt"))
    }
    
    @Test
    fun `LocalSimplePipelineContext works correctly`() {
        // Given: A simple pipeline context
        val koin = GlobalContext.get()
        val serviceLocator = KoinServiceLocator(koin)
        val context = TestSimplePipelineContext(serviceLocator)
        
        // When: We use LocalSimplePipelineContext
        LocalSimplePipelineContext.runWith(context) {
            val currentContext = LocalSimplePipelineContext.current
            
            // Then: Context should be accessible
            assertNotNull(currentContext)
            assertTrue(currentContext === context)
            
            // And managers should work
            currentContext.setParam("localTest", "localValue")
            assertEquals("localValue", currentContext.getParam<String>("localTest"))
        }
    }
}