package dev.rubentxu.pipeline.steps.plugin.context

import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Service Locator pattern for stable context injection
 * 
 * This provides a stable alternative to Context Parameters for @Step functions.
 * Uses CoroutineContext for thread-safe, suspend-compatible service resolution.
 * 
 * Key advantages:
 * - Stable across Kotlin versions (no experimental features)
 * - CoroutineContext-based for suspend function compatibility  
 * - Type-safe service resolution
 * - Scoped service lifecycles
 * - Performance monitoring and logging
 */
object ServiceLocator {

    /**
     * Service registry for type-safe service resolution
     */
    private val serviceRegistry = mutableMapOf<ServiceKey<*>, Any>()
    
    /**
     * CoroutineContext key for pipeline services
     */
    object PipelineServicesKey : CoroutineContext.Key<PipelineServices>
    
    /**
     * CoroutineContext element containing pipeline services
     */
    class PipelineServices(
        private val services: Map<ServiceKey<*>, Any> = emptyMap()
    ) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = PipelineServicesKey
        
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> getService(serviceKey: ServiceKey<T>): T? {
            return services[serviceKey] as? T
        }
        
        fun <T : Any> withService(serviceKey: ServiceKey<T>, service: T): PipelineServices {
            return PipelineServices(services + (serviceKey to service))
        }
    }
    
    /**
     * Type-safe service key
     */
    data class ServiceKey<T : Any>(
        val name: String,
        val type: Class<T>
    ) {
        companion object {
            inline fun <reified T : Any> create(name: String): ServiceKey<T> {
                return ServiceKey(name, T::class.java)
            }
        }
    }
    
    /**
     * Register a service in the global registry
     */
    fun <T : Any> registerService(key: ServiceKey<T>, service: T) {
        StructuredLogger.logPerformanceMetric(
            operation = "service_registration",
            durationMs = 0,
            metadata = mapOf(
                "service_key" to key.name,
                "service_type" to key.type.simpleName,
                "registration_method" to "global_registry"
            )
        )
        serviceRegistry[key] = service
    }
    
    /**
     * Get service from CoroutineContext (preferred) or global registry (fallback)
     */
    suspend fun <T : Any> getService(key: ServiceKey<T>): T? {
        return try {
            // Try CoroutineContext first (most stable)
            val pipelineServices = currentCoroutineContext()[PipelineServicesKey]
            val contextService = pipelineServices?.getService(key)
            
            if (contextService != null) {
                StructuredLogger.logPerformanceMetric(
                    operation = "service_resolution",
                    durationMs = 0,
                    metadata = mapOf(
                        "service_key" to key.name,
                        "resolution_method" to "coroutine_context",
                        "success" to true
                    )
                )
                return contextService
            }
            
            // Fallback to global registry
            @Suppress("UNCHECKED_CAST")
            val globalService = serviceRegistry[key] as? T
            
            StructuredLogger.logPerformanceMetric(
                operation = "service_resolution",
                durationMs = 0,
                metadata = mapOf(
                    "service_key" to key.name,
                    "resolution_method" to "global_registry",
                    "success" to (globalService != null)
                )
            )
            
            globalService
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "service_resolution",
                error = e,
                context = mapOf(
                    "service_key" to key.name,
                    "service_type" to key.type.simpleName
                )
            )
            null
        }
    }
    
    /**
     * Execute block with additional service in CoroutineContext
     */
    suspend fun <T : Any, R> withService(
        key: ServiceKey<T>, 
        service: T, 
        block: suspend CoroutineScope.() -> R
    ): R {
        val currentServices = currentCoroutineContext()[PipelineServicesKey] 
            ?: PipelineServices()
        val newServices = currentServices.withService(key, service)
        
        StructuredLogger.logPerformanceMetric(
            operation = "scoped_service_execution",
            durationMs = 0,
            metadata = mapOf(
                "service_key" to key.name,
                "service_type" to key.type.simpleName,
                "execution_method" to "coroutine_context_scoped"
            )
        )
        
        return withContext(newServices, block)
    }
    
    /**
     * Common service keys for @Step functions
     */
    object CommonServices {
        val PIPELINE_CONTEXT = ServiceKey.create<Any>("pipeline_context")
        val LOGGER = ServiceKey.create<Any>("logger") 
        val FILE_SYSTEM = ServiceKey.create<Any>("file_system")
        val SHELL_EXECUTOR = ServiceKey.create<Any>("shell_executor")
        val HTTP_CLIENT = ServiceKey.create<Any>("http_client")
        val SLACK_CLIENT = ServiceKey.create<Any>("slack_client")
        val DOCKER_CLIENT = ServiceKey.create<Any>("docker_client")
    }
    
    /**
     * Clear all registered services (for testing)
     */
    fun clearRegistry() {
        StructuredLogger.logPerformanceMetric(
            operation = "service_registry_clear",
            durationMs = 0,
            metadata = mapOf(
                "cleared_services" to serviceRegistry.size,
                "method" to "testing_cleanup"
            )
        )
        serviceRegistry.clear()
    }
    
    /**
     * Get all registered service keys (for debugging)
     */
    fun getRegisteredServices(): Set<ServiceKey<*>> {
        return serviceRegistry.keys.toSet()
    }
}

/**
 * Extension function for easy service access in @Step functions
 */
suspend inline fun <reified T : Any> getStepService(name: String): T? {
    val key = ServiceLocator.ServiceKey.create<T>(name)
    return ServiceLocator.getService(key)
}

/**
 * Extension function for scoped service execution
 */
suspend inline fun <reified T : Any, R> withStepService(
    name: String,
    service: T,
    noinline block: suspend CoroutineScope.() -> R
): R {
    val key = ServiceLocator.ServiceKey.create<T>(name)
    return ServiceLocator.withService(key, service, block)
}