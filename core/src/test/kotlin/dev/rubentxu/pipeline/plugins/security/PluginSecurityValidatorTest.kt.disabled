package dev.rubentxu.pipeline.plugins.security

import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.plugins.PluginMetadata
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * Tests for plugin security validation
 */
class PluginSecurityValidatorTest : StringSpec({
    
    fun createTestJar(name: String, includeManifest: Boolean = true, includeExecutable: Boolean = false): File {
        val tempFile = File.createTempFile(name, ".jar")
        tempFile.deleteOnExit()
        
        JarOutputStream(tempFile.outputStream()).use { jarOut ->
            if (includeManifest) {
                val manifest = Manifest()
                manifest.mainAttributes.putValue("Manifest-Version", "1.0")
                manifest.mainAttributes.putValue("Plugin-Main-Class", "com.example.TestPlugin")
                manifest.mainAttributes.putValue("Plugin-Id", "test-plugin")
                manifest.mainAttributes.putValue("Plugin-Version", "1.0.0")
                
                val manifestEntry = JarEntry("META-INF/MANIFEST.MF")
                jarOut.putNextEntry(manifestEntry)
                manifest.write(jarOut)
                jarOut.closeEntry()
            }
            
            // Add a simple class file
            val classEntry = JarEntry("com/example/TestPlugin.class")
            jarOut.putNextEntry(classEntry)
            jarOut.write(createSimpleClassBytes())
            jarOut.closeEntry()
            
            if (includeExecutable) {
                val exeEntry = JarEntry("malicious.exe")
                jarOut.putNextEntry(exeEntry)
                jarOut.write("fake executable content".toByteArray())
                jarOut.closeEntry()
            }
        }
        
        return tempFile
    }
    
    fun createSimpleClassBytes(): ByteArray {
        // This is a very simplified class file byte representation
        // In a real scenario, you'd use a proper bytecode library like ASM
        return byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), // Magic number
            0x00, 0x00, 0x00, 0x34, // Version
            0x00, 0x10 // Constant pool count
        ) + "TestPlugin".toByteArray()
    }
    
    fun createLargeFile(sizeInMB: Int): File {
        val tempFile = File.createTempFile("large", ".jar")
        tempFile.deleteOnExit()
        
        val content = ByteArray(1024 * 1024) // 1MB chunks
        tempFile.outputStream().use { out ->
            repeat(sizeInMB) {
                out.write(content)
            }
        }
        
        return tempFile
    }
    
    "should validate plugin metadata successfully" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val metadata = PluginMetadata(
                id = "test-plugin",
                version = "1.0.0",
                name = "Test Plugin",
                description = "A test plugin",
                author = "Test Author",
                mainClass = "com.example.TestPlugin"
            )
            
            val result = validator.validatePluginMetadata(metadata)
            
            result.isSecure shouldBe true
            result.violations.shouldBeEmpty()
        }
    }
    
    "should detect malicious metadata with path traversal" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val metadata = PluginMetadata(
                id = "test-plugin",
                version = "1.0.0",
                name = "../../../malicious",
                description = "A malicious plugin",
                author = "Evil Author",
                mainClass = "com.example.MaliciousPlugin"
            )
            
            val result = validator.validatePluginMetadata(metadata)
            
            result.isSecure shouldBe false
            result.violations.shouldNotBeEmpty()
            result.violations.any { it.type == PluginViolationType.MALICIOUS_METADATA } shouldBe true
        }
    }
    
    "should validate JAR file successfully with default policy" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val testJar = createTestJar("valid-plugin")
            
            val result = validator.validatePlugin(testJar)
            
            result.isSecure shouldBe true
            result.violations.shouldBeEmpty()
        }
    }
    
    "should detect executable files in JAR" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val testJar = createTestJar("malicious-plugin", includeExecutable = true)
            
            val result = validator.validatePlugin(testJar)
            
            result.isSecure shouldBe false
            result.violations.shouldNotBeEmpty()
            result.violations.any { it.type == PluginViolationType.EXECUTABLE_CONTENT } shouldBe true
        }
    }
    
    "should enforce file size limits" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val policy = PluginSecurityPolicy(maxFileSizeBytes = 1024 * 1024) // 1MB limit
            val validator = PluginSecurityValidator(logger, policy)
            
            val largeFile = createLargeFile(2) // 2MB file
            
            val result = validator.validatePlugin(largeFile)
            
            result.isSecure shouldBe false
            result.violations.shouldNotBeEmpty()
            result.violations.any { it.type == PluginViolationType.EXCESSIVE_FILE_SIZE } shouldBe true
        }
    }
    
    "should detect missing files" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val nonExistentFile = File("non-existent-plugin.jar")
            
            val result = validator.validatePlugin(nonExistentFile)
            
            result.isSecure shouldBe false
            result.violations.shouldNotBeEmpty()
            result.violations.any { it.type == PluginViolationType.FILE_NOT_FOUND } shouldBe true
        }
    }
    
    "should validate digital signatures when required" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val policy = PluginSecurityPolicy(requireDigitalSignature = true)
            val validator = PluginSecurityValidator(logger, policy)
            
            val testJar = createTestJar("unsigned-plugin")
            
            val result = validator.validatePlugin(testJar)
            
            result.isSecure shouldBe false
            result.violations.shouldNotBeEmpty()
            result.violations.any { it.type == PluginViolationType.MISSING_SIGNATURE } shouldBe true
        }
    }
    
    "should perform bytecode analysis when enabled" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val policy = PluginSecurityPolicy(enableBytecodeAnalysis = true)
            val validator = PluginSecurityValidator(logger, policy)
            
            val testJar = createTestJar("safe-plugin")
            
            val result = validator.validatePlugin(testJar)
            
            // Should complete analysis without errors
            result shouldNotBe null
        }
    }
    
    "should work with strict security policy" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger, PluginSecurityPolicy.STRICT)
            
            val testJar = createTestJar("strict-test")
            
            val result = validator.validatePlugin(testJar)
            
            // With strict policy, unsigned plugins should fail
            result.isSecure shouldBe false
            result.violations.any { it.type == PluginViolationType.MISSING_SIGNATURE } shouldBe true
        }
    }
    
    "should work with permissive security policy" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger, PluginSecurityPolicy.PERMISSIVE)
            
            val testJar = createTestJar("permissive-test")
            
            val result = validator.validatePlugin(testJar)
            
            // With permissive policy, most things should pass
            result.isSecure shouldBe true
        }
    }
    
    "should validate plugin with expected metadata" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val testJar = createTestJar("metadata-test")
            val expectedMetadata = PluginMetadata(
                id = "test-plugin",
                version = "1.0.0",
                name = "Test Plugin",
                description = "Test description",
                author = "Test Author",
                mainClass = "com.example.TestPlugin"
            )
            
            val result = validator.validatePlugin(testJar, expectedMetadata)
            
            result shouldNotBe null
            // Should complete validation even with expected metadata
        }
    }
    
    "should detect suspicious description keywords" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val metadata = PluginMetadata(
                id = "suspicious-plugin",
                version = "1.0.0",
                name = "Suspicious Plugin",
                description = "This plugin can hack the system and bypass security",
                author = "Hacker",
                mainClass = "com.example.HackerPlugin"
            )
            
            val result = validator.validatePluginMetadata(metadata)
            
            result.warnings.shouldNotBeEmpty()
            result.warnings.any { it.type == PluginWarningType.SUSPICIOUS_DESCRIPTION } shouldBe true
        }
    }
    
    "should validate version format" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val metadataWithInvalidVersion = PluginMetadata(
                id = "version-test",
                version = "invalid-version-format",
                name = "Version Test",
                description = "Test version validation",
                author = "Test Author",
                mainClass = "com.example.VersionTest"
            )
            
            val result = validator.validatePluginMetadata(metadataWithInvalidVersion)
            
            result.warnings.shouldNotBeEmpty()
            result.warnings.any { it.type == PluginWarningType.INVALID_VERSION_FORMAT } shouldBe true
        }
    }
    
    "should warn about missing author information" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val validator = PluginSecurityValidator(logger)
            
            val metadataWithoutAuthor = PluginMetadata(
                id = "no-author",
                version = "1.0.0",
                name = "No Author Plugin",
                description = "Plugin without author",
                author = "",
                mainClass = "com.example.NoAuthor"
            )
            
            val result = validator.validatePluginMetadata(metadataWithoutAuthor)
            
            result.warnings.shouldNotBeEmpty()
            result.warnings.any { it.type == PluginWarningType.MISSING_AUTHOR_INFO } shouldBe true
        }
    }
})

/**
 * Create simple Java class bytecode for testing
 */
private fun createSimpleClassBytes(): ByteArray {
    // Simple mock bytecode - just return a valid looking byte array for testing
    // This represents a minimal Java class file
    return "Mock Java Class Bytecode".toByteArray()
}