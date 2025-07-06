package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlin.script.experimental.api.CompiledScript
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import java.io.File
import java.nio.file.Files

class DslSystemBasicTest : DescribeSpec({

    describe("DslCompilationContext") {
        
        it("should create default compilation context") {
            val context = DslCompilationContext()
            
            context.classPath shouldBe emptyList<File>()
            context.imports shouldBe emptySet<String>()
            context.configuration shouldBe emptyMap<String, Any>()
            context.allowedPackages shouldBe emptySet<String>()
            context.blockedPackages shouldBe emptySet<String>()
            context.enableCaching shouldBe true
            context.securityPolicy shouldNotBe null
        }
        
        it("should create compilation context with custom settings") {
            val customClassPath = listOf(File("/custom/path"))
            val customImports = setOf("kotlin.collections.*")
            val customConfig = mapOf("debug" to true)
            
            val context = DslCompilationContext(
                classPath = customClassPath,
                imports = customImports,
                configuration = customConfig,
                enableCaching = false
            )
            
            context.classPath shouldBe customClassPath
            context.imports shouldBe customImports
            context.configuration shouldBe customConfig
            context.enableCaching shouldBe false
        }
    }
    
    describe("DslExecutionContext") {
        
        it("should create default execution context") {
            val context = DslExecutionContext()
            
            context.variables shouldBe emptyMap<String, Any>()
            context.workingDirectory shouldBe File(System.getProperty("user.dir"))
            context.environmentVariables shouldBe emptyMap<String, String>()
            context.timeout shouldBe null
            context.resourceLimits shouldBe null
            context.executionPolicy shouldNotBe null
        }
        
        it("should create execution context with custom settings") {
            val customWorkDir = Files.createTempDirectory("test").toFile()
            val customVars = mapOf("TEST_VAR" to "test_value")
            val customEnvVars = mapOf("PATH" to "/custom/path")
            val customLimits = DslResourceLimits(maxMemoryMb = 512)
            
            val context = DslExecutionContext(
                variables = customVars,
                workingDirectory = customWorkDir,
                environmentVariables = customEnvVars,
                timeout = 30000L,
                resourceLimits = customLimits
            )
            
            context.variables shouldBe customVars
            context.workingDirectory shouldBe customWorkDir
            context.environmentVariables shouldBe customEnvVars
            context.timeout shouldBe 30000L
            context.resourceLimits shouldBe customLimits
            
            customWorkDir.deleteRecursively()
        }
    }
    
    describe("DslSecurityPolicy") {
        
        it("should use default security policy") {
            val policy = DslSecurityPolicy.DEFAULT
            
            policy.allowNetworkAccess shouldBe false
            policy.allowFileSystemAccess shouldBe true
            policy.allowedDirectories shouldBe emptySet<File>()
            policy.allowReflection shouldBe false
            policy.allowNativeCode shouldBe false
            policy.sandboxEnabled shouldBe true
        }
        
        it("should use restricted security policy") {
            val policy = DslSecurityPolicy.RESTRICTED
            
            policy.allowNetworkAccess shouldBe false
            policy.allowFileSystemAccess shouldBe false
            policy.allowReflection shouldBe false
            policy.allowNativeCode shouldBe false
            policy.sandboxEnabled shouldBe true
        }
        
        it("should use permissive security policy") {
            val policy = DslSecurityPolicy.PERMISSIVE
            
            policy.allowNetworkAccess shouldBe true
            policy.allowFileSystemAccess shouldBe true
            policy.allowReflection shouldBe true
            policy.allowNativeCode shouldBe false
            policy.sandboxEnabled shouldBe false
        }
        
        it("should create custom security policy") {
            val allowedDirs = setOf(File("/allowed/dir"))
            
            val policy = DslSecurityPolicy(
                allowNetworkAccess = true,
                allowFileSystemAccess = true,
                allowedDirectories = allowedDirs,
                allowReflection = false,
                allowNativeCode = false,
                sandboxEnabled = true
            )
            
            policy.allowNetworkAccess shouldBe true
            policy.allowFileSystemAccess shouldBe true
            policy.allowedDirectories shouldBe allowedDirs
            policy.allowReflection shouldBe false
            policy.allowNativeCode shouldBe false
            policy.sandboxEnabled shouldBe true
        }
    }
    
    describe("DslResourceLimits") {
        
        it("should create default resource limits") {
            val limits = DslResourceLimits()
            
            limits.maxMemoryMb shouldBe null
            limits.maxCpuTimeMs shouldBe null
            limits.maxWallTimeMs shouldBe null
            limits.maxThreads shouldBe null
            limits.maxFileHandles shouldBe null
        }
        
        it("should create custom resource limits") {
            val limits = DslResourceLimits(
                maxMemoryMb = 1024,
                maxCpuTimeMs = 60000L,
                maxWallTimeMs = 120000L,
                maxThreads = 4,
                maxFileHandles = 100
            )
            
            limits.maxMemoryMb shouldBe 1024
            limits.maxCpuTimeMs shouldBe 60000L
            limits.maxWallTimeMs shouldBe 120000L
            limits.maxThreads shouldBe 4
            limits.maxFileHandles shouldBe 100
        }
    }
    
    describe("DslExecutionPolicy") {
        
        it("should create default execution policy") {
            val policy = DslExecutionPolicy()
            
            policy.isolationLevel shouldBe DslIsolationLevel.THREAD
            policy.allowConcurrentExecution shouldBe true
            policy.persistResults shouldBe false
            policy.enableEventPublishing shouldBe true
        }
        
        it("should create custom execution policy") {
            val policy = DslExecutionPolicy(
                isolationLevel = DslIsolationLevel.PROCESS,
                allowConcurrentExecution = false,
                persistResults = true,
                enableEventPublishing = false
            )
            
            policy.isolationLevel shouldBe DslIsolationLevel.PROCESS
            policy.allowConcurrentExecution shouldBe false
            policy.persistResults shouldBe true
            policy.enableEventPublishing shouldBe false
        }
    }
    
    describe("DslCompilationResult") {
        
        it("should create successful compilation result") {
            val mockCompiledScript = mockk<CompiledScript>()
            val metadata = DslCompilationMetadata(
                compilationTimeMs = 100L,
                cacheHit = false,
                dependenciesResolved = 5,
                warningsCount = 1
            )
            
            val result: DslCompilationResult.Success<String> = DslCompilationResult.Success(mockCompiledScript, metadata)
            
            result.shouldBeInstanceOf<DslCompilationResult.Success<Any>>()
            result.compiledScript shouldBe mockCompiledScript
            result.metadata shouldBe metadata
        }
        
        it("should create failed compilation result") {
            val errors = listOf(
                DslError("SYNTAX_ERROR", "Missing semicolon", DslLocation(1, 10))
            )
            val warnings = listOf(
                DslWarning("UNUSED_VAR", "Variable 'x' is not used", DslLocation(2, 5))
            )
            
            val result = DslCompilationResult.Failure(errors, warnings)
            
            result.shouldBeInstanceOf<DslCompilationResult.Failure>()
            result.errors shouldBe errors
            result.warnings shouldBe warnings
        }
    }
    
    describe("DslExecutionResult") {
        
        it("should create successful execution result") {
            val resultValue = "Success!"
            val metadata = DslExecutionMetadata(
                executionTimeMs = 50L,
                memoryUsedMb = 64L,
                threadsUsed = 1,
                eventsPublished = 3
            )
            
            val result = DslExecutionResult.Success(resultValue, metadata)
            
            result.shouldBeInstanceOf<DslExecutionResult.Success<String>>()
            result.result shouldBe resultValue
            result.metadata shouldBe metadata
        }
        
        it("should create failed execution result") {
            val error = DslError("RUNTIME_ERROR", "NullPointerException occurred")
            val metadata = DslExecutionMetadata(executionTimeMs = 25L)
            
            val result = DslExecutionResult.Failure(error, metadata)
            
            result.shouldBeInstanceOf<DslExecutionResult.Failure>()
            result.error shouldBe error
            result.metadata shouldBe metadata
        }
    }
    
    describe("DslValidationResult") {
        
        it("should create valid validation result") {
            val result = DslValidationResult.Valid
            
            result.shouldBeInstanceOf<DslValidationResult.Valid>()
        }
        
        it("should create invalid validation result") {
            val errors = listOf(
                DslError("INVALID_SYNTAX", "Unexpected token")
            )
            val warnings = listOf(
                DslWarning("STYLE_WARNING", "Consider using camelCase")
            )
            
            val result = DslValidationResult.Invalid(errors, warnings)
            
            result.shouldBeInstanceOf<DslValidationResult.Invalid>()
            result.errors shouldBe errors
            result.warnings shouldBe warnings
        }
    }
    
    describe("DslEngineInfo") {
        
        it("should create engine info") {
            val engineInfo = DslEngineInfo(
                engineId = "kotlin-dsl",
                engineName = "Kotlin DSL Engine",
                version = "1.0.0",
                description = "Executes Kotlin DSL scripts",
                author = "Pipeline Team",
                supportedExtensions = setOf("kts", "kotlin"),
                capabilities = setOf(DslCapability.COMPILATION_CACHING, DslCapability.SYNTAX_VALIDATION),
                dependencies = listOf("kotlin-compiler", "kotlin-script-runtime")
            )
            
            engineInfo.engineId shouldBe "kotlin-dsl"
            engineInfo.engineName shouldBe "Kotlin DSL Engine"
            engineInfo.version shouldBe "1.0.0"
            engineInfo.description shouldBe "Executes Kotlin DSL scripts"
            engineInfo.author shouldBe "Pipeline Team"
            engineInfo.supportedExtensions shouldContain "kts"
            engineInfo.supportedExtensions shouldContain "kotlin"
            engineInfo.capabilities shouldContain DslCapability.COMPILATION_CACHING
            engineInfo.capabilities shouldContain DslCapability.SYNTAX_VALIDATION
            engineInfo.dependencies shouldHaveSize 2
        }
    }
    
    describe("DslError and DslWarning") {
        
        it("should create DSL error with location") {
            val location = DslLocation(line = 5, column = 12, file = "test.kts")
            val error = DslError(
                code = "TYPE_ERROR",
                message = "Type mismatch: expected Int, found String",
                location = location,
                severity = DslSeverity.ERROR
            )
            
            error.code shouldBe "TYPE_ERROR"
            error.message shouldContain "Type mismatch"
            error.location shouldBe location
            error.severity shouldBe DslSeverity.ERROR
        }
        
        it("should create DSL warning") {
            val warning = DslWarning(
                code = "DEPRECATED_API",
                message = "This API is deprecated, use newApi() instead",
                location = DslLocation(3, 8),
                severity = DslSeverity.WARNING
            )
            
            warning.code shouldBe "DEPRECATED_API"
            warning.message shouldContain "deprecated"
            warning.location?.line shouldBe 3
            warning.location?.column shouldBe 8
            warning.severity shouldBe DslSeverity.WARNING
        }
    }
    
    describe("DslExecutionStats") {
        
        it("should create execution statistics") {
            val startTime = java.time.Instant.now()
            val endTime = startTime.plusMillis(1000)
            
            val stats = DslExecutionStats(
                dslType = "pipeline",
                executionId = "exec-123",
                startTime = startTime,
                endTime = endTime,
                compilationTimeMs = 200L,
                executionTimeMs = 800L,
                memoryUsedMb = 128L,
                eventsPublished = 5,
                errorsCount = 0,
                warningsCount = 2
            )
            
            stats.dslType shouldBe "pipeline"
            stats.executionId shouldBe "exec-123"
            stats.startTime shouldBe startTime
            stats.endTime shouldBe endTime
            stats.compilationTimeMs shouldBe 200L
            stats.executionTimeMs shouldBe 800L
            stats.memoryUsedMb shouldBe 128L
            stats.eventsPublished shouldBe 5
            stats.errorsCount shouldBe 0
            stats.warningsCount shouldBe 2
        }
    }
})