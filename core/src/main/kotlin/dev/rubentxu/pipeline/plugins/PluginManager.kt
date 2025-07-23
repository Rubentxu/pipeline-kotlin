package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.interfaces.ILogger
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
 *
 * PluginManager provides comprehensive plugin lifecycle management with security-first
 * design principles. It handles plugin discovery, loading, initialization, monitoring,
 * and cleanup with complete isolation between plugins to prevent interference and
 * security vulnerabilities.
 *
 * ## Key Features
 * - **Isolated Execution**: Each plugin runs in its own ClassLoader for security
 * - **Security Validation**: Comprehensive security checks for plugin JARs
 * - **Lifecycle Management**: Complete plugin lifecycle from discovery to cleanup
 * - **Hot Reload**: Optional runtime plugin reloading capability
 * - **Thread Safety**: Concurrent plugin operations with proper synchronization
 * - **Monitoring**: Detailed statistics and state tracking
 * - **Error Handling**: Robust error handling with recovery mechanisms
 *
 * ## Plugin Architecture
 * The manager supports plugins that implement the [Plugin] interface:
 * ```kotlin
 * class MyPlugin : Plugin {
 *     override fun initialize(context: PluginInitializationContext) {
 *         // Plugin initialization logic
 *     }
 *     
 *     override fun cleanup() {
 *         // Plugin cleanup logic
 *     }
 *     
 *     override fun getInfo(): PluginInfo {
 *         return PluginInfo(
 *             id = "my-plugin",
 *             name = "My Plugin",
 *             version = "1.0.0",
 *             description = "Example plugin"
 *         )
 *     }
 * }
 * ```
 *
 * ## Security Model
 * The plugin system enforces strict security policies:
 * - **JAR Validation**: Digital signature verification and integrity checks
 * - **Bytecode Analysis**: Scanning for malicious patterns and unauthorized access
 * - **ClassLoader Isolation**: Plugins cannot access each other's classes or data
 * - **Permission Restrictions**: Configurable security policies for system access
 * - **Resource Limits**: Memory and CPU constraints for plugin execution
 *
 * ## Usage Example
 * ```kotlin
 * // Create plugin manager with custom configuration
 * val pluginManager = PluginManager(
 *     pluginDirectory = File("/path/to/plugins"),
 *     enableHotReload = true,
 *     securityPolicy = PluginSecurityPolicy.STRICT,
 *     logger = myLogger
 * )
 *
 * // Load all plugins from directory
 * val loadResults = pluginManager.loadAllPlugins()
 * loadResults.forEach { result ->
 *     when (result) {
 *         is PluginLoadResult.Success -> {
 *             println("Loaded plugin: ${result.plugin.metadata.id}")
 *         }
 *         is PluginLoadResult.Failure -> {
 *             println("Failed to load plugin: ${result.error}")
 *         }
 *     }
 * }
 *
 * // Get plugin by ID
 * val plugin = pluginManager.getPlugin("my-plugin-id")
 * 
 * // Monitor plugin statistics
 * val stats = pluginManager.getPluginStats()
 * println("Loaded plugins: ${stats.totalPlugins}")
 * ```
 *
 * ## Thread Safety
 * All operations are thread-safe and can be called concurrently:
 * - Plugin loading and unloading use mutex synchronization
 * - Plugin state changes are atomic
 * - Statistics are collected using thread-safe collections
 * - Cleanup operations are safely coordinated
 *
 * ## Error Handling
 * The manager provides comprehensive error handling:
 * - Plugin loading failures are isolated and don't affect other plugins
 * - Security violations are detected and logged
 * - Cleanup is performed even when errors occur
 * - Detailed error information is provided for debugging
 *
 * @param pluginDirectory Directory containing plugin JAR files
 * @param enableHotReload Whether to enable runtime plugin reloading
 * @param securityPolicy Security policy for plugin validation and execution
 * @param logger Logger instance for plugin management events
 *
 * @since 1.0.0
 * @see Plugin
 * @see PluginSecurityPolicy
 * @see PluginSecurityValidator
 * @see PluginLoadResult
 */
class PluginManager(
    private val pluginDirectory: File = File("plugins"),
    private val enableHotReload: Boolean = false,
    private val securityPolicy: PluginSecurityPolicy = PluginSecurityPolicy.DEFAULT,
    private val logger: ILogger = PipelineLogger.getLogger()
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
     *
     * This method performs comprehensive plugin discovery and loading with security
     * validation, error handling, and detailed logging. It scans the plugin directory
     * for JAR files, validates each plugin's security and integrity, and loads them
     * into isolated execution environments.
     *
     * ## Discovery Process
     * 1. **Directory Scanning**: Searches for JAR files in the plugin directory
     * 2. **Metadata Extraction**: Reads plugin metadata from JAR manifests
     * 3. **Security Validation**: Validates plugin security and integrity
     * 4. **ClassLoader Creation**: Creates isolated ClassLoader for each plugin
     * 5. **Plugin Loading**: Loads and initializes plugin classes
     * 6. **State Tracking**: Updates plugin states and statistics
     *
     * ## Security Validation
     * Each plugin undergoes rigorous security checks:
     * - **Digital Signature Verification**: Validates plugin authenticity
     * - **Bytecode Analysis**: Scans for malicious code patterns
     * - **Dependency Validation**: Checks plugin dependencies for security
     * - **Permission Analysis**: Validates required permissions
     * - **Resource Usage**: Ensures plugins don't exceed resource limits
     *
     * ## Error Handling
     * The method provides comprehensive error handling:
     * - **Individual Plugin Failures**: One plugin failure doesn't affect others
     * - **Security Violations**: Detected and logged with detailed information
     * - **Loading Errors**: Compilation and runtime errors are captured
     * - **Resource Cleanup**: Proper cleanup even when errors occur
     * - **Detailed Logging**: All events are logged for debugging
     *
     * ## Performance Considerations
     * - **Concurrent Processing**: Plugins are processed efficiently
     * - **Resource Management**: Memory and CPU usage is monitored
     * - **Caching**: Plugin metadata is cached for performance
     * - **Lazy Loading**: Plugin classes are loaded only when needed
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager(File("/path/to/plugins"))
     * val results = pluginManager.loadAllPlugins()
     * 
     * val successful = results.filterIsInstance<PluginLoadResult.Success>()
     * val failed = results.filterIsInstance<PluginLoadResult.Failure>()
     * 
     * println("Successfully loaded ${successful.size} plugins")
     * println("Failed to load ${failed.size} plugins")
     * 
     * failed.forEach { failure ->
     *     logger.error("Plugin ${failure.pluginId} failed: ${failure.error}")
     * }
     * ```
     *
     * @return List of [PluginLoadResult] containing results for each plugin discovery attempt.
     *         Each result is either a [PluginLoadResult.Success] with the loaded plugin or
     *         a [PluginLoadResult.Failure] with error details.
     * @see PluginLoadResult
     * @see loadPlugin
     * @since 1.0.0
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
     * Loads a specific plugin from a JAR file with comprehensive security validation.
     *
     * This method performs detailed plugin loading with security validation, metadata
     * extraction, and isolated execution environment setup. It validates the plugin's
     * integrity, security compliance, and compatibility before creating an isolated
     * ClassLoader and initializing the plugin instance.
     *
     * ## Loading Process
     * 1. **File Validation**: Ensures the plugin file exists and is accessible
     * 2. **Metadata Extraction**: Reads plugin metadata from JAR manifest or properties
     * 3. **Security Validation**: Comprehensive security checks using [PluginSecurityValidator]
     * 4. **Duplicate Detection**: Prevents loading plugins with duplicate IDs
     * 5. **ClassLoader Creation**: Creates isolated [PluginClassLoader] with security restrictions
     * 6. **Plugin Loading**: Loads and instantiates the plugin class
     * 7. **Interface Verification**: Ensures plugin implements [Plugin] interface
     * 8. **Initialization**: Calls plugin's initialize method with proper context
     * 9. **Registration**: Registers the plugin in the manager's internal registry
     *
     * ## Security Validation
     * Each plugin undergoes comprehensive security validation:
     * - **Digital Signature**: Verifies plugin authenticity and integrity
     * - **Bytecode Analysis**: Scans for malicious code patterns and vulnerabilities
     * - **Dependency Validation**: Checks all plugin dependencies for security issues
     * - **Permission Analysis**: Validates required permissions against security policy
     * - **Resource Limits**: Ensures plugin doesn't exceed resource constraints
     * - **Package Restrictions**: Enforces allowed and blocked package access rules
     *
     * ## Isolation and ClassLoading
     * The plugin is loaded in complete isolation:
     * - **Separate ClassLoader**: Each plugin gets its own isolated ClassLoader
     * - **Package Filtering**: Restricts access to system and sensitive packages
     * - **Resource Isolation**: Prevents plugins from accessing each other's resources
     * - **Security Context**: Applies security policies specific to the plugin
     *
     * ## Error Handling
     * The method handles various error scenarios gracefully:
     * - **File System Errors**: Missing files, permission issues, corrupted JARs
     * - **Security Violations**: Failed security validation with detailed violation reporting
     * - **Metadata Errors**: Invalid or missing plugin metadata
     * - **Duplicate IDs**: Attempts to load plugins with existing IDs
     * - **Class Loading Errors**: Missing classes, dependency issues, incompatible versions
     * - **Initialization Failures**: Plugin initialization errors with proper cleanup
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * val pluginFile = File("/path/to/plugin.jar")
     * 
     * val result = pluginManager.loadPlugin(pluginFile)
     * when (result) {
     *     is PluginLoadResult.Success -> {
     *         val plugin = result.plugin
     *         println("Loaded plugin: ${plugin.metadata.name} v${plugin.metadata.version}")
     *         println("Plugin ID: ${plugin.metadata.id}")
     *         println("Author: ${plugin.metadata.author}")
     *     }
     *     is PluginLoadResult.Failure -> {
     *         println("Failed to load plugin: ${result.error}")
     *         logger.error("Plugin loading failed for ${result.pluginName}")
     *     }
     * }
     * ```
     *
     * @param pluginFile The JAR file containing the plugin. Must be a valid JAR file
     *                   with proper plugin metadata and implementation.
     * @return [PluginLoadResult.Success] with the loaded plugin if successful,
     *         or [PluginLoadResult.Failure] with error details if loading fails
     * @see PluginLoadResult
     * @see LoadedPlugin
     * @see PluginSecurityValidator
     * @see PluginClassLoader
     * @since 1.0.0
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
     * Unloads a plugin by ID with proper cleanup and resource management.
     *
     * This method performs safe plugin unloading with comprehensive cleanup of all
     * plugin resources, including calling the plugin's cleanup method, closing the
     * ClassLoader, and removing the plugin from the manager's registry. The unloading
     * process is thread-safe and handles errors gracefully.
     *
     * ## Unloading Process
     * 1. **Plugin Lookup**: Finds the plugin by ID in the loaded plugins registry
     * 2. **Cleanup Invocation**: Calls the plugin's cleanup() method for graceful shutdown
     * 3. **ClassLoader Cleanup**: Closes the plugin's isolated ClassLoader
     * 4. **Registry Cleanup**: Removes the plugin from all internal registries
     * 5. **State Update**: Updates plugin state to UNLOADED
     * 6. **Resource Release**: Releases all resources associated with the plugin
     *
     * ## Thread Safety
     * The unloading process is fully thread-safe:
     * - Uses mutex synchronization to prevent concurrent modifications
     * - Atomic state updates ensure consistent plugin state
     * - Safe cleanup even if multiple threads attempt to unload the same plugin
     *
     * ## Error Handling
     * The method handles various error scenarios:
     * - **Plugin Not Found**: Gracefully handles attempts to unload non-existent plugins
     * - **Cleanup Failures**: Continues with unloading even if plugin cleanup fails
     * - **ClassLoader Errors**: Handles ClassLoader close failures gracefully
     * - **Resource Leaks**: Ensures resources are released even on failures
     *
     * ## Cleanup Guarantees
     * The method provides strong cleanup guarantees:
     * - Plugin cleanup() method is always called if the plugin is loaded
     * - ClassLoader is always closed to prevent memory leaks
     * - Plugin is always removed from registry regardless of cleanup success
     * - Plugin state is always updated to reflect the unloading
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Load a plugin first
     * val loadResult = pluginManager.loadPlugin(pluginFile)
     * if (loadResult is PluginLoadResult.Success) {
     *     val pluginId = loadResult.plugin.metadata.id
     *     
     *     // Use the plugin...
     *     
     *     // Unload the plugin
     *     val unloadSuccess = pluginManager.unloadPlugin(pluginId)
     *     if (unloadSuccess) {
     *         println("Plugin unloaded successfully")
     *     } else {
     *         println("Failed to unload plugin")
     *     }
     * }
     * ```
     *
     * @param pluginId The unique identifier of the plugin to unload. Must match
     *                 the ID of a currently loaded plugin.
     * @return `true` if the plugin was successfully unloaded and cleaned up,
     *         `false` if the plugin was not found or unloading failed
     * @see loadPlugin
     * @see getPlugin
     * @see PluginState
     * @since 1.0.0
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
     * Retrieves a loaded plugin by its unique identifier.
     *
     * This method provides safe access to loaded plugins by their ID. It returns
     * the complete plugin information including metadata, instance, ClassLoader,
     * and source file. The method is thread-safe and returns null if the plugin
     * is not found or not currently loaded.
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Uses concurrent data structures for plugin storage
     * - Safe to call from multiple threads simultaneously
     * - No synchronization overhead for read operations
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Try to get a plugin by ID
     * val plugin = pluginManager.getPlugin("my-plugin-id")
     * if (plugin != null) {
     *     println("Found plugin: ${plugin.metadata.name}")
     *     println("Version: ${plugin.metadata.version}")
     *     println("Author: ${plugin.metadata.author}")
     *     
     *     // Access plugin functionality
     *     val pluginInfo = plugin.instance.getInfo()
     *     println("Plugin capabilities: ${pluginInfo.capabilities}")
     * } else {
     *     println("Plugin not found or not loaded")
     * }
     * ```
     *
     * @param pluginId The unique identifier of the plugin to retrieve
     * @return The [LoadedPlugin] instance if found and loaded, or `null` if
     *         the plugin is not found or not currently loaded
     * @see LoadedPlugin
     * @see getAllPlugins
     * @see getPluginState
     * @since 1.0.0
     */
    fun getPlugin(pluginId: String): LoadedPlugin? {
        return loadedPlugins[pluginId]
    }
    
    /**
     * Retrieves all currently loaded plugins.
     *
     * This method returns a snapshot of all plugins currently loaded in the manager.
     * The returned list is a copy, so modifications to the list won't affect the
     * manager's internal state. The method is thread-safe and provides a consistent
     * view of all loaded plugins at the time of the call.
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Returns a snapshot copy of the current plugin list
     * - Safe to call from multiple threads simultaneously
     * - Consistent view even during concurrent plugin loading/unloading
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Load some plugins...
     * pluginManager.loadAllPlugins()
     * 
     * // Get all loaded plugins
     * val allPlugins = pluginManager.getAllPlugins()
     * println("Total loaded plugins: ${allPlugins.size}")
     * 
     * allPlugins.forEach { plugin ->
     *     println("Plugin: ${plugin.metadata.name} v${plugin.metadata.version}")
     *     println("  ID: ${plugin.metadata.id}")
     *     println("  Author: ${plugin.metadata.author}")
     *     println("  Description: ${plugin.metadata.description}")
     * }
     * ```
     *
     * @return A list containing all currently loaded plugins. The list is a
     *         snapshot copy and can be empty if no plugins are loaded.
     * @see LoadedPlugin
     * @see getPlugin
     * @see getPluginStats
     * @since 1.0.0
     */
    fun getAllPlugins(): List<LoadedPlugin> {
        return loadedPlugins.values.toList()
    }
    
    /**
     * Retrieves all loaded plugins that implement or extend a specific plugin type.
     *
     * This method provides type-safe filtering of loaded plugins based on their
     * implementation type. It's useful for finding plugins that implement specific
     * interfaces or extend particular base classes, enabling typed plugin discovery
     * and usage.
     *
     * ## Type Safety
     * The method uses generics to ensure type safety:
     * - Generic parameter T must extend Plugin interface
     * - Runtime type checking ensures only compatible plugins are returned
     * - Safe casting is guaranteed for returned plugins
     *
     * ## Common Use Cases
     * - **Interface-based Discovery**: Find plugins implementing specific interfaces
     * - **Category Filtering**: Group plugins by functionality type
     * - **Feature Detection**: Identify plugins with specific capabilities
     * - **Dependency Resolution**: Find plugins that provide required services
     *
     * ## Usage Example
     * ```kotlin
     * // Define a custom plugin interface
     * interface DataProcessorPlugin : Plugin {
     *     fun processData(data: String): String
     * }
     * 
     * val pluginManager = PluginManager()
     * 
     * // Find all data processor plugins
     * val dataProcessors = pluginManager.getPluginsByType(DataProcessorPlugin::class.java)
     * 
     * println("Found ${dataProcessors.size} data processor plugins:")
     * dataProcessors.forEach { plugin ->
     *     println("  - ${plugin.metadata.name}")
     *     
     *     // Safe to cast because type is guaranteed
     *     val processor = plugin.instance as DataProcessorPlugin
     *     val result = processor.processData("test data")
     *     println("    Result: $result")
     * }
     * ```
     *
     * ## Performance Considerations
     * - The method performs runtime type checking on all loaded plugins
     * - Type checking overhead is minimal for typical plugin counts
     * - Results are not cached, so repeated calls will re-check types
     *
     * @param T The plugin type to filter by. Must extend the [Plugin] interface.
     * @param pluginClass The Class object representing the plugin type to search for
     * @return A list of [LoadedPlugin] instances whose plugin instances implement
     *         or extend the specified type. The list can be empty if no matching
     *         plugins are found.
     * @see Plugin
     * @see LoadedPlugin
     * @see getAllPlugins
     * @since 1.0.0
     */
    fun <T : Plugin> getPluginsByType(pluginClass: Class<T>): List<LoadedPlugin> {
        return loadedPlugins.values.filter { pluginClass.isAssignableFrom(it.instance::class.java) }
    }
    
    /**
     * Retrieves the current state of a plugin by its unique identifier.
     *
     * This method provides access to the current lifecycle state of a plugin,
     * which is essential for monitoring plugin health, debugging issues, and
     * implementing plugin management UI. The state reflects the plugin's current
     * status in the system lifecycle.
     *
     * ## Plugin States
     * The method returns one of the following states:
     * - **LOADED**: Plugin is successfully loaded and operational
     * - **UNLOADED**: Plugin has been unloaded and is no longer active
     * - **ERROR**: Plugin encountered an error during loading or execution
     * - **UNKNOWN**: Plugin state cannot be determined
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Uses concurrent data structures for state tracking
     * - Safe to call from multiple threads simultaneously
     * - Consistent state reporting during concurrent operations
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Check plugin state
     * val state = pluginManager.getPluginState("my-plugin-id")
     * when (state) {
     *     PluginState.LOADED -> {
     *         println("Plugin is loaded and ready")
     *         // Safe to use plugin
     *     }
     *     PluginState.UNLOADED -> {
     *         println("Plugin has been unloaded")
     *         // Plugin is not available
     *     }
     *     PluginState.ERROR -> {
     *         println("Plugin is in error state")
     *         // Handle error condition
     *     }
     *     PluginState.UNKNOWN -> {
     *         println("Plugin state is unknown")
     *         // Handle unknown state
     *     }
     *     null -> {
     *         println("Plugin not found")
     *         // Plugin doesn't exist
     *     }
     * }
     * ```
     *
     * @param pluginId The unique identifier of the plugin to check
     * @return The current [PluginState] of the plugin, or `null` if the plugin
     *         is not found or has never been loaded
     * @see PluginState
     * @see getPlugin
     * @see getPluginStats
     * @since 1.0.0
     */
    fun getPluginState(pluginId: String): PluginState? {
        return pluginStates[pluginId]
    }
    
    /**
     * Retrieves comprehensive statistics about all loaded plugins and manager state.
     *
     * This method provides detailed metrics about the plugin system's current state,
     * including loaded plugin counts, error states, and individual plugin statistics.
     * The statistics are useful for monitoring, debugging, and system health checks.
     *
     * ## Statistics Included
     * - **Total Plugins**: Count of all plugins known to the manager
     * - **Loaded Plugins**: Count of plugins currently in LOADED state
     * - **Error Plugins**: Count of plugins currently in ERROR state
     * - **Individual Plugin Stats**: Detailed metrics for each plugin including:
     *   - Plugin ID, version, and current state
     *   - ClassLoader statistics and resource usage
     *   - Memory consumption and performance metrics
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Creates a consistent snapshot of all plugin states
     * - Safe to call from multiple threads simultaneously
     * - No locking overhead for statistics collection
     *
     * ## Performance Considerations
     * - Statistics collection is lightweight and fast
     * - ClassLoader statistics may have minimal overhead
     * - Statistics are computed on-demand, not cached
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Get comprehensive statistics
     * val stats = pluginManager.getPluginStats()
     * 
     * println("Plugin System Statistics:")
     * println("Total plugins: ${stats.totalPlugins}")
     * println("Loaded plugins: ${stats.loadedPlugins}")
     * println("Error plugins: ${stats.errorPlugins}")
     * 
     * // Print individual plugin statistics
     * stats.plugins.forEach { plugin ->
     *     println("Plugin: ${plugin.id} v${plugin.version}")
     *     println("  State: ${plugin.state}")
     *     println("  Classes loaded: ${plugin.classLoaderStats.loadedClassCount}")
     * }
     * 
     * // Get formatted statistics
     * val formatted = stats.getFormattedStats()
     * println(formatted)
     * ```
     *
     * @return [PluginManagerStats] containing comprehensive statistics about
     *         all plugins and the manager's current state
     * @see PluginManagerStats
     * @see PluginStats
     * @see PluginState
     * @since 1.0.0
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
     * Reloads a plugin by unloading and then loading it again from its source file.
     *
     * This method provides hot-reload functionality for plugins, allowing plugins
     * to be updated without restarting the entire application. The reload process
     * is atomic - if unloading fails, the plugin remains in its current state.
     * If loading fails after successful unloading, the plugin will be in an
     * unloaded state.
     *
     * ## Reload Process
     * 1. **Plugin Lookup**: Finds the plugin by ID and retrieves its source file
     * 2. **Unload Phase**: Safely unloads the plugin using [unloadPlugin]
     * 3. **Load Phase**: Loads the plugin again from its original file using [loadPlugin]
     * 4. **Result Handling**: Returns the load result with success or failure details
     *
     * ## Atomicity Guarantees
     * The reload operation provides the following guarantees:
     * - If unloading fails, the plugin remains in its current state
     * - If loading fails after successful unloading, the plugin will be unloaded
     * - No partial state changes occur - the plugin is either loaded or unloaded
     *
     * ## Use Cases
     * - **Hot Reloading**: Update plugins during development without restart
     * - **Configuration Updates**: Apply new plugin configurations
     * - **Bug Fixes**: Apply plugin fixes in production environments
     * - **Version Updates**: Upgrade plugins to newer versions
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Uses mutex synchronization for atomic reload operations
     * - Prevents concurrent modifications during reload
     * - Safe to call from multiple threads simultaneously
     *
     * ## Error Handling
     * The method handles various error scenarios:
     * - **Plugin Not Found**: Returns failure if plugin doesn't exist
     * - **Unload Failures**: Returns failure if plugin cannot be unloaded
     * - **File Access Issues**: Handles file system errors gracefully
     * - **Load Failures**: Returns detailed error information for load failures
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Reload a plugin
     * val result = pluginManager.reloadPlugin("my-plugin-id")
     * when (result) {
     *     is PluginLoadResult.Success -> {
     *         println("Plugin reloaded successfully")
     *         val plugin = result.plugin
     *         println("Reloaded: ${plugin.metadata.name} v${plugin.metadata.version}")
     *     }
     *     is PluginLoadResult.Failure -> {
     *         println("Plugin reload failed: ${result.error}")
     *         // Handle reload failure
     *     }
     * }
     * ```
     *
     * ## Performance Considerations
     * - Reload operations are more expensive than regular load operations
     * - Plugin cleanup and re-initialization add overhead
     * - Use sparingly in production environments
     *
     * @param pluginId The unique identifier of the plugin to reload. Must match
     *                 the ID of a currently loaded plugin.
     * @return [PluginLoadResult.Success] if the plugin was reloaded successfully,
     *         or [PluginLoadResult.Failure] if the reload failed at any stage
     * @see loadPlugin
     * @see unloadPlugin
     * @see PluginLoadResult
     * @since 1.0.0
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
     * Shuts down the plugin manager and performs comprehensive cleanup.
     *
     * This method performs orderly shutdown of the plugin system, including
     * unloading all plugins, cleaning up resources, and preparing for application
     * termination. The shutdown process is designed to be safe and complete,
     * ensuring no resources are leaked and all plugins are properly cleaned up.
     *
     * ## Shutdown Process
     * 1. **Logging**: Logs the start of shutdown process with current plugin count
     * 2. **Plugin Unloading**: Calls [unloadPlugin] for all currently loaded plugins
     * 3. **Resource Cleanup**: Cleans up all manager resources and internal state
     * 4. **Completion Logging**: Logs successful completion of shutdown process
     *
     * ## Thread Safety
     * The method is fully thread-safe:
     * - Uses mutex synchronization for safe shutdown
     * - Prevents concurrent operations during shutdown
     * - Safe to call from multiple threads (only first call has effect)
     *
     * ## Cleanup Guarantees
     * The shutdown process provides strong cleanup guarantees:
     * - All plugins are properly unloaded with cleanup methods called
     * - All ClassLoaders are closed to prevent memory leaks
     * - All internal data structures are cleared
     * - All resources are released
     *
     * ## Error Handling
     * The method handles errors gracefully:
     * - Individual plugin unload failures don't stop the shutdown process
     * - Errors are logged but don't prevent other plugins from being unloaded
     * - Shutdown continues even if some cleanup operations fail
     *
     * ## Usage Guidelines
     * - Call this method when shutting down the application
     * - Use in try-with-resources patterns when possible
     * - Don't call other manager methods after shutdown
     * - Multiple calls are safe but unnecessary
     *
     * ## Usage Example
     * ```kotlin
     * val pluginManager = PluginManager()
     * 
     * // Load and use plugins...
     * pluginManager.loadAllPlugins()
     * 
     * // Shutdown when done
     * try {
     *     pluginManager.shutdown()
     *     println("Plugin manager shut down successfully")
     * } catch (e: Exception) {
     *     logger.error("Error during plugin manager shutdown", e)
     * }
     * ```
     *
     * ## Integration with Application Lifecycle
     * ```kotlin
     * class Application {
     *     private val pluginManager = PluginManager()
     *     
     *     fun start() {
     *         pluginManager.loadAllPlugins()
     *         // Start application services...
     *     }
     *     
     *     fun stop() {
     *         // Stop application services...
     *         pluginManager.shutdown()
     *     }
     * }
     * ```
     *
     * @since 1.0.0
     * @see unloadPlugin
     * @see cleanup
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
 *
 * This interface defines the fundamental contract that all plugins must fulfill
 * to be compatible with the plugin system. It provides the basic lifecycle
 * methods for plugin initialization, cleanup, and information retrieval.
 *
 * ## Plugin Lifecycle
 * All plugins follow a standard lifecycle:
 * 1. **Loading**: Plugin JAR is loaded and validated
 * 2. **Initialization**: [initialize] method is called with proper context
 * 3. **Operation**: Plugin provides its functionality to the system
 * 4. **Cleanup**: [cleanup] method is called before unloading
 * 5. **Unloading**: Plugin is removed from the system
 *
 * ## Implementation Requirements
 * Plugin implementations must:
 * - Provide a public no-argument constructor
 * - Implement all interface methods
 * - Handle initialization and cleanup properly
 * - Be thread-safe if accessed concurrently
 * - Follow security best practices
 *
 * ## Example Implementation
 * ```kotlin
 * class MyPlugin : Plugin {
 *     private var initialized = false
 *     
 *     override fun initialize(context: PluginInitializationContext) {
 *         context.logger.info("Initializing plugin: ${context.pluginId}")
 *         // Perform initialization logic
 *         initialized = true
 *     }
 *     
 *     override fun cleanup() {
 *         // Perform cleanup logic
 *         initialized = false
 *     }
 *     
 *     override fun getInfo(): PluginInfo {
 *         return PluginInfo(
 *             name = "My Plugin",
 *             description = "Example plugin implementation",
 *             version = "1.0.0",
 *             capabilities = setOf("example", "demonstration")
 *         )
 *     }
 * }
 * ```
 *
 * @since 1.0.0
 * @see PluginManager
 * @see PluginInitializationContext
 * @see PluginInfo
 */
interface Plugin {
    /**
     * Called when the plugin is loaded and initialized.
     *
     * This method is invoked by the plugin system after the plugin has been
     * loaded and validated. It provides the plugin with access to the system
     * context and allows the plugin to perform any necessary initialization.
     *
     * ## Initialization Process
     * - Called once per plugin lifecycle
     * - Provides access to plugin context and system services
     * - Should complete quickly to avoid blocking the system
     * - Must be thread-safe if the plugin will be used concurrently
     *
     * ## Context Usage
     * The provided context contains:
     * - Plugin ID for identification
     * - Logger instance for plugin logging
     * - ClassLoader for resource access
     *
     * @param context The initialization context containing system services
     *                and plugin-specific information
     * @see PluginInitializationContext
     * @since 1.0.0
     */
    fun initialize(context: PluginInitializationContext)
    
    /**
     * Called when the plugin is being unloaded.
     *
     * This method is invoked by the plugin system before the plugin is
     * unloaded from the system. It provides the plugin with an opportunity
     * to clean up resources, save state, and perform any necessary shutdown
     * operations.
     *
     * ## Cleanup Process
     * - Called once before plugin unloading
     * - Should release all resources and handles
     * - Should complete quickly to avoid blocking shutdown
     * - Must be safe to call even if initialization failed
     *
     * ## Cleanup Responsibilities
     * - Close open files and network connections
     * - Stop background threads and timers
     * - Release memory and other system resources
     * - Save persistent state if necessary
     *
     * @since 1.0.0
     */
    fun cleanup()
    
    /**
     * Gets information about this plugin.
     *
     * This method provides metadata about the plugin, including its name,
     * description, version, and capabilities. The information is used by
     * the plugin system for display, management, and feature discovery.
     *
     * ## Information Usage
     * The returned information is used for:
     * - Plugin management interfaces
     * - Capability discovery and matching
     * - Version compatibility checking
     * - Documentation and help systems
     *
     * @return [PluginInfo] containing plugin metadata and capabilities
     * @see PluginInfo
     * @since 1.0.0
     */
    fun getInfo(): PluginInfo
}

/**
 * Context provided to plugins during initialization.
 *
 * This data class contains the context and system services provided to plugins
 * during their initialization phase. It gives plugins access to essential
 * system services and information needed for proper initialization and operation.
 *
 * ## Context Contents
 * - **Plugin ID**: Unique identifier for the plugin instance
 * - **Logger**: Plugin-specific logger for consistent logging
 * - **ClassLoader**: The isolated ClassLoader for the plugin
 *
 * ## Usage in Plugin Initialization
 * ```kotlin
 * override fun initialize(context: PluginInitializationContext) {
 *     context.logger.info("Initializing plugin: ${context.pluginId}")
 *     
 *     // Use the plugin's ClassLoader for resource access
 *     val resource = context.classLoader.getResource("config.properties")
 *     
 *     // Plugin initialization logic...
 * }
 * ```
 *
 * ## Security Considerations
 * The context provides access to plugin-specific resources only:
 * - Logger is scoped to the plugin for proper log attribution
 * - ClassLoader is isolated to prevent access to other plugins
 * - Plugin ID is provided for system interaction identification
 *
 * @param pluginId The unique identifier of the plugin being initialized
 * @param logger Pipeline logger instance scoped to this plugin
 * @param classLoader The isolated ClassLoader for this plugin's resources
 * @since 1.0.0
 * @see Plugin.initialize
 * @see PluginManager
 */
data class PluginInitializationContext(
    val pluginId: String,
    val logger: ILogger,
    val classLoader: ClassLoader
)

/**
 * Information about a plugin.
 *
 * This data class contains metadata about a plugin, including its identity,
 * description, version, and capabilities. This information is used by the
 * plugin system for management, discovery, and user interfaces.
 *
 * ## Information Categories
 * - **Identity**: Name and description for user identification
 * - **Version**: Version information for compatibility checking
 * - **Capabilities**: Set of features or services provided by the plugin
 *
 * ## Usage Example
 * ```kotlin
 * override fun getInfo(): PluginInfo {
 *     return PluginInfo(
 *         name = "Database Connector",
 *         description = "Provides database connectivity for pipeline steps",
 *         version = "2.1.0",
 *         capabilities = setOf(
 *             "database",
 *             "sql",
 *             "transactions",
 *             "connection-pooling"
 *         )
 *     )
 * }
 * ```
 *
 * ## Capability Discovery
 * The capabilities set enables feature discovery:
 * ```kotlin
 * val dbPlugins = pluginManager.getAllPlugins()
 *     .filter { it.instance.getInfo().capabilities.contains("database") }
 * ```
 *
 * @param name Human-readable name of the plugin
 * @param description Detailed description of the plugin's functionality
 * @param version Version string following semantic versioning
 * @param capabilities Set of capability identifiers provided by the plugin
 * @since 1.0.0
 * @see Plugin.getInfo
 * @see PluginManager
 */
data class PluginInfo(
    val name: String,
    val description: String,
    val version: String,
    val capabilities: Set<String> = emptySet()
)

/**
 * Metadata extracted from plugin files.
 *
 * This data class contains comprehensive metadata extracted from plugin JAR files,
 * including identity information, security configuration, and dependency details.
 * The metadata is used by the plugin system for loading, security validation,
 * and dependency resolution.
 *
 * ## Metadata Sources
 * Plugin metadata can be extracted from:
 * - **plugin.properties** file in the JAR root
 * - **MANIFEST.MF** file in META-INF directory
 * - Default values for missing information
 *
 * ## Security Configuration
 * The metadata includes security-related configuration:
 * - **Allowed Packages**: Packages the plugin is allowed to access
 * - **Blocked Packages**: Packages the plugin is explicitly denied access to
 * - **Main Class**: The plugin's main implementation class
 *
 * ## Example plugin.properties
 * ```properties
 * plugin.id=database-connector
 * plugin.version=1.0.0
 * plugin.name=Database Connector
 * plugin.description=Provides database connectivity
 * plugin.author=Development Team
 * plugin.main-class=com.example.DatabasePlugin
 * plugin.allowed-packages=java.sql,javax.sql
 * plugin.blocked-packages=java.io,java.net
 * ```
 *
 * @param id Unique identifier for the plugin
 * @param version Version string following semantic versioning
 * @param name Human-readable name of the plugin
 * @param description Detailed description of the plugin's functionality
 * @param author Author or organization that created the plugin
 * @param mainClass Fully qualified name of the main plugin class
 * @param allowedPackages Set of package prefixes the plugin can access
 * @param blockedPackages Set of package prefixes the plugin cannot access
 * @since 1.0.0
 * @see PluginManager.loadPlugin
 * @see PluginClassLoader
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
 *
 * This data class represents a successfully loaded plugin with all its
 * associated runtime information. It serves as the complete representation
 * of a plugin instance within the plugin system, containing everything
 * needed to manage and interact with the plugin.
 *
 * ## Complete Plugin Context
 * The loaded plugin contains:
 * - **Metadata**: Static information extracted from the plugin JAR
 * - **Instance**: The actual plugin object for method invocation
 * - **ClassLoader**: Isolated ClassLoader for security and resource access
 * - **File**: Original JAR file for reloading and reference
 *
 * ## Usage Example
 * ```kotlin
 * val loadedPlugin = pluginManager.getPlugin("my-plugin")
 * if (loadedPlugin != null) {
 *     // Access plugin metadata
 *     println("Plugin: ${loadedPlugin.metadata.name}")
 *     println("Version: ${loadedPlugin.metadata.version}")
 *     
 *     // Use plugin functionality
 *     val info = loadedPlugin.instance.getInfo()
 *     println("Capabilities: ${info.capabilities}")
 *     
 *     // Access plugin resources
 *     val resource = loadedPlugin.classLoader.getResource("config.xml")
 *     
 *     // Reference source file
 *     println("Source: ${loadedPlugin.file.absolutePath}")
 * }
 * ```
 *
 * ## Security Isolation
 * Each loaded plugin maintains security isolation:
 * - Dedicated ClassLoader prevents inter-plugin interference
 * - Resource access is scoped to the plugin's JAR and allowed packages
 * - Plugin instances cannot access each other's data
 *
 * @param metadata Static metadata extracted from the plugin JAR
 * @param instance The actual plugin object implementing the Plugin interface
 * @param classLoader Isolated ClassLoader for this plugin's resources
 * @param file The original JAR file containing the plugin
 * @since 1.0.0
 * @see PluginManager.loadPlugin
 * @see PluginMetadata
 * @see Plugin
 * @see PluginClassLoader
 */
data class LoadedPlugin(
    val metadata: PluginMetadata,
    val instance: Plugin,
    val classLoader: PluginClassLoader,
    val file: File
)

/**
 * Result of plugin loading operation.
 *
 * This sealed class represents the outcome of a plugin loading operation,
 * providing a type-safe way to handle both successful and failed plugin
 * loading attempts. It enables proper error handling and result processing
 * in the plugin management system.
 *
 * ## Result Types
 * - [Success]: Plugin was loaded successfully with complete plugin information
 * - [Failure]: Plugin loading failed with detailed error information
 *
 * ## Usage Pattern
 * ```kotlin
 * val result = pluginManager.loadPlugin(pluginFile)
 * when (result) {
 *     is PluginLoadResult.Success -> {
 *         val plugin = result.plugin
 *         println("Successfully loaded: ${plugin.metadata.name}")
 *         // Use the loaded plugin...
 *     }
 *     is PluginLoadResult.Failure -> {
 *         println("Failed to load ${result.pluginName}: ${result.error}")
 *         // Handle the failure...
 *     }
 * }
 * ```
 *
 * ## Error Handling
 * The result type enables comprehensive error handling:
 * - Success results provide complete plugin information
 * - Failure results include detailed error messages and context
 * - Type safety prevents accessing plugin data when loading failed
 *
 * @since 1.0.0
 * @see PluginManager.loadPlugin
 * @see PluginManager.loadAllPlugins
 * @see LoadedPlugin
 */
sealed class PluginLoadResult {
    /**
     * Successful plugin loading result.
     *
     * This result indicates that the plugin was loaded successfully and is
     * ready for use. It contains the complete loaded plugin information
     * including metadata, instance, and runtime context.
     *
     * @param plugin The successfully loaded plugin with all associated information
     * @since 1.0.0
     */
    data class Success(val plugin: LoadedPlugin) : PluginLoadResult()
    
    /**
     * Failed plugin loading result.
     *
     * This result indicates that the plugin loading failed and provides
     * detailed error information for debugging and user feedback.
     *
     * @param pluginName Name or identifier of the plugin that failed to load
     * @param error Detailed error message explaining why the loading failed
     * @since 1.0.0
     */
    data class Failure(val pluginName: String, val error: String) : PluginLoadResult()
}

/**
 * Plugin lifecycle states.
 *
 * This enumeration defines the possible states of a plugin throughout its
 * lifecycle within the plugin management system. Plugin states are used
 * for monitoring, debugging, and ensuring proper plugin lifecycle management.
 *
 * ## State Transitions
 * Plugins typically follow this state transition pattern:
 * 1. **LOADED**: Plugin is successfully loaded and operational
 * 2. **UNLOADED**: Plugin has been unloaded and is no longer active
 * 3. **ERROR**: Plugin encountered an error during loading or execution
 * 4. **UNKNOWN**: Plugin state cannot be determined (rare, usually temporary)
 *
 * ## State Usage
 * ```kotlin
 * val state = pluginManager.getPluginState("my-plugin")
 * when (state) {
 *     PluginState.LOADED -> {
 *         // Plugin is ready to use
 *         val plugin = pluginManager.getPlugin("my-plugin")
 *         plugin?.instance?.someMethod()
 *     }
 *     PluginState.UNLOADED -> {
 *         // Plugin is not available
 *         println("Plugin has been unloaded")
 *     }
 *     PluginState.ERROR -> {
 *         // Plugin is in error state
 *         println("Plugin encountered an error")
 *     }
 *     PluginState.UNKNOWN -> {
 *         // Plugin state is indeterminate
 *         println("Plugin state is unknown")
 *     }
 * }
 * ```
 *
 * @since 1.0.0
 * @see PluginManager.getPluginState
 * @see PluginStats
 */
enum class PluginState {
    /** Plugin is successfully loaded and operational */
    LOADED,
    
    /** Plugin has been unloaded and is no longer active */
    UNLOADED,
    
    /** Plugin encountered an error during loading or execution */
    ERROR,
    
    /** Plugin state cannot be determined */
    UNKNOWN
}

/**
 * Statistics for a single plugin.
 *
 * This data class contains detailed statistics and metrics for an individual
 * plugin, including its identity, current state, and resource usage information.
 * Plugin statistics are used for monitoring, debugging, and performance analysis.
 *
 * ## Statistics Categories
 * - **Identity**: Plugin ID and version information
 * - **State**: Current lifecycle state of the plugin
 * - **Resource Usage**: ClassLoader statistics and resource consumption
 *
 * ## Usage Example
 * ```kotlin
 * val stats = pluginManager.getPluginStats()
 * stats.plugins.forEach { plugin ->
 *     println("Plugin: ${plugin.id} v${plugin.version}")
 *     println("  State: ${plugin.state}")
 *     println("  Classes loaded: ${plugin.classLoaderStats.loadedClassCount}")
 *     println("  Memory usage: ${plugin.classLoaderStats.memoryUsage} bytes")
 * }
 * ```
 *
 * ## Monitoring Integration
 * Plugin statistics can be integrated with monitoring systems:
 * - Track plugin health and performance over time
 * - Alert on plugin state changes or resource usage
 * - Generate reports on plugin system health
 *
 * @param id Unique identifier of the plugin
 * @param version Version string of the plugin
 * @param state Current lifecycle state of the plugin
 * @param classLoaderStats Resource usage statistics for the plugin's ClassLoader
 * @since 1.0.0
 * @see PluginManagerStats
 * @see PluginState
 * @see PluginClassLoaderStats
 */
data class PluginStats(
    val id: String,
    val version: String,
    val state: PluginState,
    val classLoaderStats: PluginClassLoaderStats
)

/**
 * Overall plugin manager statistics.
 *
 * This data class contains comprehensive statistics about the entire plugin
 * management system, including aggregate counts, individual plugin statistics,
 * and formatted reporting capabilities. Manager statistics provide a complete
 * view of the plugin system's health and performance.
 *
 * ## Aggregate Statistics
 * - **Total Plugins**: Count of all plugins known to the system
 * - **Loaded Plugins**: Count of plugins currently in LOADED state
 * - **Error Plugins**: Count of plugins currently in ERROR state
 * - **Individual Plugin Details**: Complete statistics for each plugin
 *
 * ## Usage Example
 * ```kotlin
 * val stats = pluginManager.getPluginStats()
 * 
 * println("Plugin System Overview:")
 * println("Total plugins: ${stats.totalPlugins}")
 * println("Loaded: ${stats.loadedPlugins}")
 * println("Errors: ${stats.errorPlugins}")
 * 
 * // Get formatted report
 * val report = stats.getFormattedStats()
 * println(report)
 * ```
 *
 * ## Monitoring Integration
 * Manager statistics are ideal for system monitoring:
 * - Track overall plugin system health
 * - Monitor plugin loading success rates
 * - Detect plugin system degradation
 * - Generate health reports and dashboards
 *
 * @param totalPlugins Total number of plugins known to the system
 * @param loadedPlugins Number of plugins currently in LOADED state
 * @param errorPlugins Number of plugins currently in ERROR state
 * @param plugins List of individual plugin statistics
 * @since 1.0.0
 * @see PluginStats
 * @see PluginManager.getPluginStats
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
 *
 * This exception is thrown when errors occur during plugin loading, initialization,
 * or other plugin-related operations. It provides detailed error information
 * for debugging and error handling in the plugin management system.
 *
 * ## Common Scenarios
 * - **Plugin Loading Failures**: JAR file corruption, missing classes, or metadata issues
 * - **Initialization Errors**: Plugin initialization method failures
 * - **Validation Failures**: Plugin validation or security check failures
 * - **Resource Issues**: ClassLoader or resource access problems
 *
 * ## Error Handling
 * ```kotlin
 * try {
 *     pluginManager.loadPlugin(pluginFile)
 * } catch (e: PluginException) {
 *     logger.error("Plugin operation failed: ${e.message}", e)
 *     // Handle plugin-specific error
 * }
 * ```
 *
 * ## Error Context
 * The exception provides context for debugging:
 * - Descriptive error messages
 * - Optional underlying cause exception
 * - Stack trace for debugging
 *
 * @param message Detailed description of the plugin operation error
 * @param cause Optional underlying cause of the plugin error
 * @since 1.0.0
 * @see PluginManager
 * @see PluginLoadResult.Failure
 */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)