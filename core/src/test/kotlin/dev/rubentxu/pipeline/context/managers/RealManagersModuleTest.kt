package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import dev.rubentxu.pipeline.context.modules.realManagersModule
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * Test to verify that all managers can be correctly resolved from the DI container
 */
class RealManagersModuleTest : KoinTest {
    
    @Test
    fun `should resolve all managers without NoBeanDefFoundException`() {
        try {
            startKoin {
                modules(realManagersModule)
            }
            
            // Test that all manager interfaces can be resolved
            assertDoesNotThrow("IParameterManager should be resolvable") {
                val parameterManager = get<IParameterManager>()
                assertNotNull(parameterManager)
            }
            
            assertDoesNotThrow("IEnvironmentManager should be resolvable") {
                val environmentManager = get<IEnvironmentManager>()
                assertNotNull(environmentManager)
            }
            
            assertDoesNotThrow("ILoggerManager should be resolvable") {
                val loggerManager = get<ILoggerManager>()
                assertNotNull(loggerManager)
            }
            
            assertDoesNotThrow("IWorkspaceManager should be resolvable") {
                val workspaceManager = get<IWorkspaceManager>()
                assertNotNull(workspaceManager)
            }
            
        } finally {
            stopKoin()
        }
    }
    
    @Test
    fun `should create functional logger from logger manager`() {
        try {
            startKoin {
                modules(realManagersModule)
            }
            
            val loggerManager = get<ILoggerManager>()
            
            assertDoesNotThrow("Logger creation should work") {
                runTest {
                    val logger = loggerManager.getLogger("test")
                    assertNotNull(logger)
                    
                    // Test basic logging operations
                    logger.info("Test info message")
                    logger.warn("Test warn message")
                    logger.debug("Test debug message")
                    logger.system("Test system message")
                }
            }
            
        } finally {
            stopKoin()
        }
    }
    
    @Test
    fun `should create functional workspace manager`() {
        try {
            startKoin {
                modules(realManagersModule)
            }
            
            val workspaceManager = get<IWorkspaceManager>()
            
            assertDoesNotThrow("Workspace operations should work") {
                runTest {
                    val currentWorkspace = workspaceManager.current
                    assertNotNull(currentWorkspace)
                    
                    // Test basic workspace operations
                    val exists = currentWorkspace.exists(".")
                    assertNotNull(exists)
                }
            }
            
        } finally {
            stopKoin()
        }
    }
}