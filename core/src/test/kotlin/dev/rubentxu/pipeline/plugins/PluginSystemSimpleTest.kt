package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.plugins.security.PluginSecurityPolicy
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class PluginSystemSimpleTest : DescribeSpec({

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
        
        it("should discover JAR files in directory") {
            val tempDir = Files.createTempDirectory("plugin-discovery-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            // Create test JAR files (empty)
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
                
                // Should find 2 JAR files but fail to load them (no plugin metadata)
                results shouldHaveSize 2
                results.forEach { it.shouldBeInstanceOf<PluginLoadResult.Failure>() }
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
        
        it("should return plugin statistics") {
            val tempDir = Files.createTempDirectory("stats-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                securityPolicy = PluginSecurityPolicy.PERMISSIVE,
                logger = mockLogger
            )
            
            val stats = manager.getPluginStats()
            stats.totalPlugins shouldBe 0
            stats.loadedPlugins shouldBe 0
            stats.errorPlugins shouldBe 0
            stats.plugins.shouldBeEmpty()
            
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
        
        it("should shutdown cleanly when no plugins loaded") {
            val tempDir = Files.createTempDirectory("shutdown-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            runBlocking {
                manager.shutdown()
                manager.getAllPlugins().shouldBeEmpty()
            }
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginClassLoader Factory Operations") {
        
        it("should fail to create classloader from non-existent JAR") {
            val nonExistentJar = File("/nonexistent/path/test.jar")
            
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJar(
                    jarFile = nonExistentJar,
                    pluginId = "test-plugin"
                )
            }
        }
        
        it("should fail to create classloader from empty JAR list") {
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJars(
                    jarFiles = emptyList(),
                    pluginId = "test-plugin"
                )
            }
        }
        
        it("should fail to create classloader from non-existent directory") {
            val nonExistentDir = File("/nonexistent/path/directory")
            
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromDirectory(
                    directory = nonExistentDir,
                    pluginId = "test-plugin"
                )
            }
        }
        
        it("should create classloader from valid JAR file") {
            val tempJar = Files.createTempFile("test", ".jar").toFile()
            createSimpleJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "test-plugin"
            )
            
            val stats = classLoader.getStats()
            stats.pluginId shouldBe "test-plugin"
            stats.urls shouldHaveSize 1
            stats.loadedClassCount shouldBe 0
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should create classloader from directory") {
            val tempDir = Files.createTempDirectory("test-classes").toFile()
            
            val classLoader = PluginClassLoader.fromDirectory(
                directory = tempDir,
                pluginId = "dir-plugin"
            )
            
            val stats = classLoader.getStats()
            stats.pluginId shouldBe "dir-plugin"
            stats.urls shouldHaveSize 1
            
            classLoader.close()
            tempDir.deleteRecursively()
        }
        
        it("should clear class cache") {
            val tempJar = Files.createTempFile("cache-test", ".jar").toFile()
            createSimpleJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "cache-plugin"
            )
            
            // Load a system class
            classLoader.loadClass("java.lang.String")
            classLoader.getStats().loadedClassCount shouldBe 1
            
            // Clear cache
            classLoader.clearCache()
            classLoader.getStats().loadedClassCount shouldBe 0
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should provide formatted statistics") {
            val tempJar = Files.createTempFile("format-test", ".jar").toFile()
            createSimpleJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "format-plugin"
            )
            
            classLoader.loadClass("java.lang.String")
            
            val stats = classLoader.getStats()
            val formatted = stats.getFormattedStats()
            
            formatted shouldContain "Plugin ClassLoader Statistics for: format-plugin"
            formatted shouldContain "Loaded Classes: 1"
            formatted shouldContain "URLs: 1"
            formatted shouldContain "java.lang.String"
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should block access to sensitive resources") {
            val tempJar = Files.createTempFile("resource-test", ".jar").toFile()
            createSimpleJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "resource-plugin"
            )
            
            // Should block access to sensitive resources
            shouldThrow<SecurityException> {
                classLoader.findResource("security/keystore.jks")
            }
            
            shouldThrow<SecurityException> {
                classLoader.findResource("credentials/api.key")
            }
            
            classLoader.close()
            tempJar.delete()
        }
    }
})

// Helper function to create a simple test JAR file
private fun createSimpleJar(jarFile: File) {
    JarOutputStream(jarFile.outputStream()).use { jos ->
        jos.putNextEntry(ZipEntry("test.properties"))
        jos.write("test.property=value".toByteArray())
        jos.closeEntry()
    }
}