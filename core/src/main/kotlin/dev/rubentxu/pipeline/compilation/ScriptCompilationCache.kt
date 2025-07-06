package dev.rubentxu.pipeline.compilation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration

/**
 * Interface for script compilation caching system.
 * Provides thread-safe caching of compiled Kotlin scripts to improve performance.
 */
interface ScriptCompilationCache {
    suspend fun get(scriptContent: String, configuration: ScriptCompilationConfiguration): CompiledScript?
    suspend fun put(scriptContent: String, configuration: ScriptCompilationConfiguration, compiledScript: CompiledScript)
    suspend fun clear()
    suspend fun size(): Int
    fun getStats(): CacheStats
}

/**
 * In-memory implementation of ScriptCompilationCache with optional disk persistence.
 * Features:
 * - Thread-safe access using coroutines and Mutex
 * - LRU eviction policy
 * - Configurable cache size limits
 * - Optional disk persistence for cache entries
 * - Cache statistics tracking
 */
class DefaultScriptCompilationCache(
    private val maxEntries: Int = 100,
    private val maxMemoryMb: Int = 256,
    private val enableDiskCache: Boolean = true,
    private val cacheDirectory: Path = Paths.get(System.getProperty("java.io.tmpdir"), "kotlin-pipeline-cache")
) : ScriptCompilationCache {
    
    private val mutex = Mutex()
    private val memoryCache = LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true)
    private var stats = CacheStats()
    
    init {
        if (enableDiskCache) {
            Files.createDirectories(cacheDirectory)
        }
    }
    
    override suspend fun get(scriptContent: String, configuration: ScriptCompilationConfiguration): CompiledScript? {
        return mutex.withLock {
            val key = generateCacheKey(scriptContent, configuration)
            
            // Try memory cache first
            memoryCache[key]?.let { entry ->
                if (entry.isValid()) {
                    stats = stats.copy(hits = stats.hits + 1)
                    return@withLock entry.compiledScript
                } else {
                    // Remove expired entry
                    memoryCache.remove(key)
                }
            }
            
            // Try disk cache if enabled
            if (enableDiskCache) {
                loadFromDisk(key)?.let { compiledScript ->
                    // Add back to memory cache
                    val entry = CacheEntry(
                        compiledScript = compiledScript,
                        timestamp = Instant.now(),
                        accessCount = 1,
                        sizeBytes = estimateSize(compiledScript)
                    )
                    putInMemoryCache(key, entry)
                    stats = stats.copy(hits = stats.hits + 1, diskHits = stats.diskHits + 1)
                    return@withLock compiledScript
                }
            }
            
            stats = stats.copy(misses = stats.misses + 1)
            null
        }
    }
    
    override suspend fun put(
        scriptContent: String, 
        configuration: ScriptCompilationConfiguration, 
        compiledScript: CompiledScript
    ) {
        mutex.withLock {
            val key = generateCacheKey(scriptContent, configuration)
            val entry = CacheEntry(
                compiledScript = compiledScript,
                timestamp = Instant.now(),
                accessCount = 1,
                sizeBytes = estimateSize(compiledScript)
            )
            
            putInMemoryCache(key, entry)
            
            // Persist to disk if enabled
            if (enableDiskCache) {
                try {
                    saveToDisk(key, compiledScript)
                } catch (e: Exception) {
                    // Log error but don't fail the operation
                    // In a real implementation, you'd use proper logging
                    println("Failed to save to disk cache: ${e.message}")
                }
            }
            
            stats = stats.copy(puts = stats.puts + 1)
        }
    }
    
    override suspend fun clear() {
        mutex.withLock {
            memoryCache.clear()
            
            if (enableDiskCache) {
                try {
                    Files.walk(cacheDirectory)
                        .filter { Files.isRegularFile(it) }
                        .forEach { Files.deleteIfExists(it) }
                } catch (e: Exception) {
                    // Log error but don't fail
                    println("Failed to clear disk cache: ${e.message}")
                }
            }
            
            stats = CacheStats()
        }
    }
    
    override suspend fun size(): Int {
        return mutex.withLock { memoryCache.size }
    }
    
    override fun getStats(): CacheStats = stats
    
    private fun putInMemoryCache(key: String, entry: CacheEntry) {
        // Check memory limits before adding
        evictIfNecessary()
        
        memoryCache[key] = entry
        
        // Update memory usage stats
        val totalMemoryMb = memoryCache.values.sumOf { it.sizeBytes } / (1024 * 1024)
        stats = stats.copy(memoryUsageMb = totalMemoryMb.toInt())
    }
    
    private fun evictIfNecessary() {
        // Evict entries if we exceed limits
        while (memoryCache.size >= maxEntries || getCurrentMemoryUsageMb() >= maxMemoryMb) {
            if (memoryCache.isEmpty()) break
            
            // Remove least recently used entry (first in LinkedHashMap)
            val oldestKey = memoryCache.keys.first()
            memoryCache.remove(oldestKey)
            stats = stats.copy(evictions = stats.evictions + 1)
        }
    }
    
    private fun getCurrentMemoryUsageMb(): Int {
        return (memoryCache.values.sumOf { it.sizeBytes } / (1024 * 1024)).toInt()
    }
    
    private fun generateCacheKey(scriptContent: String, configuration: ScriptCompilationConfiguration): String {
        val configHash = configuration.hashCode().toString()
        val contentHash = MessageDigest.getInstance("SHA-256")
            .digest(scriptContent.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "script_${contentHash}_${configHash}"
    }
    
    private fun estimateSize(compiledScript: CompiledScript): Long {
        // This is a rough estimation - in a real implementation you might want more accurate sizing
        return 1024L // Default 1KB per script
    }
    
    private fun loadFromDisk(key: String): CompiledScript? {
        return try {
            val cacheFile = cacheDirectory.resolve("$key.cache")
            if (Files.exists(cacheFile)) {
                // In a real implementation, you'd need proper serialization/deserialization
                // For now, returning null as serializing CompiledScript is complex
                null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun saveToDisk(key: String, compiledScript: CompiledScript) {
        try {
            val cacheFile = cacheDirectory.resolve("$key.cache")
            // In a real implementation, you'd need proper serialization
            // For now, just create an empty file to indicate the entry exists
            Files.createFile(cacheFile)
        } catch (e: Exception) {
            // Ignore disk cache errors
        }
    }
    
    private data class CacheEntry(
        val compiledScript: CompiledScript,
        val timestamp: Instant,
        val accessCount: Long,
        val sizeBytes: Long,
        val ttlMinutes: Long = 60 // Default TTL of 1 hour
    ) {
        fun isValid(): Boolean {
            val now = Instant.now()
            val expiryTime = timestamp.plusSeconds(ttlMinutes * 60)
            return now.isBefore(expiryTime)
        }
    }
}

/**
 * Statistics for cache performance monitoring
 */
data class CacheStats(
    val hits: Long = 0,
    val misses: Long = 0,
    val puts: Long = 0,
    val evictions: Long = 0,
    val diskHits: Long = 0,
    val memoryUsageMb: Int = 0
) {
    val hitRate: Double
        get() = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
    
    val totalRequests: Long
        get() = hits + misses
        
    fun getFormattedStats(): String {
        return buildString {
            appendLine("Script Compilation Cache Statistics:")
            appendLine("====================================")
            appendLine("Hit Rate: ${"%.2f".format(hitRate * 100)}%")
            appendLine("Total Requests: $totalRequests")
            appendLine("Cache Hits: $hits")
            appendLine("Cache Misses: $misses")
            appendLine("Disk Hits: $diskHits")
            appendLine("Puts: $puts")
            appendLine("Evictions: $evictions")
            appendLine("Memory Usage: ${memoryUsageMb}MB")
        }
    }
}

/**
 * Factory for creating cache instances with different configurations
 */
object ScriptCompilationCacheFactory {
    
    /**
     * Creates a high-performance cache suitable for production use
     */
    fun createProductionCache(): ScriptCompilationCache {
        return DefaultScriptCompilationCache(
            maxEntries = 500,
            maxMemoryMb = 512,
            enableDiskCache = true
        )
    }
    
    /**
     * Creates a lightweight cache suitable for development/testing
     */
    fun createDevelopmentCache(): ScriptCompilationCache {
        return DefaultScriptCompilationCache(
            maxEntries = 50,
            maxMemoryMb = 64,
            enableDiskCache = false
        )
    }
    
    /**
     * Creates a cache with custom configuration
     */
    fun createCustomCache(
        maxEntries: Int = 100,
        maxMemoryMb: Int = 256,
        enableDiskCache: Boolean = true,
        cacheDirectory: Path? = null
    ): ScriptCompilationCache {
        return if (cacheDirectory != null) {
            DefaultScriptCompilationCache(maxEntries, maxMemoryMb, enableDiskCache, cacheDirectory)
        } else {
            DefaultScriptCompilationCache(maxEntries, maxMemoryMb, enableDiskCache)
        }
    }
}