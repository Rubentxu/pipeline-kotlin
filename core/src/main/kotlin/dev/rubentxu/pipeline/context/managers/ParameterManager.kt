package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.IParameterManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap


/**
 * Default implementation of ParameterManager using concurrent data structures
 * and Kotlin Flow for reactivity.
 */
class ParameterManager(
    private val initialParams: Map<String, Any> = emptyMap(),
    ) : IParameterManager {
    
    // Thread-safe storage
    private val parameters = ConcurrentHashMap<String, Any>(initialParams)


    override fun set(key: String, value: Any) {
        require(key.isNotBlank()) { "Parameter key cannot be blank" }
        
        parameters[key] = value
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String): T? {
        return parameters[key] as? T
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T {
        return parameters[key] as? T ?: defaultValue
    }
    
    override fun has(key: String): Boolean {
        return parameters.containsKey(key)
    }
    
    override fun getAll(): Map<String, Any> {
        return parameters.toMap()
    }

}
