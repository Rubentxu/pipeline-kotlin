package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.context.modules.realManagersModule
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.IEnvironmentManager
import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import java.nio.file.Paths
import java.nio.file.Path

/**
 * Pipeline Service Initializer
 * 
 * Responsible for setting up the complete dependency injection system
 * for the pipeline execution environment. This includes:
 * 
 * - Service registration with proper implementations
 * - ServiceLocator creation with all required managers
 * - Koin DI framework initialization
 * - Custom working directory configuration
 * 
 * Usage:
 * ```kotlin
 * val initializer = PipelineServiceInitializer()
 * val koinServiceLocator = initializer.initialize()
 * 
 * // Services are now available via Koin service locator
 * val logger = koinServiceLocator.get<ILoggerManager>()
 * ```
 */
class PipelineServiceInitializer {
    
    /**
     * Initializes all pipeline services and returns a configured KoinServiceLocator.
     * 
     * This method:
     * 1. Starts Koin DI framework if not already started
     * 2. Registers all manager implementations from realManagersModule
     * 3. Returns the powerful KoinServiceLocator directly
     * 4. Validates that all critical services are available
     * 
     * @param workingDirectory Optional custom working directory for workspace manager
     * @return KoinServiceLocator with all pipeline services registered
     * @throws IllegalStateException if service initialization fails
     */
    fun initialize(workingDirectory: String? = null): KoinServiceLocator {
        try {
            // Initialize Koin if not already started
            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(realManagersModule)
                }
            }
            
            // Create Koin-backed service locator - the most powerful implementation
            val koinServiceLocator = KoinServiceLocator(GlobalContext.get())
            
            // Handle custom working directory if needed
            if (workingDirectory != null) {
                setupCustomWorkspaceManager(koinServiceLocator, workingDirectory)
            }
            
            // Validate that all critical services are available
            validateServices(koinServiceLocator)
            
            return koinServiceLocator
            
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize pipeline services: ${e.message}", e)
        }
    }
    
    /**
     * Sets up a custom workspace manager with specific working directory
     */
    private fun setupCustomWorkspaceManager(
        koinServiceLocator: KoinServiceLocator,
        workingDirectory: String
    ) {
        val loggerManager = koinServiceLocator.get(ILoggerManager::class.java)
        val customWorkspaceManager = createCustomWorkspaceManager(workingDirectory, loggerManager)
        
        // Override the workspace manager in Koin's registry
        // Note: This is a simplified approach. In a production system,
        // we might want to create a new Koin module with the custom workspace manager
        org.koin.core.context.GlobalContext.get().declare(customWorkspaceManager)
    }
    
    /**
     * Creates a custom workspace manager with specific working directory
     */
    private fun createCustomWorkspaceManager(
        workingDirectory: String,
        loggerManager: ILoggerManager
    ): IWorkspaceManager {
        // Import the DefaultWorkspaceManager here to avoid circular dependencies
        val workingPath = Paths.get(workingDirectory).toAbsolutePath().normalize()
        
        return dev.rubentxu.pipeline.context.managers.DefaultWorkspaceManager(
            initialDirectory = workingPath,
            logger = kotlinx.coroutines.runBlocking { loggerManager.getLogger("WorkspaceManager") }
        )
    }
    
    /**
     * Validates that all critical services are available and functional
     */
    private fun validateServices(koinServiceLocator: KoinServiceLocator) {
        val requiredServices = listOf(
            IParameterManager::class,
            IEnvironmentManager::class,
            ILoggerManager::class,
            IWorkspaceManager::class
        )
        
        requiredServices.forEach { serviceClass ->
            try {
                val service = koinServiceLocator.get(serviceClass.java)
                requireNotNull(service) { "Service ${serviceClass.simpleName} is null" }
            } catch (e: Exception) {
                throw IllegalStateException("Required service ${serviceClass.simpleName} is not available", e)
            }
        }
    }
    
    /**
     * Stops the Koin context and cleans up resources.
     * Primarily used for testing to ensure clean state between tests.
     */
    fun shutdown() {
        try {
            org.koin.core.context.stopKoin()
        } catch (_: Exception) {
            // Already stopped or not initialized
        }
    }
    
    companion object {
        /**
         * Quick initialization for simple use cases
         */
        fun quickStart(): KoinServiceLocator {
            return PipelineServiceInitializer().initialize()
        }
        
        /**
         * Initialize with custom working directory
         */
        fun startWithWorkingDirectory(workingDirectory: String): KoinServiceLocator {
            return PipelineServiceInitializer().initialize(workingDirectory)
        }
    }
}