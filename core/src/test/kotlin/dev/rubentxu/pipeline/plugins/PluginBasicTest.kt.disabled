package dev.rubentxu.pipeline.plugins

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.plugins.security.PluginSecurityPolicy
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import java.io.File
import java.nio.file.Files

class PluginBasicTest : DescribeSpec({

    describe("PluginClassLoader Factory Validation") {
        
        it("should validate JAR file existence") {
            val nonExistentJar = File("/nonexistent/path/test.jar")
            
            val exception = shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJar(
                    jarFile = nonExistentJar,
                    pluginId = "test-plugin"
                )
            }
            
            exception.message shouldContain "does not exist"
        }
        
        it("should validate JAR list is not empty") {
            val exception = shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJars(
                    jarFiles = emptyList(),
                    pluginId = "test-plugin"
                )
            }
            
            exception.message shouldContain "At least one JAR file is required"
        }
        
        it("should validate directory existence") {
            val nonExistentDir = File("/nonexistent/path/directory")
            
            val exception = shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromDirectory(
                    directory = nonExistentDir,
                    pluginId = "test-plugin"
                )
            }
            
            exception.message shouldContain "does not exist"
        }
    }
    
    describe("PluginManager Basic Setup") {
        
        it("should create plugin directory when it doesn't exist") {
            val tempDir = Files.createTempDirectory("plugin-test").toFile()
            val pluginDir = File(tempDir, "plugins")
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            pluginDir.exists() shouldBe false
            
            PluginManager(
                pluginDirectory = pluginDir,
                logger = mockLogger
            )
            
            pluginDir.exists() shouldBe true
            pluginDir.isDirectory shouldBe true
            
            verify { mockLogger.info("Created plugin directory: ${pluginDir.absolutePath}") }
            
            tempDir.deleteRecursively()
        }
        
        it("should handle non-existent plugin directory gracefully") {
            val nonExistentDir = File("/nonexistent/plugin/directory")
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = nonExistentDir,
                logger = mockLogger
            )
            
            // Should not throw exception, just log
            verify { mockLogger.info("Created plugin directory: ${nonExistentDir.absolutePath}") }
        }
        
        it("should return empty statistics for new manager") {
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
        
        it("should return null for non-existent plugin") {
            val tempDir = Files.createTempDirectory("get-plugin-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            val plugin = manager.getPlugin("non-existent-plugin")
            plugin shouldBe null
            
            tempDir.deleteRecursively()
        }
        
        it("should return empty list for getAllPlugins when no plugins loaded") {
            val tempDir = Files.createTempDirectory("get-all-plugins-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            val plugins = manager.getAllPlugins()
            plugins.shouldBeEmpty()
            
            tempDir.deleteRecursively()
        }
        
        it("should return unknown state for non-existent plugin") {
            val tempDir = Files.createTempDirectory("plugin-state-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            val state = manager.getPluginState("non-existent-plugin")
            state shouldBe null
            
            tempDir.deleteRecursively()
        }
        
        it("should format statistics correctly") {
            val tempDir = Files.createTempDirectory("format-stats-test").toFile()
            val mockLogger = mockk<IPipelineLogger>(relaxed = true)
            
            val manager = PluginManager(
                pluginDirectory = tempDir,
                logger = mockLogger
            )
            
            val stats = manager.getPluginStats()
            val formatted = stats.getFormattedStats()
            
            formatted shouldContain "Plugin Manager Statistics"
            formatted shouldContain "Total Plugins: 0"
            formatted shouldContain "Loaded: 0"
            formatted shouldContain "Errors: 0"
            
            tempDir.deleteRecursively()
        }
    }
    
    describe("PluginClassLoader Stats") {
        
        it("should provide accurate initial statistics") {
            val tempJar = Files.createTempFile("test", ".jar").toFile()
            
            // Create minimal JAR
            tempJar.writeBytes(byteArrayOf())
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "test-plugin"
            )
            
            val stats = classLoader.getStats()
            stats.pluginId shouldBe "test-plugin"
            stats.loadedClassCount shouldBe 0
            stats.loadedClasses.shouldBeEmpty()
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should format statistics correctly") {
            val tempJar = Files.createTempFile("format-test", ".jar").toFile()
            tempJar.writeBytes(byteArrayOf())
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "format-plugin"
            )
            
            val stats = classLoader.getStats()
            val formatted = stats.getFormattedStats()
            
            formatted shouldContain "Plugin ClassLoader Statistics for: format-plugin"
            formatted shouldContain "Loaded Classes: 0"
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should clear cache correctly") {
            val tempJar = Files.createTempFile("cache-test", ".jar").toFile()
            tempJar.writeBytes(byteArrayOf())
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "cache-plugin"
            )
            
            // Clear cache (should not throw)
            classLoader.clearCache()
            classLoader.getStats().loadedClassCount shouldBe 0
            
            classLoader.close()
            tempJar.delete()
        }
    }
})