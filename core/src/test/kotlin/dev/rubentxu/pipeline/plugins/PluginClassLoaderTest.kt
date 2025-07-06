package dev.rubentxu.pipeline.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class PluginClassLoaderTest : DescribeSpec({
    
    describe("PluginClassLoader Factory Methods") {
        
        it("should create classloader from JAR file") {
            val tempJar = Files.createTempFile("test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "test-plugin"
            )
            
            classLoader.getStats().pluginId shouldBe "test-plugin"
            classLoader.getStats().urls shouldHaveSize 1
            classLoader.getStats().urls[0].toString() shouldContain tempJar.name
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should create classloader from multiple JAR files") {
            val tempJar1 = Files.createTempFile("test1", ".jar").toFile()
            val tempJar2 = Files.createTempFile("test2", ".jar").toFile()
            createTestJar(tempJar1)
            createTestJar(tempJar2)
            
            val classLoader = PluginClassLoader.fromJars(
                jarFiles = listOf(tempJar1, tempJar2),
                pluginId = "multi-jar-plugin"
            )
            
            classLoader.getStats().pluginId shouldBe "multi-jar-plugin"
            classLoader.getStats().urls shouldHaveSize 2
            
            classLoader.close()
            tempJar1.delete()
            tempJar2.delete()
        }
        
        it("should create classloader from directory") {
            val tempDir = Files.createTempDirectory("test-classes").toFile()
            
            val classLoader = PluginClassLoader.fromDirectory(
                directory = tempDir,
                pluginId = "dir-plugin"
            )
            
            classLoader.getStats().pluginId shouldBe "dir-plugin"
            classLoader.getStats().urls shouldHaveSize 1
            
            classLoader.close()
            tempDir.deleteRecursively()
        }
        
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
    }
    
    describe("PluginClassLoader Security Validation") {
        
        it("should allow access to allowed packages") {
            val tempJar = Files.createTempFile("security-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "security-plugin",
                allowedPackages = setOf("java.lang.", "java.util."),
                blockedPackages = emptySet()
            )
            
            // Should be able to load allowed classes
            shouldNotThrow {
                classLoader.loadClass("java.lang.String")
            }
            
            shouldNotThrow {
                classLoader.loadClass("java.util.ArrayList")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should block access to blocked packages") {
            val tempJar = Files.createTempFile("security-block-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "block-plugin",
                allowedPackages = setOf("java."),
                blockedPackages = setOf("java.security.", "java.lang.reflect.")
            )
            
            // Should block access to blocked packages
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.security.Permission")
            }
            
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.lang.reflect.Method")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should block access to non-allowed system classes") {
            val tempJar = Files.createTempFile("restricted-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "restricted-plugin",
                allowedPackages = setOf("java.lang."), // Only java.lang allowed
                blockedPackages = emptySet()
            )
            
            // Should allow java.lang classes
            shouldNotThrow {
                classLoader.loadClass("java.lang.String")
            }
            
            // Should block other system packages not in allowed list
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.net.URL")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should block access to sensitive resources") {
            val tempJar = Files.createTempFile("resource-test", ".jar").toFile()
            createTestJar(tempJar)
            
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
            
            shouldThrow<SecurityException> {
                classLoader.findResource("META-INF/services/java.security.Provider")
            }
            
            classLoader.close()
            tempJar.delete()
        }
    }
    
    describe("PluginClassLoader Class Loading Strategy") {
        
        it("should load system classes from parent first") {
            val tempJar = Files.createTempFile("strategy-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "strategy-plugin"
            )
            
            // System classes should be loaded from parent
            val stringClass = classLoader.loadClass("java.lang.String")
            stringClass shouldNotBe null
            stringClass.classLoader shouldNotBe classLoader // Should be system classloader
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should cache loaded classes") {
            val tempJar = Files.createTempFile("cache-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "cache-plugin"
            )
            
            // Load a class twice
            val class1 = classLoader.loadClass("java.lang.String")
            val class2 = classLoader.loadClass("java.lang.String")
            
            // Should be the same instance (cached)
            class1 shouldBe class2
            
            val stats = classLoader.getStats()
            stats.loadedClasses shouldContain "java.lang.String"
            stats.loadedClassCount shouldBe 1
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should clear class cache") {
            val tempJar = Files.createTempFile("clear-cache-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "clear-cache-plugin"
            )
            
            // Load a class
            classLoader.loadClass("java.lang.String")
            classLoader.getStats().loadedClassCount shouldBe 1
            
            // Clear cache
            classLoader.clearCache()
            classLoader.getStats().loadedClassCount shouldBe 0
            classLoader.getStats().loadedClasses.shouldBeEmpty()
            
            classLoader.close()
            tempJar.delete()
        }
    }
    
    describe("PluginClassLoader Statistics") {
        
        it("should provide accurate statistics") {
            val tempJar = Files.createTempFile("stats-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "stats-plugin"
            )
            
            // Initial stats
            val initialStats = classLoader.getStats()
            initialStats.pluginId shouldBe "stats-plugin"
            initialStats.loadedClassCount shouldBe 0
            initialStats.urls shouldHaveSize 1
            initialStats.loadedClasses.shouldBeEmpty()
            
            // Load some classes
            classLoader.loadClass("java.lang.String")
            classLoader.loadClass("java.lang.Integer")
            
            val afterLoadStats = classLoader.getStats()
            afterLoadStats.loadedClassCount shouldBe 2
            afterLoadStats.loadedClasses shouldContain "java.lang.String"
            afterLoadStats.loadedClasses shouldContain "java.lang.Integer"
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should format statistics correctly") {
            val tempJar = Files.createTempFile("format-test", ".jar").toFile()
            createTestJar(tempJar)
            
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
    }
    
    describe("PluginClassLoader Custom Package Configuration") {
        
        it("should use custom allowed packages") {
            val tempJar = Files.createTempFile("custom-allowed-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val customAllowed = setOf("java.lang.", "custom.package.")
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "custom-plugin",
                allowedPackages = customAllowed
            )
            
            // Should allow custom packages
            shouldNotThrow {
                classLoader.loadClass("java.lang.String")
            }
            
            // Should block packages not in custom allowed list
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.util.ArrayList")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should use custom blocked packages") {
            val tempJar = Files.createTempFile("custom-blocked-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val customBlocked = setOf("java.lang.String") // Very specific blocking
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "custom-block-plugin",
                allowedPackages = setOf("java."),
                blockedPackages = customBlocked
            )
            
            // Should block the custom blocked class
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.lang.String")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should handle overlapping allowed and blocked packages") {
            val tempJar = Files.createTempFile("overlap-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "overlap-plugin",
                allowedPackages = setOf("java."), // Allow all java packages
                blockedPackages = setOf("java.security.") // But block security
            )
            
            // Allowed package should work
            shouldNotThrow {
                classLoader.loadClass("java.lang.String")
            }
            
            // Blocked package should be blocked (blocked takes precedence)
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.security.Permission")
            }
            
            classLoader.close()
            tempJar.delete()
        }
    }
    
    describe("PluginClassLoader Error Handling") {
        
        it("should handle ClassNotFoundException gracefully") {
            val tempJar = Files.createTempFile("error-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "error-plugin"
            )
            
            shouldThrow<ClassNotFoundException> {
                classLoader.loadClass("com.nonexistent.NonExistentClass")
            }
            
            classLoader.close()
            tempJar.delete()
        }
        
        it("should close cleanly") {
            val tempJar = Files.createTempFile("close-test", ".jar").toFile()
            createTestJar(tempJar)
            
            val classLoader = PluginClassLoader.fromJar(
                jarFile = tempJar,
                pluginId = "close-plugin"
            )
            
            classLoader.loadClass("java.lang.String")
            classLoader.getStats().loadedClassCount shouldBe 1
            
            // Should close without throwing exceptions
            shouldNotThrow {
                classLoader.close()
            }
            
            tempJar.delete()
        }
    }
})

// Helper function to create a test JAR file
private fun createTestJar(jarFile: File) {
    JarOutputStream(jarFile.outputStream()).use { jos ->
        jos.putNextEntry(ZipEntry("test/TestClass.class"))
        jos.write("dummy class content".toByteArray())
        jos.closeEntry()
        
        jos.putNextEntry(ZipEntry("test.properties"))
        jos.write("test.property=value".toByteArray())
        jos.closeEntry()
    }
}

// Helper function to suppress exception throwing for allowed operations
private inline fun shouldNotThrow(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        throw AssertionError("Expected no exception, but got: ${e.javaClass.simpleName}: ${e.message}")
    }
}