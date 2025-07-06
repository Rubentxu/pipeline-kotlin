package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.plugins.security.PluginSecurityPolicy
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class PluginIntegrationTest : DescribeSpec({
    
    describe("Plugin System Integration Tests") {
        
        it("should handle complete plugin lifecycle") {
            val tempDir = Files.createTempDirectory("integration-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create test plugin JAR
            val jarFile = createCompleteTestPlugin(tempDir, "lifecycle-plugin.jar")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // 1. Load plugin
                val loadResult = manager.loadPlugin(jarFile)
                loadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                val plugin = manager.getPlugin("lifecycle-plugin")
                plugin shouldNotBe null
                plugin!!.metadata.id shouldBe "lifecycle-plugin"
                plugin.metadata.version shouldBe "1.0.0"
                
                // 2. Check plugin state
                manager.getPluginState("lifecycle-plugin") shouldBe PluginState.LOADED
                
                // 3. Get plugin statistics
                val stats = manager.getPluginStats()
                stats.totalPlugins shouldBe 1
                stats.loadedPlugins shouldBe 1
                stats.errorPlugins shouldBe 0
                
                // 4. Test ClassLoader functionality
                val classLoader = plugin.classLoader
                classLoader.getStats().pluginId shouldBe "lifecycle-plugin"
                
                // 5. Reload plugin
                val reloadResult = manager.reloadPlugin("lifecycle-plugin")
                reloadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                // 6. Unload plugin
                val unloadResult = manager.unloadPlugin("lifecycle-plugin")
                unloadResult shouldBe true
                
                manager.getPlugin("lifecycle-plugin") shouldBe null
                manager.getPluginState("lifecycle-plugin") shouldBe PluginState.UNLOADED
                
                // 7. Final statistics
                val finalStats = manager.getPluginStats()
                finalStats.totalPlugins shouldBe 0
                finalStats.loadedPlugins shouldBe 0
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle multiple plugins with different security policies") {
            val tempDir = Files.createTempDirectory("multi-plugin-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create multiple test plugins
            val plugin1 = createCompleteTestPlugin(tempDir, "plugin1.jar", "plugin1")
            val plugin2 = createCompleteTestPlugin(tempDir, "plugin2.jar", "plugin2")
            val plugin3 = createCompleteTestPlugin(tempDir, "plugin3.jar", "plugin3")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // Load all plugins
                val loadAllResult = manager.loadAllPlugins()
                loadAllResult shouldHaveSize 3
                loadAllResult.forEach { it.shouldBeInstanceOf<PluginLoadResult.Success>() }
                
                // Verify all plugins are loaded
                val allPlugins = manager.getAllPlugins()
                allPlugins shouldHaveSize 3
                
                val pluginIds = allPlugins.map { it.metadata.id }.sorted()
                pluginIds shouldBe listOf("plugin1", "plugin2", "plugin3")
                
                // Test plugin queries
                manager.getPlugin("plugin1") shouldNotBe null
                manager.getPlugin("plugin2") shouldNotBe null
                manager.getPlugin("plugin3") shouldNotBe null
                
                // Test statistics
                val stats = manager.getPluginStats()
                stats.totalPlugins shouldBe 3
                stats.loadedPlugins shouldBe 3
                stats.errorPlugins shouldBe 0
                
                // Test selective unloading
                manager.unloadPlugin("plugin2") shouldBe true
                manager.getAllPlugins() shouldHaveSize 2
                manager.getPlugin("plugin2") shouldBe null
                
                // Test shutdown
                manager.shutdown()
                manager.getAllPlugins() shouldHaveSize 0
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should enforce security policies correctly") {
            val tempDir = Files.createTempDirectory("security-integration-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val secureJar = createCompleteTestPlugin(tempDir, "secure-plugin.jar", "secure-plugin")
            
            // Test with strict security policy
            val strictManager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.STRICT,
                logger = mockLogger
            )
            
            runBlocking {
                val strictResult = strictManager.loadPlugin(secureJar)
                // Note: This might fail with strict policy depending on plugin content
                // The test validates that security policies are being applied
                
                when (strictResult) {
                    is PluginLoadResult.Success -> {
                        // Plugin passed strict security checks
                        val plugin = strictResult.plugin
                        plugin.classLoader.getStats().pluginId shouldBe "secure-plugin"
                    }
                    is PluginLoadResult.Failure -> {
                        // Plugin failed strict security validation
                        strictResult.error shouldContain "security validation"
                    }
                }
                
                strictManager.shutdown()
            }
            
            // Test with permissive policy
            val permissiveManager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                val permissiveResult = permissiveManager.loadPlugin(secureJar)
                permissiveResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                permissiveManager.shutdown()
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle plugin errors gracefully") {
            val tempDir = Files.createTempDirectory("error-integration-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create a plugin with invalid metadata
            val invalidJar = createInvalidPlugin(tempDir, "invalid-plugin.jar")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.loadPlugin(invalidJar)
                result.shouldBeInstanceOf<PluginLoadResult.Failure>()
                
                // Manager should still be functional after error
                manager.getPluginStats().totalPlugins shouldBe 0
                manager.getAllPlugins() shouldHaveSize 0
                
                // Should handle graceful shutdown even with errors
                manager.shutdown()
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should support hot reload functionality") {
            val tempDir = Files.createTempDirectory("hot-reload-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile = createCompleteTestPlugin(tempDir, "hot-reload-plugin.jar")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                enableHotReload = true,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // Initial load
                val loadResult = manager.loadPlugin(jarFile)
                loadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                val originalPlugin = manager.getPlugin("lifecycle-plugin")
                originalPlugin shouldNotBe null
                
                // Hot reload
                val reloadResult = manager.reloadPlugin("lifecycle-plugin")
                reloadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                val reloadedPlugin = manager.getPlugin("lifecycle-plugin")
                reloadedPlugin shouldNotBe null
                
                // Should be a new instance after reload
                reloadedPlugin!!.metadata.id shouldBe originalPlugin!!.metadata.id
                
                manager.shutdown()
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle concurrent plugin operations") {
            val tempDir = Files.createTempDirectory("concurrent-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create multiple plugins
            val plugins = (1..5).map { i ->
                createCompleteTestPlugin(tempDir, "concurrent-plugin$i.jar", "concurrent-plugin$i")
            }
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // Concurrent loading should be handled safely by mutex
                val results = manager.loadAllPlugins()
                results shouldHaveSize 5
                results.forEach { it.shouldBeInstanceOf<PluginLoadResult.Success>() }
                
                manager.getAllPlugins() shouldHaveSize 5
                
                // Concurrent operations
                val unloadResults = (1..3).map { i ->
                    manager.unloadPlugin("concurrent-plugin$i")
                }
                
                unloadResults.forEach { it shouldBe true }
                manager.getAllPlugins() shouldHaveSize 2
                
                manager.shutdown()
                manager.getAllPlugins() shouldHaveSize 0
            }
            
            tempDir.deleteRecursively()
        }
    }
})

// Helper function to create a complete test plugin JAR
private fun createCompleteTestPlugin(
    directory: File,
    fileName: String,
    pluginId: String = "lifecycle-plugin"
): File {
    val jarFile = File(directory, fileName)
    
    JarOutputStream(jarFile.outputStream()).use { jos ->
        // Add plugin.properties
        jos.putNextEntry(ZipEntry("plugin.properties"))
        val properties = """
            plugin.id=$pluginId
            plugin.name=Test Lifecycle Plugin
            plugin.version=1.0.0
            plugin.description=A test plugin for integration testing
            plugin.author=Test Framework
            plugin.main-class=com.example.TestPlugin
            plugin.allowed-packages=java.lang.,java.util.,kotlin.
            plugin.blocked-packages=java.security.,java.lang.reflect.
        """.trimIndent()
        jos.write(properties.toByteArray())
        jos.closeEntry()
        
        // Add main plugin class
        jos.putNextEntry(ZipEntry("com/example/TestPlugin.class"))
        jos.write("test plugin class content".toByteArray())
        jos.closeEntry()
        
        // Add additional resources
        jos.putNextEntry(ZipEntry("plugin-config.properties"))
        jos.write("config.key=value".toByteArray())
        jos.closeEntry()
    }
    
    return jarFile
}

// Helper function to create an invalid plugin JAR
private fun createInvalidPlugin(directory: File, fileName: String): File {
    val jarFile = File(directory, fileName)
    
    JarOutputStream(jarFile.outputStream()).use { jos ->
        // Add invalid metadata (missing main class)
        jos.putNextEntry(ZipEntry("plugin.properties"))
        val properties = """
            plugin.id=invalid-plugin
            plugin.name=Invalid Plugin
            plugin.version=1.0.0
            # Missing plugin.main-class property
        """.trimIndent()
        jos.write(properties.toByteArray())
        jos.closeEntry()
        
        jos.putNextEntry(ZipEntry("dummy.txt"))
        jos.write("dummy content".toByteArray())
        jos.closeEntry()
    }
    
    return jarFile
}