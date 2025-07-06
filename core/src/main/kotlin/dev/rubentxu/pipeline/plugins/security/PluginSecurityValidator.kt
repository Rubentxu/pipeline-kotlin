package dev.rubentxu.pipeline.plugins.security

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.plugins.PluginMetadata
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.reflect.KClass

/**
 * Security validator for plugins that ensures plugins are safe to load and execute.
 * This includes checking digital signatures, analyzing bytecode, and validating
 * plugin metadata for potential security issues.
 */
class PluginSecurityValidator(
    private val logger: IPipelineLogger,
    private val securityPolicy: PluginSecurityPolicy = PluginSecurityPolicy.DEFAULT
) {
    
    companion object {
        private val DANGEROUS_CLASSES = setOf(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.io.FileOutputStream",
            "java.io.FileWriter",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.URL",
            "java.net.URLConnection",
            "java.security.AccessController",
            "sun.misc.Unsafe"
        )
        
        private val DANGEROUS_METHODS = setOf(
            "exec",
            "exit",
            "halt",
            "getRuntime",
            "doPrivileged",
            "setSecurityManager",
            "loadLibrary",
            "load"
        )
        
    }
    
    /**
     * Validates a plugin file for security compliance
     */
    suspend fun validatePlugin(
        pluginFile: File,
        expectedMetadata: PluginMetadata? = null
    ): PluginSecurityResult {
        
        logger.info("🔍 Validating plugin security: ${pluginFile.name}")
        
        val violations = mutableListOf<PluginSecurityViolation>()
        val warnings = mutableListOf<PluginSecurityWarning>()
        
        try {
            // 1. File existence and basic checks
            if (!pluginFile.exists()) {
                violations.add(PluginSecurityViolation(
                    type = PluginViolationType.FILE_NOT_FOUND,
                    message = "Plugin file not found: ${pluginFile.absolutePath}",
                    severity = PluginSecuritySeverity.CRITICAL
                ))
                return PluginSecurityResult(false, violations, warnings)
            }
            
            // 2. File size validation
            val fileSize = pluginFile.length()
            if (fileSize > securityPolicy.maxFileSizeBytes) {
                violations.add(PluginSecurityViolation(
                    type = PluginViolationType.EXCESSIVE_FILE_SIZE,
                    message = "Plugin file too large: ${fileSize / 1024 / 1024}MB > ${securityPolicy.maxFileSizeBytes / 1024 / 1024}MB",
                    severity = PluginSecuritySeverity.HIGH
                ))
            }
            
            // 3. File integrity validation
            if (securityPolicy.requireIntegrityCheck) {
                val integrityResult = validateFileIntegrity(pluginFile, expectedMetadata)
                if (integrityResult.violations.isNotEmpty()) {
                    violations.addAll(integrityResult.violations)
                }
            }
            
            // 4. JAR structure validation (if it's a JAR file)
            if (pluginFile.extension.lowercase() == "jar") {
                val jarValidation = validateJarStructure(pluginFile)
                violations.addAll(jarValidation.violations)
                warnings.addAll(jarValidation.warnings)
            }
            
            // 5. Digital signature validation
            if (securityPolicy.requireDigitalSignature) {
                val signatureResult = validateDigitalSignature(pluginFile)
                if (signatureResult.violations.isNotEmpty()) {
                    violations.addAll(signatureResult.violations)
                }
            }
            
            // 6. Bytecode analysis
            if (securityPolicy.enableBytecodeAnalysis) {
                val bytecodeResult = analyzeBytecode(pluginFile)
                violations.addAll(bytecodeResult.violations)
                warnings.addAll(bytecodeResult.warnings)
            }
            
            // 7. Metadata validation
            expectedMetadata?.let { metadata ->
                val metadataResult = validateMetadata(metadata)
                violations.addAll(metadataResult.violations)
                warnings.addAll(metadataResult.warnings)
            }
            
            // 8. Permissions validation
            val permissionsResult = validateRequiredPermissions(pluginFile)
            violations.addAll(permissionsResult.violations)
            warnings.addAll(permissionsResult.warnings)
            
        } catch (e: Exception) {
            logger.error("Error during plugin security validation: ${e.message}")
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.VALIDATION_ERROR,
                message = "Security validation failed: ${e.message}",
                severity = PluginSecuritySeverity.CRITICAL
            ))
        }
        
        val isSecure = violations.none { it.severity == PluginSecuritySeverity.CRITICAL || it.severity == PluginSecuritySeverity.HIGH }
        
        if (isSecure) {
            logger.info("✅ Plugin security validation passed: ${pluginFile.name}")
        } else {
            logger.warn("❌ Plugin security validation failed: ${pluginFile.name} (${violations.size} violations)")
        }
        
        return PluginSecurityResult(isSecure, violations, warnings)
    }
    
    /**
     * Validates plugin metadata for security issues
     */
    fun validatePluginMetadata(metadata: PluginMetadata): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        val warnings = mutableListOf<PluginSecurityWarning>()
        
        // Check plugin name
        if (metadata.name.contains("..") || metadata.name.contains("/") || metadata.name.contains("\\")) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.MALICIOUS_METADATA,
                message = "Plugin name contains path traversal characters: ${metadata.name}",
                severity = PluginSecuritySeverity.HIGH
            ))
        }
        
        // Check version format
        if (!isValidVersion(metadata.version)) {
            warnings.add(PluginSecurityWarning(
                type = PluginWarningType.INVALID_VERSION_FORMAT,
                message = "Plugin version format is invalid: ${metadata.version}"
            ))
        }
        
        // Check author information
        if (metadata.author.isBlank()) {
            warnings.add(PluginSecurityWarning(
                type = PluginWarningType.MISSING_AUTHOR_INFO,
                message = "Plugin author information is missing"
            ))
        }
        
        // Check for suspicious descriptions
        val suspiciousKeywords = listOf("hack", "exploit", "bypass", "admin", "root", "system")
        val descriptionLower = metadata.description.lowercase()
        suspiciousKeywords.forEach { keyword ->
            if (descriptionLower.contains(keyword)) {
                warnings.add(PluginSecurityWarning(
                    type = PluginWarningType.SUSPICIOUS_DESCRIPTION,
                    message = "Plugin description contains suspicious keyword: $keyword"
                ))
            }
        }
        
        val isSecure = violations.none { it.severity == PluginSecuritySeverity.CRITICAL || it.severity == PluginSecuritySeverity.HIGH }
        return PluginSecurityResult(isSecure, violations, warnings)
    }
    
    /**
     * Validates file integrity using checksums
     */
    private fun validateFileIntegrity(
        pluginFile: File,
        expectedMetadata: PluginMetadata?
    ): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        
        try {
            val actualChecksum = calculateFileChecksum(pluginFile)
            // For now, skip checksum validation as PluginMetadata doesn't have checksum field
            // This would be added when checksum support is implemented
            /*expectedMetadata?.checksum?.let { expectedChecksum ->
                if (actualChecksum != expectedChecksum) {
                    violations.add(PluginSecurityViolation(
                        type = PluginViolationType.INTEGRITY_CHECK_FAILED,
                        message = "File integrity check failed. Expected: $expectedChecksum, Actual: $actualChecksum",
                        severity = PluginSecuritySeverity.CRITICAL
                    ))
                }
            }*/
        } catch (e: Exception) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.INTEGRITY_CHECK_ERROR,
                message = "Failed to calculate file checksum: ${e.message}",
                severity = PluginSecuritySeverity.HIGH
            ))
        }
        
        return PluginSecurityResult(violations.isEmpty(), violations, emptyList())
    }
    
    /**
     * Validates JAR file structure and manifest
     */
    private fun validateJarStructure(pluginFile: File): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        val warnings = mutableListOf<PluginSecurityWarning>()
        
        try {
            JarFile(pluginFile).use { jarFile ->
                val manifest = jarFile.manifest
                
                // Check for manifest
                if (manifest == null) {
                    warnings.add(PluginSecurityWarning(
                        type = PluginWarningType.MISSING_MANIFEST,
                        message = "JAR file has no manifest"
                    ))
                } else {
                    validateManifest(manifest, violations, warnings)
                }
                
                // Check for suspicious entries
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    
                    // Check for path traversal attempts
                    if (entryName.contains("..") || entryName.startsWith("/")) {
                        violations.add(PluginSecurityViolation(
                            type = PluginViolationType.PATH_TRAVERSAL_ATTEMPT,
                            message = "Suspicious JAR entry: $entryName",
                            severity = PluginSecuritySeverity.HIGH
                        ))
                    }
                    
                    // Check for executable files
                    if (entryName.endsWith(".exe") || entryName.endsWith(".bat") || entryName.endsWith(".sh")) {
                        violations.add(PluginSecurityViolation(
                            type = PluginViolationType.EXECUTABLE_CONTENT,
                            message = "JAR contains executable file: $entryName",
                            severity = PluginSecuritySeverity.HIGH
                        ))
                    }
                    
                    // Check for native libraries
                    if (entryName.endsWith(".dll") || entryName.endsWith(".so") || entryName.endsWith(".dylib")) {
                        if (securityPolicy.allowNativeLibraries) {
                            warnings.add(PluginSecurityWarning(
                                type = PluginWarningType.NATIVE_LIBRARY_DETECTED,
                                message = "Native library detected: $entryName"
                            ))
                        } else {
                            violations.add(PluginSecurityViolation(
                                type = PluginViolationType.NATIVE_LIBRARY_PROHIBITED,
                                message = "Native library not allowed: $entryName",
                                severity = PluginSecuritySeverity.HIGH
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.JAR_STRUCTURE_ERROR,
                message = "Failed to validate JAR structure: ${e.message}",
                severity = PluginSecuritySeverity.MEDIUM
            ))
        }
        
        return PluginSecurityResult(violations.isEmpty(), violations, warnings)
    }
    
    /**
     * Validates JAR manifest for security issues
     */
    private fun validateManifest(
        manifest: Manifest,
        violations: MutableList<PluginSecurityViolation>,
        warnings: MutableList<PluginSecurityWarning>
    ) {
        val mainAttributes = manifest.mainAttributes
        
        // Check for suspicious permissions
        val permissions = mainAttributes.getValue("Permissions")
        if (permissions != null && permissions.contains("all-permissions")) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.EXCESSIVE_PERMISSIONS,
                message = "Plugin requests all permissions",
                severity = PluginSecuritySeverity.HIGH
            ))
        }
        
        // Check for code signing
        val codebase = mainAttributes.getValue("Codebase")
        if (codebase != null && !codebase.startsWith("https://")) {
            warnings.add(PluginSecurityWarning(
                type = PluginWarningType.INSECURE_CODEBASE,
                message = "Plugin codebase is not HTTPS: $codebase"
            ))
        }
    }
    
    /**
     * Validates digital signature of the plugin
     */
    private fun validateDigitalSignature(pluginFile: File): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        
        try {
            JarFile(pluginFile, true).use { jarFile ->
                val certificates = mutableSetOf<Certificate>()
                
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    
                    // Skip directories and signature files
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) {
                        continue
                    }
                    
                    // Read the entry to trigger certificate verification
                    jarFile.getInputStream(entry).use { input ->
                        input.readBytes()
                    }
                    
                    // Check certificates
                    entry.certificates?.let { certs ->
                        certificates.addAll(certs)
                    }
                }
                
                if (certificates.isEmpty()) {
                    violations.add(PluginSecurityViolation(
                        type = PluginViolationType.MISSING_SIGNATURE,
                        message = "Plugin is not digitally signed",
                        severity = PluginSecuritySeverity.HIGH
                    ))
                } else {
                    // Validate certificates
                    certificates.forEach { cert ->
                        if (!isValidCertificate(cert)) {
                            violations.add(PluginSecurityViolation(
                                type = PluginViolationType.INVALID_SIGNATURE,
                                message = "Plugin has invalid digital signature",
                                severity = PluginSecuritySeverity.HIGH
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.SIGNATURE_VERIFICATION_ERROR,
                message = "Failed to verify digital signature: ${e.message}",
                severity = PluginSecuritySeverity.MEDIUM
            ))
        }
        
        return PluginSecurityResult(violations.isEmpty(), violations, emptyList())
    }
    
    /**
     * Analyzes bytecode for suspicious patterns
     */
    private fun analyzeBytecode(pluginFile: File): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        val warnings = mutableListOf<PluginSecurityWarning>()
        
        try {
            JarFile(pluginFile).use { jarFile ->
                val entries = jarFile.entries()
                
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    
                    if (entry.name.endsWith(".class")) {
                        val classBytes = jarFile.getInputStream(entry).readBytes()
                        analyzeClassBytecode(classBytes, entry.name, violations, warnings)
                    }
                }
            }
        } catch (e: Exception) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.BYTECODE_ANALYSIS_ERROR,
                message = "Failed to analyze bytecode: ${e.message}",
                severity = PluginSecuritySeverity.MEDIUM
            ))
        }
        
        return PluginSecurityResult(violations.isEmpty(), violations, warnings)
    }
    
    /**
     * Analyzes individual class bytecode for dangerous patterns
     */
    private fun analyzeClassBytecode(
        classBytes: ByteArray,
        className: String,
        violations: MutableList<PluginSecurityViolation>,
        warnings: MutableList<PluginSecurityWarning>
    ) {
        // This is a simplified bytecode analysis
        // In a real implementation, you would use a bytecode analysis library like ASM
        
        val bytecodeString = String(classBytes, Charsets.ISO_8859_1)
        
        // Check for dangerous class references
        DANGEROUS_CLASSES.forEach { dangerousClass ->
            if (bytecodeString.contains(dangerousClass.replace(".", "/"))) {
                violations.add(PluginSecurityViolation(
                    type = PluginViolationType.DANGEROUS_API_USAGE,
                    message = "Class $className references dangerous API: $dangerousClass",
                    severity = PluginSecuritySeverity.HIGH
                ))
            }
        }
        
        // Check for dangerous method calls
        DANGEROUS_METHODS.forEach { dangerousMethod ->
            if (bytecodeString.contains(dangerousMethod)) {
                violations.add(PluginSecurityViolation(
                    type = PluginViolationType.DANGEROUS_METHOD_CALL,
                    message = "Class $className calls dangerous method: $dangerousMethod",
                    severity = PluginSecuritySeverity.HIGH
                ))
            }
        }
        
        // Check for reflection usage
        if (bytecodeString.contains("java/lang/reflect/")) {
            warnings.add(PluginSecurityWarning(
                type = PluginWarningType.REFLECTION_USAGE,
                message = "Class $className uses reflection"
            ))
        }
    }
    
    /**
     * Validates metadata for security compliance
     */
    private fun validateMetadata(metadata: PluginMetadata): PluginSecurityResult {
        return validatePluginMetadata(metadata)
    }
    
    /**
     * Validates required permissions
     */
    private fun validateRequiredPermissions(pluginFile: File): PluginSecurityResult {
        val violations = mutableListOf<PluginSecurityViolation>()
        val warnings = mutableListOf<PluginSecurityWarning>()
        
        // This would analyze what permissions the plugin actually needs
        // and compare with what's declared
        
        // For now, just check file access permissions
        if (!pluginFile.canRead()) {
            violations.add(PluginSecurityViolation(
                type = PluginViolationType.INSUFFICIENT_PERMISSIONS,
                message = "Cannot read plugin file: insufficient permissions",
                severity = PluginSecuritySeverity.MEDIUM
            ))
        }
        
        return PluginSecurityResult(violations.isEmpty(), violations, warnings)
    }
    
    // Helper methods
    
    private fun calculateFileChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun isValidCertificate(certificate: Certificate): Boolean {
        // Simplified certificate validation
        // In practice, you would check against trusted certificate authorities
        return try {
            certificate.verify(certificate.publicKey)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidVersion(version: String): Boolean {
        // Simple version format validation (e.g., 1.0.0, 2.1.3-SNAPSHOT)
        return version.matches(Regex("""^\d+\.\d+\.\d+(-\w+)?$"""))
    }
}

/**
 * Security policy for plugin validation
 */
data class PluginSecurityPolicy(
    val requireDigitalSignature: Boolean = false,
    val requireIntegrityCheck: Boolean = true,
    val enableBytecodeAnalysis: Boolean = true,
    val allowNativeLibraries: Boolean = false,
    val maxFileSizeBytes: Long = 50 * 1024 * 1024, // 50MB
    val trustedAuthors: Set<String> = emptySet(),
    val allowedPackages: Set<String> = setOf(
        "dev.rubentxu.pipeline.plugins",
        "kotlin",
        "kotlinx", 
        "java.lang",
        "java.util",
        "java.time"
    )
) {
    companion object {
        val DEFAULT = PluginSecurityPolicy()
        val STRICT = PluginSecurityPolicy(
            requireDigitalSignature = true,
            requireIntegrityCheck = true,
            enableBytecodeAnalysis = true,
            allowNativeLibraries = false,
            maxFileSizeBytes = 10 * 1024 * 1024 // 10MB
        )
        val PERMISSIVE = PluginSecurityPolicy(
            requireDigitalSignature = false,
            requireIntegrityCheck = false,
            enableBytecodeAnalysis = false,
            allowNativeLibraries = true,
            maxFileSizeBytes = 100 * 1024 * 1024 // 100MB
        )
    }
}

/**
 * Result of plugin security validation
 */
data class PluginSecurityResult(
    val isSecure: Boolean,
    val violations: List<PluginSecurityViolation>,
    val warnings: List<PluginSecurityWarning>
)

/**
 * Security violation detected in a plugin
 */
data class PluginSecurityViolation(
    val type: PluginViolationType,
    val message: String,
    val severity: PluginSecuritySeverity,
    val location: String? = null
)

/**
 * Security warning for a plugin
 */
data class PluginSecurityWarning(
    val type: PluginWarningType,
    val message: String,
    val location: String? = null
)

/**
 * Types of plugin security violations
 */
enum class PluginViolationType {
    FILE_NOT_FOUND,
    EXCESSIVE_FILE_SIZE,
    INTEGRITY_CHECK_FAILED,
    INTEGRITY_CHECK_ERROR,
    PATH_TRAVERSAL_ATTEMPT,
    EXECUTABLE_CONTENT,
    NATIVE_LIBRARY_PROHIBITED,
    JAR_STRUCTURE_ERROR,
    EXCESSIVE_PERMISSIONS,
    MISSING_SIGNATURE,
    INVALID_SIGNATURE,
    SIGNATURE_VERIFICATION_ERROR,
    DANGEROUS_API_USAGE,
    DANGEROUS_METHOD_CALL,
    MALICIOUS_METADATA,
    INSUFFICIENT_PERMISSIONS,
    BYTECODE_ANALYSIS_ERROR,
    VALIDATION_ERROR
}

/**
 * Types of plugin security warnings
 */
enum class PluginWarningType {
    MISSING_MANIFEST,
    INSECURE_CODEBASE,
    NATIVE_LIBRARY_DETECTED,
    REFLECTION_USAGE,
    INVALID_VERSION_FORMAT,
    MISSING_AUTHOR_INFO,
    SUSPICIOUS_DESCRIPTION
}

/**
 * Severity levels for security violations
 */
enum class PluginSecuritySeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}