package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of DslEngineRegistry that manages multiple DSL engines
 * with thread-safe registration and lookup capabilities.
 */
class DefaultDslEngineRegistry(
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
) : DslEngineRegistry {
    
    private val engines = ConcurrentHashMap<String, DslEngine<*>>()
    private val extensionMapping = ConcurrentHashMap<String, String>() // extension -> engineId
    private val capabilityMapping = ConcurrentHashMap<DslCapability, MutableSet<String>>() // capability -> engineIds
    private val mutex = Mutex()
    
    override fun <TResult : Any> registerEngine(engine: DslEngine<TResult>) {
        require(engine.engineId.isNotBlank()) { "Engine ID cannot be blank" }
        require(engine.supportedExtensions.isNotEmpty()) { "Engine must support at least one file extension" }
        
        runCatching {
            // Validate engine extensions format
            engine.supportedExtensions.forEach { ext ->
                require(ext.isNotBlank()) { "File extension cannot be blank" }
                require(!ext.contains(".") || ext.startsWith(".")) { 
                    "File extension must not contain dots unless it starts with one: $ext" 
                }
            }
            
            val existingEngine = engines[engine.engineId]
            if (existingEngine != null) {
                logger.warn("Replacing existing DSL engine: ${engine.engineId}")
            }
            
            // Register the engine
            engines[engine.engineId] = engine
            
            // Register extension mappings
            engine.supportedExtensions.forEach { extension ->
                val normalizedExt = normalizeExtension(extension)
                val existingEngineId = extensionMapping[normalizedExt]
                
                if (existingEngineId != null && existingEngineId != engine.engineId) {
                    logger.warn("Extension $normalizedExt was mapped to engine $existingEngineId, now mapping to ${engine.engineId}")
                }
                
                extensionMapping[normalizedExt] = engine.engineId
            }
            
            // Register capability mappings
            val engineInfo = engine.getEngineInfo()
            engineInfo.capabilities.forEach { capability ->
                capabilityMapping.computeIfAbsent(capability) { mutableSetOf() }.add(engine.engineId)
            }
            
            logger.info("Registered DSL engine: ${engine.engineId} v${engine.engineVersion}")
            logger.debug("Engine ${engine.engineId} supports extensions: ${engine.supportedExtensions}")
            logger.debug("Engine ${engine.engineId} capabilities: ${engineInfo.capabilities}")
            
        }.onFailure { exception ->
            logger.error("Failed to register DSL engine: ${engine.engineId} - ${exception.message}")
            throw exception
        }
    }
    
    override fun unregisterEngine(engineId: String) {
        require(engineId.isNotBlank()) { "Engine ID cannot be blank" }
        
        val removedEngine = engines.remove(engineId)
        if (removedEngine == null) {
            logger.warn("Attempted to unregister non-existent DSL engine: $engineId")
            return
        }
        
        // Remove extension mappings
        extensionMapping.entries.removeIf { it.value == engineId }
        
        // Remove capability mappings
        capabilityMapping.values.forEach { engineIds ->
            engineIds.remove(engineId)
        }
        
        // Clean up empty capability sets
        capabilityMapping.entries.removeIf { it.value.isEmpty() }
        
        logger.info("Unregistered DSL engine: $engineId")
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <TResult : Any> getEngine(engineId: String): DslEngine<TResult>? {
        require(engineId.isNotBlank()) { "Engine ID cannot be blank" }
        return engines[engineId] as? DslEngine<TResult>
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <TResult : Any> getEngineForExtension(extension: String): DslEngine<TResult>? {
        require(extension.isNotBlank()) { "File extension cannot be blank" }
        
        val normalizedExt = normalizeExtension(extension)
        val engineId = extensionMapping[normalizedExt] ?: return null
        
        return engines[engineId] as? DslEngine<TResult>
    }
    
    override fun getAllEngines(): List<DslEngine<*>> {
        return engines.values.toList()
    }
    
    override fun getEnginesWithCapability(capability: DslCapability): List<DslEngine<*>> {
        val engineIds = capabilityMapping[capability] ?: return emptyList()
        return engineIds.mapNotNull { engines[it] }
    }
    
    /**
     * Gets engine statistics for monitoring and debugging.
     */
    fun getRegistryStats(): DslEngineRegistryStats {
        val engineStats = engines.values.map { engine ->
            val info = engine.getEngineInfo()
            EngineSummary(
                engineId = engine.engineId,
                engineName = engine.engineName,
                version = engine.engineVersion,
                supportedExtensions = engine.supportedExtensions,
                capabilities = info.capabilities
            )
        }
        
        return DslEngineRegistryStats(
            totalEngines = engines.size,
            supportedExtensions = extensionMapping.keys.toSet(),
            availableCapabilities = capabilityMapping.keys.toSet(),
            engines = engineStats
        )
    }
    
    /**
     * Validates that an engine can handle a specific file.
     */
    fun canHandleFile(file: java.io.File): Boolean {
        val extension = file.extension
        return getEngineForExtension<Any>(extension) != null
    }
    
    /**
     * Gets all engines that can handle a specific file.
     */
    fun getEnginesForFile(file: java.io.File): List<DslEngine<*>> {
        val extension = file.extension
        val engineId = extensionMapping[normalizeExtension(extension)]
        return if (engineId != null) {
            engines[engineId]?.let { listOf(it) } ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    /**
     * Creates a detailed report of the registry state.
     */
    fun generateReport(): String {
        val stats = getRegistryStats()
        
        return buildString {
            appendLine("DSL Engine Registry Report")
            appendLine("==========================")
            appendLine("Total Engines: ${stats.totalEngines}")
            appendLine("Supported Extensions: ${stats.supportedExtensions.joinToString(", ")}")
            appendLine("Available Capabilities: ${stats.availableCapabilities.joinToString(", ")}")
            appendLine()
            
            if (stats.engines.isNotEmpty()) {
                appendLine("Registered Engines:")
                stats.engines.forEach { engine ->
                    appendLine("- ${engine.engineName} (${engine.engineId}) v${engine.version}")
                    appendLine("  Extensions: ${engine.supportedExtensions.joinToString(", ")}")
                    appendLine("  Capabilities: ${engine.capabilities.joinToString(", ")}")
                }
            }
            
            appendLine()
            appendLine("Extension Mappings:")
            extensionMapping.forEach { (ext, engineId) ->
                val engine = engines[engineId]
                appendLine("- $ext -> ${engine?.engineName ?: "Unknown"} ($engineId)")
            }
            
            appendLine()
            appendLine("Capability Mappings:")
            capabilityMapping.forEach { (capability, engineIds) ->
                val engineNames = engineIds.mapNotNull { engines[it]?.engineName }
                appendLine("- $capability: ${engineNames.joinToString(", ")}")
            }
        }
    }
    
    /**
     * Clears all registered engines. Use with caution.
     */
    suspend fun clear() {
        mutex.withLock {
            val engineIds = engines.keys.toList()
            engineIds.forEach { unregisterEngine(it) }
            
            engines.clear()
            extensionMapping.clear()
            capabilityMapping.clear()
            
            logger.info("Cleared all DSL engines from registry")
        }
    }
    
    private fun normalizeExtension(extension: String): String {
        return if (extension.startsWith(".")) {
            extension.lowercase()
        } else {
            ".${extension.lowercase()}"
        }
    }
}

/**
 * Statistics about the DSL engine registry.
 */
data class DslEngineRegistryStats(
    val totalEngines: Int,
    val supportedExtensions: Set<String>,
    val availableCapabilities: Set<DslCapability>,
    val engines: List<EngineSummary>
)

/**
 * Summary information about a registered engine.
 */
data class EngineSummary(
    val engineId: String,
    val engineName: String,
    val version: String,
    val supportedExtensions: Set<String>,
    val capabilities: Set<DslCapability>
)

/**
 * Exception thrown when DSL engine operations fail.
 */
open class DslEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when no suitable DSL engine is found.
 */
class NoSuitableDslEngineException(
    val extension: String? = null,
    val capability: DslCapability? = null,
    message: String
) : DslEngineException(message)

/**
 * Builder for creating DSL engine registry with pre-configured engines.
 */
class DslEngineRegistryBuilder {
    private val engines = mutableListOf<DslEngine<*>>()
    private var logger: IPipelineLogger = PipelineLogger.getLogger()
    
    fun withLogger(logger: IPipelineLogger) = apply {
        this.logger = logger
    }
    
    fun <TResult : Any> addEngine(engine: DslEngine<TResult>) = apply {
        engines.add(engine)
    }
    
    fun build(): DefaultDslEngineRegistry {
        val registry = DefaultDslEngineRegistry(logger)
        
        engines.forEach { engine ->
            @Suppress("UNCHECKED_CAST")
            registry.registerEngine(engine as DslEngine<Any>)
        }
        
        return registry
    }
}

/**
 * DSL for building DSL engine registry
 */
fun dslEngineRegistry(configure: DslEngineRegistryBuilder.() -> Unit): DefaultDslEngineRegistry {
    return DslEngineRegistryBuilder().apply(configure).build()
}