package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.security.AccessController
import java.security.PrivilegedAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Secure and isolated ClassLoader for pipeline plugins.
 * Provides strong isolation between plugins and the host system while maintaining
 * necessary access to core pipeline APIs.
 */
class PluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader = getSystemClassLoader(),
    private val pluginId: String,
    private val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    private val blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES,
    private val enableLogging: Boolean = true
) : URLClassLoader(urls, parent) {
    
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
    private val loadedClasses = ConcurrentHashMap<String, Class<*>>()
    private val lock = ReentrantReadWriteLock()
    
    init {
        if (enableLogging) {
            logger.debug("Created PluginClassLoader for plugin: $pluginId with URLs: ${urls.contentToString()}")
        }
    }
    
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return lock.read {
            // Check cache first
            loadedClasses[name]?.let { cachedClass ->
                if (resolve) resolveClass(cachedClass)
                return@read cachedClass
            }
            
            // Security check
            validateClassAccess(name)
            
            try {
                // Try to find class in this classloader first (plugin classes)
                val clazz = findLoadedClass(name) ?: run {
                    if (shouldLoadFromPlugin(name)) {
                        try {
                            findClass(name)
                        } catch (e: ClassNotFoundException) {
                            // Fall back to parent if not found in plugin
                            super.loadClass(name, false)
                        }
                    } else {
                        // Load from parent first (system/pipeline classes)
                        try {
                            super.loadClass(name, false)
                        } catch (e: ClassNotFoundException) {
                            // If parent can't find it, try plugin
                            findClass(name)
                        }
                    }
                }
                
                if (resolve) {
                    resolveClass(clazz)
                }
                
                // Cache the loaded class
                lock.write {
                    loadedClasses[name] = clazz
                }
                
                if (enableLogging) {
                    logger.debug("Loaded class: $name for plugin: $pluginId")
                }
                
                clazz
                
            } catch (e: ClassNotFoundException) {
                if (enableLogging) {
                    logger.error("Failed to load class: $name for plugin: $pluginId - ${e.message}")
                }
                throw e
            } catch (e: SecurityException) {
                logger.error("Security violation: Blocked class access to $name for plugin: $pluginId")
                throw e
            }
        }
    }
    
    override fun findClass(name: String): Class<*> {
        validateClassAccess(name)
        
        return try {
            super.findClass(name)
        } catch (e: ClassNotFoundException) {
            if (enableLogging) {
                logger.debug("Class not found in plugin classpath: $name for plugin: $pluginId")
            }
            throw e
        }
    }
    
    override fun findResource(name: String): URL? {
        validateResourceAccess(name)
        return super.findResource(name)
    }
    
    override fun findResources(name: String): java.util.Enumeration<URL> {
        validateResourceAccess(name)
        return super.findResources(name)
    }
    
    /**
     * Validates if a class can be accessed by this plugin.
     * Throws SecurityException if access is not allowed.
     */
    private fun validateClassAccess(className: String) {
        // Check blocked packages first (more restrictive)
        for (blockedPackage in blockedPackages) {
            if (className.startsWith(blockedPackage)) {
                throw SecurityException("Access to package '$blockedPackage' is blocked for plugin: $pluginId")
            }
        }
        
        // For system classes, check if they're in allowed packages
        if (isSystemClass(className)) {
            val isAllowed = allowedPackages.any { allowedPackage ->
                className.startsWith(allowedPackage)
            }
            
            if (!isAllowed) {
                throw SecurityException("Access to system class '$className' is not allowed for plugin: $pluginId")
            }
        }
    }
    
    /**
     * Validates if a resource can be accessed by this plugin.
     */
    private fun validateResourceAccess(resourceName: String) {
        // Block access to sensitive system resources
        val sensitiveResources = listOf(
            "META-INF/services/",
            "security/",
            "credentials/",
            "keys/",
            ".key",
            ".p12",
            ".jks"
        )
        
        for (sensitive in sensitiveResources) {
            if (resourceName.contains(sensitive, ignoreCase = true)) {
                throw SecurityException("Access to sensitive resource '$resourceName' is blocked for plugin: $pluginId")
            }
        }
    }
    
    /**
     * Determines if a class should be loaded from the plugin first.
     */
    private fun shouldLoadFromPlugin(className: String): Boolean {
        // Plugin classes (non-system packages) should be loaded from plugin first
        return !isSystemClass(className)
    }
    
    /**
     * Determines if a class is a system class.
     */
    private fun isSystemClass(className: String): Boolean {
        return SYSTEM_PACKAGES.any { systemPackage ->
            className.startsWith(systemPackage)
        }
    }
    
    /**
     * Gets statistics about this classloader.
     */
    fun getStats(): PluginClassLoaderStats {
        return lock.read {
            PluginClassLoaderStats(
                pluginId = pluginId,
                loadedClassCount = loadedClasses.size,
                urls = urLs.toList(),
                loadedClasses = loadedClasses.keys.toSet()
            )
        }
    }
    
    /**
     * Clears the class cache. Use with caution.
     */
    fun clearCache() {
        lock.write {
            loadedClasses.clear()
        }
        if (enableLogging) {
            logger.debug("Cleared class cache for plugin: $pluginId")
        }
    }
    
    override fun close() {
        try {
            clearCache()
            super.close()
            if (enableLogging) {
                logger.info("Closed PluginClassLoader for plugin: $pluginId")
            }
        } catch (e: Exception) {
            logger.error("Error closing PluginClassLoader for plugin: $pluginId - ${e.message}")
        }
    }
    
    companion object {
        /**
         * System packages that are always loaded from the parent classloader.
         */
        private val SYSTEM_PACKAGES = setOf(
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "sun.",
            "com.sun.",
            "org.xml.",
            "org.w3c.",
            "org.ietf.",
            "org.omg."
        )
        
        /**
         * Default packages that plugins are allowed to access.
         */
        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "java.lang.",
            "java.util.",
            "java.io.",
            "java.nio.",
            "java.text.",
            "java.time.",
            "java.math.",
            "java.net.",
            "kotlin.",
            "kotlinx.coroutines.",
            "kotlinx.serialization.",
            "dev.rubentxu.pipeline.dsl.",
            "dev.rubentxu.pipeline.model.",
            "dev.rubentxu.pipeline.steps.",
            "dev.rubentxu.pipeline.logger.",
            "dev.rubentxu.pipeline.events.",
            "org.slf4j.",
            "ch.qos.logback."
        )
        
        /**
         * Packages that are explicitly blocked for plugins.
         */
        val DEFAULT_BLOCKED_PACKAGES = setOf(
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.security.",
            "java.lang.management.",
            "sun.",
            "com.sun.",
            "jdk.internal.",
            "dev.rubentxu.pipeline.compilation.",
            "dev.rubentxu.pipeline.security.",
            "dev.rubentxu.pipeline.plugins.internal."
        )
        
        /**
         * Creates a PluginClassLoader from a JAR file.
         */
        fun fromJar(
            jarFile: File,
            pluginId: String,
            allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
            blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES
        ): PluginClassLoader {
            require(jarFile.exists()) { "Plugin JAR file does not exist: ${jarFile.absolutePath}" }
            require(jarFile.isFile) { "Plugin path is not a file: ${jarFile.absolutePath}" }
            require(jarFile.canRead()) { "Cannot read plugin JAR file: ${jarFile.absolutePath}" }
            
            val url = jarFile.toURI().toURL()
            return PluginClassLoader(
                urls = arrayOf(url),
                pluginId = pluginId,
                allowedPackages = allowedPackages,
                blockedPackages = blockedPackages
            )
        }
        
        /**
         * Creates a PluginClassLoader from multiple JAR files.
         */
        fun fromJars(
            jarFiles: List<File>,
            pluginId: String,
            allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
            blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES
        ): PluginClassLoader {
            require(jarFiles.isNotEmpty()) { "At least one JAR file is required" }
            
            jarFiles.forEach { jarFile ->
                require(jarFile.exists()) { "Plugin JAR file does not exist: ${jarFile.absolutePath}" }
                require(jarFile.isFile) { "Plugin path is not a file: ${jarFile.absolutePath}" }
                require(jarFile.canRead()) { "Cannot read plugin JAR file: ${jarFile.absolutePath}" }
            }
            
            val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
            return PluginClassLoader(
                urls = urls,
                pluginId = pluginId,
                allowedPackages = allowedPackages,
                blockedPackages = blockedPackages
            )
        }
        
        /**
         * Creates a PluginClassLoader from a directory containing classes.
         */
        fun fromDirectory(
            directory: File,
            pluginId: String,
            allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
            blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES
        ): PluginClassLoader {
            require(directory.exists()) { "Plugin directory does not exist: ${directory.absolutePath}" }
            require(directory.isDirectory) { "Plugin path is not a directory: ${directory.absolutePath}" }
            require(directory.canRead()) { "Cannot read plugin directory: ${directory.absolutePath}" }
            
            val url = directory.toURI().toURL()
            return PluginClassLoader(
                urls = arrayOf(url),
                pluginId = pluginId,
                allowedPackages = allowedPackages,
                blockedPackages = blockedPackages
            )
        }
    }
}

/**
 * Statistics about a PluginClassLoader instance.
 */
data class PluginClassLoaderStats(
    val pluginId: String,
    val loadedClassCount: Int,
    val urls: List<URL>,
    val loadedClasses: Set<String>
) {
    fun getFormattedStats(): String {
        return buildString {
            appendLine("Plugin ClassLoader Statistics for: $pluginId")
            appendLine("=".repeat(40 + pluginId.length))
            appendLine("Loaded Classes: $loadedClassCount")
            appendLine("URLs: ${urls.size}")
            urls.forEach { url ->
                appendLine("  - $url")
            }
            if (loadedClasses.isNotEmpty()) {
                appendLine("Loaded Classes:")
                loadedClasses.sorted().forEach { className ->
                    appendLine("  - $className")
                }
            }
        }
    }
}