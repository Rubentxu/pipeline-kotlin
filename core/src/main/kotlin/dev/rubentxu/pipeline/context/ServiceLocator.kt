package dev.rubentxu.pipeline.context

import kotlin.reflect.KClass
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier

/**
 * Service Locator interface for pipeline components.
 * 
 * Provides a clean abstraction over dependency injection that can evolve
 * independently of the underlying DI framework (currently Koin).
 */
interface IServiceLocator {
    
    /**
     * Gets a component by type
     */
    fun <T : Any> get(
        clazz: Class<T>,
        qualifier: Qualifier? = null,
        parameters: ParametersDefinition? = null
    ): T

    /**
     * Gets a component by type, or null if not found
     */
    fun <T : Any> getOrNull(
        clazz: Class<T>,
        qualifier: Qualifier? = null,
        parameters: ParametersDefinition? = null
    ): T?

    /**
     * Lazy injection - component is resolved on first access
     */
    fun <T : Any> lazy(
        clazz: Class<T>,
        qualifier: Qualifier? = null,
        parameters: ParametersDefinition? = null
    ): Lazy<T>
}

/**
 * Koin-based implementation of Service Locator.
 * 
 * This implementation delegates to Koin but provides a stable API that can
 * evolve independently. Future versions could add:
 * - Component lifecycle management
 * - Security policies for component access
 * - Performance monitoring and metrics
 * - Custom scopes for pipeline execution
 */
class KoinServiceLocator(private val koin: Koin) : IServiceLocator {

    override fun <T : Any> get(
        clazz: Class<T>,
        qualifier: Qualifier?,
        parameters: ParametersDefinition?
    ): T = koin.get(clazz.kotlin, qualifier, parameters)

    override fun <T : Any> getOrNull(
        clazz: Class<T>,
        qualifier: Qualifier?,
        parameters: ParametersDefinition?
    ): T? = koin.getOrNull(clazz.kotlin, qualifier, parameters)

    override fun <T : Any> lazy(
        clazz: Class<T>,
        qualifier: Qualifier?,
        parameters: ParametersDefinition?
    ): Lazy<T> = kotlin.lazy { koin.get(clazz.kotlin, qualifier, parameters) }
}

/**
 * Component that can be injected via the Service Locator.
 * 
 * Provides convenient access to the service locator and common injection patterns.
 * Components implementing this interface get automatic access to dependency injection.
 */
interface ServiceLocatorAware {
    val serviceLocator: IServiceLocator
}

/**
 * Koin-aware component implementation.
 * 
 * Provides automatic dependency injection for pipeline components using Koin.
 * This is a bridge between our Service Locator abstraction and Koin's component system.
 */
abstract class KoinAwareComponent : ServiceLocatorAware, KoinComponent {
    
    override val serviceLocator: IServiceLocator by inject()
    
    /**
     * Convenient delegate for lazy injection
     */
    protected inline fun <reified T : Any> service(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): Lazy<T> = inject(qualifier)
}

/**
 * Extension functions for convenient reified access to ServiceLocator
 */
inline fun <reified T : Any> IServiceLocator.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
): T = get(T::class.java, qualifier, parameters)

inline fun <reified T : Any> IServiceLocator.getOrNull(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
): T? = getOrNull(T::class.java, qualifier, parameters)

inline fun <reified T : Any> IServiceLocator.lazy(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
): Lazy<T> = lazy(T::class.java, qualifier, parameters)

/**
 * Robust service locator implementation with hierarchical support
 */
class ServiceLocator(private val parent: IServiceLocator? = null) : IServiceLocator {
    private val registry = mutableMapOf<Class<*>, Any>()

    /**
     * Registers a service instance by interface type
     */
    fun <T : Any> register(interfaceClass: Class<T>, service: T) {
        registry[interfaceClass] = service
    }

    /**
     * Convenience method for registering with reified type
     */
    inline fun <reified T : Any> register(service: T) {
        register(T::class.java, service)
    }

    /**
     * Gets a service instance
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>, qualifier: Qualifier?, parameters: ParametersDefinition?): T {
        return registry[clazz] as? T
            ?: parent?.get(clazz, qualifier, parameters)
            ?: throw IllegalStateException("No service found for ${clazz.simpleName}")
    }

    /**
     * Gets a service instance, or null if not found
     */
    override fun <T : Any> getOrNull(clazz: Class<T>, qualifier: Qualifier?, parameters: ParametersDefinition?): T? {
        return registry[clazz] as? T
            ?: parent?.getOrNull(clazz, qualifier, parameters)
    }

    /**
     * Lazy injection - component is resolved on first access
     */
    override fun <T : Any> lazy(clazz: Class<T>, qualifier: Qualifier?, parameters: ParametersDefinition?): Lazy<T> {
        return kotlin.lazy { get(clazz, qualifier, parameters) }
    }

    /**
     * Creates a child service locator
     */
    fun createChild(): ServiceLocator = ServiceLocator(this)
}