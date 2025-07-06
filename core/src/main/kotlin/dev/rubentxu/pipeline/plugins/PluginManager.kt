package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.plugins.security.PluginSecurityValidator
import dev.rubentxu.pipeline.plugins.security.PluginSecurityPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Manages plugins with isolated ClassLoaders for security and reliability.
 * Provides plugin discovery, loading, and lifecycle management.
 */
class PluginManager(
    private val pluginDirectory: File = File("plugins"),
    private val enableHotReload: Boolean = false,
    private val securityPolicy: PluginSecurityPolicy = PluginSecurityPolicy.DEFAULT,
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
) {
    
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()
    private val pluginStates = ConcurrentHashMap<String, PluginState>()
    private val mutex = Mutex()
    private val securityValidator = PluginSecurityValidator(logger, securityPolicy)
    
    init {
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs()
            logger.info("Created plugin directory: ${pluginDirectory.absolutePath}")
        }
    }
    
    /**
     * Discovers and loads all plugins from the plugin directory.
     */
    suspend fun loadAllPlugins(): List<PluginLoadResult> {
        return mutex.withLock {
            logger.info("Discovering plugins in: ${pluginDirectory.absolutePath}")
            
            val results = mutableListOf<PluginLoadResult>()
            val pluginFiles = discoverPluginFiles()
            
            logger.info("Found ${pluginFiles.size} plugin files")
            
            for (pluginFile in pluginFiles) {
                try {
                    val result = loadPlugin(pluginFile)
                    results.add(result)
                    
                    when (result) {
                        is PluginLoadResult.Success -> {
                            logger.info("Successfully loaded plugin: ${result.plugin.metadata.id}")
                        }
                        is PluginLoadResult.Failure -> {
                            logger.error("Failed to load plugin from ${pluginFile.name}: ${result.error}")
                        }
                    }
                } catch (e: Exception) {
                    val error = "Unexpected error loading plugin from ${pluginFile.name}: ${e.message}"
                    logger.error("$error - ${e.message}")
                    results.add(PluginLoadResult.Failure(pluginFile.name, error))
                }
            }
            
            logger.info("Plugin loading completed. Success: ${results.count { it is PluginLoadResult.Success }}, " +
                       "Failed: ${results.count { it is PluginLoadResult.Failure }}")
            
            results
        }
    }
    
    /**
     * Loads a specific plugin from a file.
     */
    suspend fun loadPlugin(pluginFile: File): PluginLoadResult {
        return mutex.withLock {
            try {
                logger.debug("Loading plugin from: ${pluginFile.absolutePath}")
                
                // Validate plugin file
                validatePluginFile(pluginFile)
                
                // Extract plugin metadata
                val metadata = extractPluginMetadata(pluginFile)
                
                // Perform security validation
                val securityResult = securityValidator.validatePlugin(pluginFile, metadata)
                if (!securityResult.isSecure) {
                    val violationMessages = securityResult.violations.joinToString("; ") { it.message }
                    return@withLock PluginLoadResult.Failure(
                        pluginFile.name,
                        "Plugin failed security validation: $violationMessages"
                    )
                }
                
                // Log security warnings if any
                if (securityResult.warnings.isNotEmpty()) {
                    securityResult.warnings.forEach { warning ->
                        logger.warn("Plugin security warning: ${warning.message}")
                    }
                }
                
                // Check if plugin is already loaded
                if (loadedPlugins.containsKey(metadata.id)) {
                    return@withLock PluginLoadResult.Failure(
                        pluginFile.name,
                        "Plugin with ID '${metadata.id}' is already loaded"
                    )
                }
                
                // Create isolated ClassLoader
                val classLoader = PluginClassLoader.fromJar(
                    jarFile = pluginFile,
                    pluginId = metadata.id,
                    allowedPackages = metadata.allowedPackages ?: PluginClassLoader.DEFAULT_ALLOWED_PACKAGES,
                    blockedPackages = metadata.blockedPackages ?: PluginClassLoader.DEFAULT_BLOCKED_PACKAGES
                )
                
                // Load the plugin class
                val pluginClass = classLoader.loadClass(metadata.mainClass)
                
                // Verify the plugin implements the required interface
                if (!Plugin::class.java.isAssignableFrom(pluginClass)) {
                    throw PluginException("Plugin class '${metadata.mainClass}' does not implement Plugin interface")
                }
                
                // Create plugin instance
                val pluginInstance = pluginClass.getDeclaredConstructor().newInstance() as Plugin
                
                // Initialize the plugin
                val initContext = PluginInitializationContext(
                    pluginId = metadata.id,
                    logger = logger,
                    classLoader = classLoader
                )
                
                pluginInstance.initialize(initContext)
                
                // Create loaded plugin wrapper
                val loadedPlugin = LoadedPlugin(
                    metadata = metadata,
                    instance = pluginInstance,
                    classLoader = classLoader,
                    file = pluginFile
                )
                
                // Register the plugin
                loadedPlugins[metadata.id] = loadedPlugin
                pluginStates[metadata.id] = PluginState.LOADED
                
                logger.info("Plugin loaded successfully: ${metadata.id} v${metadata.version}")
                
                PluginLoadResult.Success(loadedPlugin)
                
            } catch (e: Exception) {
                logger.error("Failed to load plugin from ${pluginFile.name} - ${e.message}")
                PluginLoadResult.Failure(pluginFile.name, e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Unloads a plugin by ID.
     */
    suspend fun unloadPlugin(pluginId: String): Boolean {
        return mutex.withLock {
            val loadedPlugin = loadedPlugins[pluginId]
            if (loadedPlugin == null) {
                logger.warn("Attempted to unload non-existent plugin: $pluginId")
                return@withLock false
            }
            
            try {
                logger.info("Unloading plugin: $pluginId")
                
                // Call plugin's cleanup method
                loadedPlugin.instance.cleanup()
                
                // Close the ClassLoader
                loadedPlugin.classLoader.close()
                
                // Remove from registry
                loadedPlugins.remove(pluginId)
                pluginStates[pluginId] = PluginState.UNLOADED
                
                logger.info("Plugin unloaded successfully: $pluginId")
                true
                
            } catch (e: Exception) {
                logger.error("Failed to unload plugin: $pluginId - ${e.message}")
                pluginStates[pluginId] = PluginState.ERROR
                false
            }
        }
    }
    
    /**
     * Gets a loaded plugin by ID.
     */
    fun getPlugin(pluginId: String): LoadedPlugin? {
        return loadedPlugins[pluginId]
    }
    
    /**
     * Gets all loaded plugins.
     */
    fun getAllPlugins(): List<LoadedPlugin> {
        return loadedPlugins.values.toList()
    }
    
    /**
     * Gets plugins by type.
     */
    fun <T : Plugin> getPluginsByType(pluginClass: Class<T>): List<LoadedPlugin> {
        return loadedPlugins.values.filter { pluginClass.isAssignableFrom(it.instance::class.java) }
    }
    
    /**
     * Gets the state of a plugin.
     */
    fun getPluginState(pluginId: String): PluginState? {
        return pluginStates[pluginId]
    }
    
    /**
     * Gets statistics about all loaded plugins.
     */
    fun getPluginStats(): PluginManagerStats {
        val pluginStats = loadedPlugins.values.map { loadedPlugin ->
            PluginStats(
                id = loadedPlugin.metadata.id,
                version = loadedPlugin.metadata.version,
                state = pluginStates[loadedPlugin.metadata.id] ?: PluginState.UNKNOWN,
                classLoaderStats = loadedPlugin.classLoader.getStats()
            )
        }
        
        return PluginManagerStats(
            totalPlugins = loadedPlugins.size,
            loadedPlugins = pluginStats.count { it.state == PluginState.LOADED },
            errorPlugins = pluginStats.count { it.state == PluginState.ERROR },
            plugins = pluginStats
        )
    }
    
    /**
     * Reloads a plugin (unload then load).
     */
    suspend fun reloadPlugin(pluginId: String): PluginLoadResult {
        return mutex.withLock {
            val loadedPlugin = loadedPlugins[pluginId]
                ?: return@withLock PluginLoadResult.Failure(pluginId, "Plugin not found")
            
            val pluginFile = loadedPlugin.file
            
            // Unload first
            if (!unloadPlugin(pluginId)) {
                return@withLock PluginLoadResult.Failure(pluginId, "Failed to unload plugin for reload")
            }
            
            // Load again
            loadPlugin(pluginFile)
        }
    }
    
    /**
     * Shuts down the plugin manager and unloads all plugins.
     */
    suspend fun shutdown() {
        mutex.withLock {
            logger.info("Shutting down plugin manager...")
            
            val pluginIds = loadedPlugins.keys.toList()
            for (pluginId in pluginIds) {
                unloadPlugin(pluginId)
            }
            
            logger.info("Plugin manager shutdown complete")
        }
    }
    
    private fun discoverPluginFiles(): List<File> {
        if (!pluginDirectory.exists() || !pluginDirectory.isDirectory) {
            logger.warn("Plugin directory does not exist or is not a directory: ${pluginDirectory.absolutePath}")
            return emptyList()
        }
        
        return pluginDirectory.listFiles { file ->
            file.isFile && file.extension.lowercase() == "jar"
        }?.toList() ?: emptyList()
    }
    
    private fun validatePluginFile(pluginFile: File) {
        require(pluginFile.exists()) { "Plugin file does not exist: ${pluginFile.absolutePath}" }
        require(pluginFile.isFile) { "Plugin path is not a file: ${pluginFile.absolutePath}" }
        require(pluginFile.canRead()) { "Cannot read plugin file: ${pluginFile.absolutePath}" }
        require(pluginFile.extension.lowercase() == "jar") { "Plugin file must be a JAR: ${pluginFile.absolutePath}" }
    }
    
    private fun extractPluginMetadata(pluginFile: File): PluginMetadata {
        JarFile(pluginFile).use { jarFile ->
            val manifestEntry = jarFile.getJarEntry("META-INF/MANIFEST.MF")
            val pluginPropertiesEntry = jarFile.getJarEntry("plugin.properties")
            
            when {
                pluginPropertiesEntry != null -> {
                    // Load from plugin.properties
                    val properties = Properties()
                    jarFile.getInputStream(pluginPropertiesEntry).use { inputStream ->
                        properties.load(inputStream)
                    }
                    return parsePluginProperties(properties, pluginFile.nameWithoutExtension)
                }
                
                manifestEntry != null -> {
                    // Load from manifest
                    val manifest = jarFile.manifest
                    return parseManifestAttributes(manifest.mainAttributes, pluginFile.nameWithoutExtension)
                }
                
                else -> {
                    throw PluginException("No plugin metadata found in ${pluginFile.name}. Expected plugin.properties or MANIFEST.MF")
                }
            }
        }
    }
    
    private fun parsePluginProperties(properties: Properties, defaultId: String): PluginMetadata {
        val id = properties.getProperty("plugin.id") ?: defaultId
        val version = properties.getProperty("plugin.version") ?: "1.0.0"
        val name = properties.getProperty("plugin.name") ?: id
        val description = properties.getProperty("plugin.description") ?: ""
        val author = properties.getProperty("plugin.author") ?: "Unknown"
        val mainClass = properties.getProperty("plugin.main-class")
            ?: throw PluginException("Missing required property: plugin.main-class")
        
        val allowedPackages = properties.getProperty("plugin.allowed-packages")
            ?.split(",")?.map { it.trim() }?.toSet()
        
        val blockedPackages = properties.getProperty("plugin.blocked-packages")
            ?.split(",")?.map { it.trim() }?.toSet()
        
        return PluginMetadata(
            id = id,
            version = version,
            name = name,
            description = description,
            author = author,
            mainClass = mainClass,
            allowedPackages = allowedPackages,
            blockedPackages = blockedPackages
        )
    }
    
    private fun parseManifestAttributes(attributes: java.util.jar.Attributes, defaultId: String): PluginMetadata {
        val id = attributes.getValue("Plugin-Id") ?: defaultId
        val version = attributes.getValue("Plugin-Version") ?: "1.0.0"
        val name = attributes.getValue("Plugin-Name") ?: id
        val description = attributes.getValue("Plugin-Description") ?: ""
        val author = attributes.getValue("Plugin-Author") ?: "Unknown"
        val mainClass = attributes.getValue("Plugin-Main-Class")
            ?: throw PluginException("Missing required manifest attribute: Plugin-Main-Class")
        
        return PluginMetadata(
            id = id,
            version = version,
            name = name,
            description = description,
            author = author,
            mainClass = mainClass
        )
    }
}

/**
 * Base interface that all plugins must implement.
 */
interface Plugin {
    /**
     * Called when the plugin is loaded and initialized.
     */
    fun initialize(context: PluginInitializationContext)
    
    /**
     * Called when the plugin is being unloaded.
     */
    fun cleanup()
    
    /**
     * Gets information about this plugin.
     */
    fun getInfo(): PluginInfo
}

/**
 * Context provided to plugins during initialization.
 */
data class PluginInitializationContext(
    val pluginId: String,
    val logger: IPipelineLogger,
    val classLoader: ClassLoader
)

/**
 * Information about a plugin.
 */
data class PluginInfo(
    val name: String,
    val description: String,
    val version: String,
    val capabilities: Set<String> = emptySet()
)

/**
 * Metadata extracted from plugin files.
 */
data class PluginMetadata(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val author: String,
    val mainClass: String,
    val allowedPackages: Set<String>? = null,
    val blockedPackages: Set<String>? = null
)

/**
 * A loaded plugin with its metadata and runtime information.
 */
data class LoadedPlugin(
    val metadata: PluginMetadata,
    val instance: Plugin,
    val classLoader: PluginClassLoader,
    val file: File
)

/**
 * Result of plugin loading operation.
 */
sealed class PluginLoadResult {
    data class Success(val plugin: LoadedPlugin) : PluginLoadResult()
    data class Failure(val pluginName: String, val error: String) : PluginLoadResult()
}

/**
 * Plugin lifecycle states.
 */
enum class PluginState {
    LOADED,
    UNLOADED,
    ERROR,
    UNKNOWN
}

/**
 * Statistics for a single plugin.
 */
data class PluginStats(
    val id: String,
    val version: String,
    val state: PluginState,
    val classLoaderStats: PluginClassLoaderStats
)

/**
 * Overall plugin manager statistics.
 */
data class PluginManagerStats(
    val totalPlugins: Int,
    val loadedPlugins: Int,
    val errorPlugins: Int,
    val plugins: List<PluginStats>
) {
    fun getFormattedStats(): String {
        return buildString {
            appendLine("Plugin Manager Statistics")
            appendLine("=========================")
            appendLine("Total Plugins: $totalPlugins")
            appendLine("Loaded: $loadedPlugins")
            appendLine("Errors: $errorPlugins")
            appendLine()
            
            if (plugins.isNotEmpty()) {
                appendLine("Plugin Details:")
                plugins.forEach { plugin ->
                    appendLine("- ${plugin.id} v${plugin.version} (${plugin.state})")
                    appendLine("  Classes Loaded: ${plugin.classLoaderStats.loadedClassCount}")
                }
            }
        }
    }
}

/**
 * Exception thrown during plugin operations.
 */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)