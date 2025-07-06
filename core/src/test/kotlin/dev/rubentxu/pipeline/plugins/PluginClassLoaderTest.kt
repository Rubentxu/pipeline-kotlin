package dev.rubentxu.pipeline.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import java.io.File
import java.net.URL
import java.nio.file.Files

class PluginClassLoaderTest : DescribeSpec({
    
    describe("PluginClassLoader Security") {
        
        val testPluginId = "test-plugin"
        val tempDir = Files.createTempDirectory("plugin-test")
        
        // Create a simple test ClassLoader with basic URLs
        val testUrls = arrayOf<URL>()
        val pluginClassLoader = PluginClassLoader(
            urls = testUrls,
            pluginId = testPluginId,
            allowedPackages = PluginClassLoader.DEFAULT_ALLOWED_PACKAGES,
            blockedPackages = PluginClassLoader.DEFAULT_BLOCKED_PACKAGES
        )
        
        afterTest {
            pluginClassLoader.close()
        }
        
        it("should allow access to permitted packages") {
            // These should not throw exceptions during validation
            val allowedClasses = listOf(
                "java.lang.String",
                "java.util.List",
                "kotlin.String",
                "dev.rubentxu.pipeline.dsl.StepsBlock"
            )
            
            for (className in allowedClasses) {
                try {
                    // This tests the validation logic, actual class loading may fail
                    // due to missing classpath, but validation should pass
                    pluginClassLoader.loadClass(className)
                } catch (e: ClassNotFoundException) {
                    // Expected for classes not in classpath - validation passed
                } catch (e: SecurityException) {
                    throw AssertionError("Should allow access to $className", e)
                }
            }
        }
        
        it("should block access to restricted packages") {
            val blockedClasses = listOf(
                "java.lang.reflect.Method",
                "java.security.AccessController",
                "sun.misc.Unsafe",
                "dev.rubentxu.pipeline.compilation.CachedScriptEngine"
            )
            
            for (className in blockedClasses) {
                shouldThrow<SecurityException> {
                    pluginClassLoader.loadClass(className)
                }
            }
        }
        
        it("should block access to sensitive resources") {
            val sensitiveResources = listOf(
                "META-INF/services/java.security.Provider",
                "security/keystore.jks",
                "credentials/secret.key"
            )
            
            for (resource in sensitiveResources) {
                shouldThrow<SecurityException> {
                    pluginClassLoader.findResource(resource)
                }
            }
        }
        
        it("should provide accurate statistics") {
            val stats = pluginClassLoader.getStats()
            
            stats.pluginId shouldBe testPluginId
            stats.loadedClassCount shouldBe 0 // No classes loaded yet
            stats.urls shouldBe testUrls.toList()
            stats.loadedClasses.isEmpty() shouldBe true
        }
        
        it("should format statistics correctly") {
            val stats = pluginClassLoader.getStats()
            val formatted = stats.getFormattedStats()
            
            formatted shouldNotBe null
            formatted.contains(testPluginId) shouldBe true
            formatted.contains("Loaded Classes: 0") shouldBe true
        }
        
        it("should clear cache properly") {
            // This tests the cache clearing mechanism
            pluginClassLoader.clearCache()
            
            val stats = pluginClassLoader.getStats()
            stats.loadedClassCount shouldBe 0
        }
    }
    
    describe("PluginClassLoader Factory Methods") {
        
        val tempDir = Files.createTempDirectory("plugin-factory-test")
        val testPluginId = "factory-test-plugin"
        
        afterTest {
            // Cleanup temp files
            tempDir.toFile().deleteRecursively()
        }
        
        it("should create ClassLoader from JAR file") {
            // Create a dummy JAR file
            val jarFile = tempDir.resolve("test-plugin.jar").toFile()
            jarFile.createNewFile() // Empty file for testing
            
            val classLoader = PluginClassLoader.fromJar(jarFile, testPluginId)
            
            classLoader shouldNotBe null
            classLoader.getStats().pluginId shouldBe testPluginId
            classLoader.getStats().urls.size shouldBe 1
            
            classLoader.close()
        }
        
        it("should create ClassLoader from multiple JAR files") {
            // Create a temporary directory for this test
            val testTempDir = Files.createTempDirectory("plugin-jar-test")
            
            // Create dummy JAR files
            val jarFile1 = testTempDir.resolve("test-plugin1.jar").toFile()
            val jarFile2 = testTempDir.resolve("test-plugin2.jar").toFile()
            
            try {
                jarFile1.createNewFile()
                jarFile2.createNewFile()
            
                val classLoader = PluginClassLoader.fromJars(
                    listOf(jarFile1, jarFile2),
                    testPluginId
                )
                
                classLoader shouldNotBe null
                classLoader.getStats().pluginId shouldBe testPluginId
                classLoader.getStats().urls.size shouldBe 2
                
                classLoader.close()
            } finally {
                // Cleanup temporary files
                jarFile1.delete()
                jarFile2.delete()
                testTempDir.toFile().delete()
            }
        }
        
        it("should create ClassLoader from directory") {
            val directory = tempDir.resolve("plugin-classes").toFile()
            directory.mkdirs()
            
            val classLoader = PluginClassLoader.fromDirectory(directory, testPluginId)
            
            classLoader shouldNotBe null
            classLoader.getStats().pluginId shouldBe testPluginId
            classLoader.getStats().urls.size shouldBe 1
            
            classLoader.close()
        }
        
        it("should fail when JAR file doesn't exist") {
            val nonExistentFile = File("/path/that/does/not/exist/plugin.jar")
            
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJar(nonExistentFile, testPluginId)
            }
        }
        
        it("should fail when directory doesn't exist") {
            val nonExistentDir = File("/path/that/does/not/exist/")
            
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromDirectory(nonExistentDir, testPluginId)
            }
        }
        
        it("should fail with empty JAR list") {
            shouldThrow<IllegalArgumentException> {
                PluginClassLoader.fromJars(emptyList(), testPluginId)
            }
        }
    }
    
    describe("PluginClassLoader Package Validation") {
        
        val testPluginId = "validation-test"
        val customAllowedPackages = setOf(
            "java.lang.",
            "kotlin.",
            "com.example.allowed."
        )
        val customBlockedPackages = setOf(
            "java.security.",
            "com.example.blocked."
        )
        
        val classLoader = PluginClassLoader(
            urls = arrayOf(),
            pluginId = testPluginId,
            allowedPackages = customAllowedPackages,
            blockedPackages = customBlockedPackages
        )
        
        afterTest {
            classLoader.close()
        }
        
        it("should respect custom allowed packages") {
            // Should allow custom allowed package
            try {
                classLoader.loadClass("com.example.allowed.TestClass")
            } catch (e: ClassNotFoundException) {
                // Expected - class doesn't exist, but validation passed
            } catch (e: SecurityException) {
                throw AssertionError("Should allow access to custom allowed package", e)
            }
        }
        
        it("should respect custom blocked packages") {
            // Should block custom blocked package
            shouldThrow<SecurityException> {
                classLoader.loadClass("com.example.blocked.TestClass")
            }
        }
        
        it("should still block security packages even with custom config") {
            // Security packages should always be blocked
            shouldThrow<SecurityException> {
                classLoader.loadClass("java.security.AccessController")
            }
        }
    }
    
    describe("PluginClassLoader Resource Security") {
        
        val testPluginId = "resource-test"
        val classLoader = PluginClassLoader(
            urls = arrayOf(),
            pluginId = testPluginId
        )
        
        afterTest {
            classLoader.close()
        }
        
        it("should allow access to safe resources") {
            // These should not throw security exceptions
            val safeResources = listOf(
                "config.properties",
                "templates/email.html",
                "static/css/style.css"
            )
            
            for (resource in safeResources) {
                try {
                    classLoader.findResource(resource)
                    // No security exception means validation passed
                } catch (e: SecurityException) {
                    throw AssertionError("Should allow access to safe resource: $resource", e)
                }
            }
        }
        
        it("should block access to sensitive resources") {
            val sensitiveResources = listOf(
                "META-INF/services/java.security.Provider",
                "security/keystore.p12",
                "keys/private.key",
                "credentials/aws.json"
            )
            
            for (resource in sensitiveResources) {
                shouldThrow<SecurityException> {
                    classLoader.findResource(resource)
                }
            }
        }
    }
})