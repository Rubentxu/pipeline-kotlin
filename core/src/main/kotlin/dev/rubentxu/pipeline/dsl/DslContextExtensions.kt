package dev.rubentxu.pipeline.dsl

import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.*
import java.io.File

/**
 * Extensions for converting between DSL contexts and Kotlin Script configurations
 */

/**
 * Converts a DslCompilationContext to a Kotlin Script compilation configuration
 */
fun DslCompilationContext.toKotlinScriptConfig(): ScriptCompilationConfiguration {
    return ScriptCompilationConfiguration {
        
        // Dependencies - simplified approach
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        
        // Compiler options
        compilerOptions.append("-jvm-target", "17")
        
        // IDE configuration
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    }
}

/**
 * Converts a DslExecutionContext to a Kotlin Script evaluation configuration  
 */
fun DslExecutionContext.toKotlinScriptEvaluationConfig(): ScriptEvaluationConfiguration {
    return ScriptEvaluationConfiguration {
        
        // Working directory
        jvm {
            baseClassLoader(this@toKotlinScriptEvaluationConfig::class.java.classLoader)
        }
        
        // Environment variables can be passed as properties
        environmentVariables.forEach { (key, value) ->
            providedProperties(key to value)
        }
        
        // Constructor arguments if needed
        constructorArgs(emptyArray<Any>())
        
        // Evaluation timeout (if resource limits are specified)
        resourceLimits?.maxWallTimeMs?.let { timeoutMs ->
            // Note: Kotlin scripting doesn't have built-in timeout support
            // This would need to be implemented at a higher level
        }
    }
}

/**
 * Converts basic script information to a compilation context
 */
fun createBasicCompilationContext(
    dependencies: List<String> = emptyList(),
    repositories: List<String> = listOf("https://repo1.maven.org/maven2/")
): DslCompilationContext {
    return DslCompilationContext(
        classPath = emptyList(),
        imports = setOf(
            "kotlin.script.experimental.api.*",
            "kotlin.script.experimental.jvm.*"
        ),
        configuration = mapOf(
            "dependencies" to dependencies,
            "repositories" to repositories,
            "compilerOptions" to listOf("-jvm-target", "17")
        ),
        allowedPackages = setOf("kotlin.*", "java.lang.*", "java.util.*"),
        enableCaching = true,
        securityPolicy = DslSecurityPolicy.RESTRICTED
    )
}

/**
 * Creates a basic execution context with security defaults
 */
fun createSecureExecutionContext(
    workingDirectory: File = File(System.getProperty("user.dir")),
    maxMemoryMb: Int = 512,
    maxCpuTimeMs: Long = 30_000,
    maxWallTimeMs: Long = 60_000,
    isolationLevel: DslIsolationLevel = DslIsolationLevel.THREAD
): DslExecutionContext {
    return DslExecutionContext(
        workingDirectory = workingDirectory,
        environmentVariables = mapOf(
            "SANDBOX_MODE" to "true",
            "SECURITY_LEVEL" to isolationLevel.name
        ),
        resourceLimits = DslResourceLimits(
            maxMemoryMb = maxMemoryMb,
            maxCpuTimeMs = maxCpuTimeMs,
            maxWallTimeMs = maxWallTimeMs,
            maxThreads = 2
        ),
        executionPolicy = DslExecutionPolicy(
            isolationLevel = isolationLevel,
            allowConcurrentExecution = false,
            enableEventPublishing = true
        )
    )
}

/**
 * Creates a high-security execution context for untrusted scripts
 */
fun createHighSecurityExecutionContext(
    workingDirectory: File = File(System.getProperty("user.dir"))
): DslExecutionContext {
    return DslExecutionContext(
        workingDirectory = workingDirectory,
        environmentVariables = mapOf(
            "SANDBOX_MODE" to "strict",
            "SECURITY_LEVEL" to "PROCESS"
        ),
        resourceLimits = DslResourceLimits(
            maxMemoryMb = 256,     // Lower memory limit
            maxCpuTimeMs = 15_000, // Shorter CPU time
            maxWallTimeMs = 30_000, // Shorter wall time
            maxThreads = 1         // Single thread only
        ),
        executionPolicy = DslExecutionPolicy(
            isolationLevel = DslIsolationLevel.PROCESS,
            allowConcurrentExecution = false,
            enableEventPublishing = true
        )
    )
}

