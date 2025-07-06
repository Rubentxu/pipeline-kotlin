package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.plugins.security.PluginSecurityPolicy
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.jar.Attributes
import java.util.zip.ZipEntry

class PluginManagerTest : DescribeSpec({
    
    describe("PluginManager Basic Operations") {
        
        it("should create plugin directory if it doesn't exist") {
            val tempDir = Files.createTempDirectory("plugin-test").toFile()
            val pluginDir = File(tempDir, "plugins")
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            pluginDir.exists() shouldBe false
            
            val manager = PluginManager(
                pluginDirectory = pluginDir,
                logger = mockLogger
            )
            
            pluginDir.exists() shouldBe true
            pluginDir.isDirectory shouldBe true
            
            verify { mockLogger.info("Created plugin directory: ${pluginDir.absolutePath}") }
            
            tempDir.deleteRecursively()
        }
        
        it("should discover plugin files in directory") {
            val tempDir = Files.createTempDirectory("plugin-discovery-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create test JAR files
            val jar1 = File(tempDir, "plugin1.jar")
            val jar2 = File(tempDir, "plugin2.jar")
            val nonJar = File(tempDir, "not-a-plugin.txt")
            
            jar1.createNewFile()
            jar2.createNewFile()
            nonJar.createNewFile()
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val results = manager.loadAllPlugins()
                
                // Should find 2 JAR files but fail to load them (no proper plugin metadata)
                results shouldHaveSize 2
                results.forEach { it.shouldBeInstanceOf<PluginLoadResult.Failure>() }
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle empty plugin directory") {
            val tempDir = Files.createTempDirectory("empty-plugin-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val results = manager.loadAllPlugins()
                results.shouldBeEmpty()
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle non-existent plugin directory") {
            val nonExistentDir = File("/nonexistent/plugin/directory")
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = nonExistentDir,
                logger = mockLogger
            )
            
            runBlocking {
                val results = manager.loadAllPlugins()
                results.shouldBeEmpty()
            }
        }
    }
    
    describe("PluginManager Plugin Loading") {
        
        it("should load plugin with valid metadata from properties file") {
            val tempDir = Files.createTempDirectory("plugin-load-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create a valid plugin JAR with plugin.properties
            val jarFile = createTestPluginJar(tempDir, "test-plugin.jar", useProperties = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.loadPlugin(jarFile)
                
                result.shouldBeInstanceOf<PluginLoadResult.Success>()
                result.plugin.metadata.id shouldBe "test-plugin"
                result.plugin.metadata.version shouldBe "1.0.0"
                result.plugin.metadata.mainClass shouldBe "com.example.TestPlugin"
                
                manager.getPlugin("test-plugin") shouldNotBe null
                manager.getPluginState("test-plugin") shouldBe PluginState.LOADED
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should load plugin with valid metadata from manifest") {
            val tempDir = Files.createTempDirectory("plugin-manifest-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create a valid plugin JAR with manifest metadata
            val jarFile = createTestPluginJar(tempDir, "manifest-plugin.jar", useProperties = false)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.loadPlugin(jarFile)
                
                result.shouldBeInstanceOf<PluginLoadResult.Success>()
                result.plugin.metadata.id shouldBe "manifest-plugin"
                result.plugin.metadata.mainClass shouldBe "com.example.TestPlugin"
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should fail to load plugin without metadata") {
            val tempDir = Files.createTempDirectory("no-metadata-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create JAR without plugin metadata
            val jarFile = File(tempDir, "no-metadata.jar")
            JarOutputStream(jarFile.outputStream()).use { jos ->
                jos.putNextEntry(ZipEntry("dummy.txt"))
                jos.write("dummy content".toByteArray())
                jos.closeEntry()
            }
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.loadPlugin(jarFile)
                
                result.shouldBeInstanceOf<PluginLoadResult.Failure>()
                result.error shouldContain "No plugin metadata found"
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should fail to load plugin that doesn't implement Plugin interface") {
            val tempDir = Files.createTempDirectory("invalid-plugin-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create plugin with invalid main class
            val jarFile = createTestPluginJar(
                tempDir, 
                "invalid-plugin.jar", 
                useProperties = true,
                mainClass = "java.lang.String" // Not a Plugin implementation
            )
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.loadPlugin(jarFile)
                
                result.shouldBeInstanceOf<PluginLoadResult.Failure>()
                result.error shouldContain "does not implement Plugin interface"
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should prevent loading plugin with duplicate ID") {
            val tempDir = Files.createTempDirectory("duplicate-plugin-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile1 = createTestPluginJar(tempDir, "plugin1.jar", useProperties = true)
            val jarFile2 = createTestPluginJar(tempDir, "plugin2.jar", useProperties = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                val result1 = manager.loadPlugin(jarFile1)
                val result2 = manager.loadPlugin(jarFile2)
                
                result1.shouldBeInstanceOf<PluginLoadResult.Success>()
                result2.shouldBeInstanceOf<PluginLoadResult.Failure>()
                result2.error shouldContain "is already loaded"
            }
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginManager Plugin Unloading") {
        
        it("should unload plugin successfully") {
            val tempDir = Files.createTempDirectory("unload-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile = createTestPluginJar(tempDir, "test-plugin.jar", useProperties = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // Load plugin
                val loadResult = manager.loadPlugin(jarFile)
                loadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                manager.getPlugin("test-plugin") shouldNotBe null
                manager.getPluginState("test-plugin") shouldBe PluginState.LOADED
                
                // Unload plugin
                val unloadResult = manager.unloadPlugin("test-plugin")
                unloadResult shouldBe true
                
                manager.getPlugin("test-plugin") shouldBe null
                manager.getPluginState("test-plugin") shouldBe PluginState.UNLOADED
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle unloading non-existent plugin") {
            val tempDir = Files.createTempDirectory("unload-nonexistent-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.unloadPlugin("non-existent-plugin")
                result shouldBe false
            }
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginManager Plugin Queries") {
        
        it("should return all loaded plugins") {
            val tempDir = Files.createTempDirectory("query-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile1 = createTestPluginJar(tempDir, "plugin1.jar", useProperties = true, pluginId = "plugin1")
            val jarFile2 = createTestPluginJar(tempDir, "plugin2.jar", useProperties = true, pluginId = "plugin2")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                manager.loadPlugin(jarFile1)
                manager.loadPlugin(jarFile2)
                
                val allPlugins = manager.getAllPlugins()
                allPlugins shouldHaveSize 2
                allPlugins.map { it.metadata.id } shouldContain "plugin1"
                allPlugins.map { it.metadata.id } shouldContain "plugin2"
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should return plugin statistics") {
            val tempDir = Files.createTempDirectory("stats-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile = createTestPluginJar(tempDir, "stats-plugin.jar", useProperties = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                manager.loadPlugin(jarFile)
                
                val stats = manager.getPluginStats()
                stats.totalPlugins shouldBe 1
                stats.loadedPlugins shouldBe 1
                stats.errorPlugins shouldBe 0
                stats.plugins shouldHaveSize 1
                stats.plugins[0].id shouldBe "test-plugin"
                stats.plugins[0].state shouldBe PluginState.LOADED
            }
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginManager Plugin Reload") {
        
        it("should reload plugin successfully") {
            val tempDir = Files.createTempDirectory("reload-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile = createTestPluginJar(tempDir, "reload-plugin.jar", useProperties = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                // Load plugin initially
                val loadResult = manager.loadPlugin(jarFile)
                loadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                // Reload plugin
                val reloadResult = manager.reloadPlugin("test-plugin")
                reloadResult.shouldBeInstanceOf<PluginLoadResult.Success>()
                
                manager.getPlugin("test-plugin") shouldNotBe null
                manager.getPluginState("test-plugin") shouldBe PluginState.LOADED
            }
            
            tempDir.deleteRecursively()
        }
        
        it("should fail to reload non-existent plugin") {
            val tempDir = Files.createTempDirectory("reload-fail-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                val result = manager.reloadPlugin("non-existent-plugin")
                result.shouldBeInstanceOf<PluginLoadResult.Failure>()
                result.error shouldContain "Plugin not found"
            }
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginManager Shutdown") {
        
        it("should shutdown and unload all plugins") {
            val tempDir = Files.createTempDirectory("shutdown-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val jarFile1 = createTestPluginJar(tempDir, "plugin1.jar", useProperties = true, pluginId = "plugin1")
            val jarFile2 = createTestPluginJar(tempDir, "plugin2.jar", useProperties = true, pluginId = "plugin2")
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            runBlocking {
                manager.loadPlugin(jarFile1)
                manager.loadPlugin(jarFile2)
                
                manager.getAllPlugins() shouldHaveSize 2
                
                manager.shutdown()
                
                manager.getAllPlugins() shouldHaveSize 0
                manager.getPluginState("plugin1") shouldBe PluginState.UNLOADED
                manager.getPluginState("plugin2") shouldBe PluginState.UNLOADED
            }
            
            tempDir.deleteRecursively()
        }
    }
})

// Helper function to create test plugin JAR files
private fun createTestPluginJar(
    directory: File,
    fileName: String,
    useProperties: Boolean = true,
    pluginId: String = "test-plugin",
    mainClass: String = "com.example.TestPlugin"
): File {
    val jarFile = File(directory, fileName)
    
    JarOutputStream(jarFile.outputStream()).use { jos ->
        if (useProperties) {
            // Add plugin.properties
            jos.putNextEntry(ZipEntry("plugin.properties"))
            val properties = """
                plugin.id=$pluginId
                plugin.name=Test Plugin
                plugin.version=1.0.0
                plugin.description=A test plugin
                plugin.author=Test Author
                plugin.main-class=$mainClass
            """.trimIndent()
            jos.write(properties.toByteArray())
            jos.closeEntry()
        } else {
            // Add manifest with plugin metadata
            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.mainAttributes[Attributes.Name("Plugin-Id")] = pluginId
            manifest.mainAttributes[Attributes.Name("Plugin-Name")] = "Test Plugin"
            manifest.mainAttributes[Attributes.Name("Plugin-Version")] = "1.0.0"
            manifest.mainAttributes[Attributes.Name("Plugin-Description")] = "A test plugin"
            manifest.mainAttributes[Attributes.Name("Plugin-Author")] = "Test Author"
            manifest.mainAttributes[Attributes.Name("Plugin-Main-Class")] = mainClass
            
            jos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            manifest.write(jos)
            jos.closeEntry()
        }
        
        // Add a dummy class file (we can't load it, but metadata parsing works)
        jos.putNextEntry(ZipEntry("com/example/TestPlugin.class"))
        jos.write("dummy class content".toByteArray())
        jos.closeEntry()
    }
    
    return jarFile
}