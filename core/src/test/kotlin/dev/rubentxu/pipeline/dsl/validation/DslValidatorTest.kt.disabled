package dev.rubentxu.pipeline.dsl.validation

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.security.SandboxManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/**
 * Comprehensive tests for the enhanced DSL validation system
 */
class DslValidatorTest : StringSpec({
    
    "should validate empty script and report error" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val report = validator.validateScript(
                    scriptContent = "",
                    scriptName = "empty.kts"
                )
                
                report.isValid shouldBe false
                report.issues.shouldNotBeEmpty()
                report.issues.any { it.code == "EMPTY_SCRIPT" } shouldBe true
                report.errorCount shouldBe 1
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should detect unmatched braces" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val scriptWithUnmatchedBraces = """
                    pipeline {
                        stage("test") {
                            steps {
                                echo "hello"
                            // Missing closing brace
                    }
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = scriptWithUnmatchedBraces,
                    scriptName = "unmatched-braces.kts"
                )
                
                report.isValid shouldBe false
                report.issues.any { it.code == "UNMATCHED_BRACES" } shouldBe true
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should detect dangerous API usage" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val dangerousScript = """
                    import java.lang.Runtime
                    
                    pipeline {
                        stage("dangerous") {
                            steps {
                                Runtime.getRuntime().exec("rm -rf /")
                                System.exit(1)
                            }
                        }
                    }
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = dangerousScript,
                    scriptName = "dangerous.kts"
                )
                
                report.isValid shouldBe false
                report.issues.any { it.code == "DANGEROUS_API_USAGE" } shouldBe true
                report.issues.count { it.code == "DANGEROUS_API_USAGE" } shouldBe 2 // Runtime and System.exit
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should detect network access violations" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val networkScript = """
                    import java.net.Socket
                    import java.net.URL
                    
                    pipeline {
                        stage("network") {
                            steps {
                                val socket = Socket("example.com", 80)
                                val url = URL("http://evil.com")
                            }
                        }
                    }
                """.trimIndent()
                
                val context = DslCompilationContext(
                    securityPolicy = DslSecurityPolicy.RESTRICTED // No network access
                )
                
                val report = validator.validateScript(
                    scriptContent = networkScript,
                    scriptName = "network.kts",
                    context = context
                )
                
                report.isValid shouldBe false
                report.issues.any { it.code == "NETWORK_ACCESS_DENIED" } shouldBe true
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should validate resource limits" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val executionContext = DslExecutionContext(
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 5000, // Too high
                        maxCpuTimeMs = 600_000, // Too high
                        maxThreads = -1 // Invalid
                    )
                )
                
                val report = validator.validateScript(
                    scriptContent = "println('hello')",
                    scriptName = "resource-test.kts",
                    executionContext = executionContext
                )
                
                report.issues.any { it.code == "EXCESSIVE_MEMORY_LIMIT" } shouldBe true
                report.issues.any { it.code == "EXCESSIVE_CPU_TIME" } shouldBe true
                report.issues.any { it.code == "INVALID_THREAD_LIMIT" } shouldBe true
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should detect missing pipeline block in pipeline scripts" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val scriptWithoutPipeline = """
                    val x = 1
                    val y = 2
                    println(x + y)
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = scriptWithoutPipeline,
                    scriptName = "no-pipeline.pipeline.kts" // .pipeline.kts extension
                )
                
                report.issues.any { it.code == "MISSING_PIPELINE_BLOCK" } shouldBe true
                report.issues.find { it.code == "MISSING_PIPELINE_BLOCK" }?.severity shouldBe DslValidationSeverity.WARNING
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should detect performance concerns" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val performanceScript = """
                    pipeline {
                        stage("performance") {
                            steps {
                                while(true) {
                                    Thread.sleep(1000)
                                }
                            }
                        }
                    }
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = performanceScript,
                    scriptName = "performance.kts"
                )
                
                report.warnings.any { it.code == "PERFORMANCE_CONCERN" } shouldBe true
                report.issues.any { it.code == "BLOCKING_SLEEP_DETECTED" } shouldBe true
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should provide helpful recommendations" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val problematicScript = """
                    import java.lang.Runtime
                    
                    pipeline {
                        stage("problematic") {
                            steps {
                                Runtime.getRuntime().exec("ls")
                                while(true) {
                                    Thread.sleep(100)
                                }
                            }
                        }
                    }
                """.trimIndent()
                
                val executionContext = DslExecutionContext(
                    resourceLimits = null // No resource limits
                )
                
                val report = validator.validateScript(
                    scriptContent = problematicScript,
                    scriptName = "problematic.kts",
                    executionContext = executionContext
                )
                
                report.recommendations.shouldNotBeEmpty()
                report.recommendations.any { it.type == DslRecommendationType.SECURITY } shouldBe true
                report.recommendations.any { it.type == DslRecommendationType.PERFORMANCE } shouldBe true
                report.recommendations.any { it.type == DslRecommendationType.CONFIGURATION } shouldBe true
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should handle valid scripts correctly" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val validScript = """
                    pipeline {
                        agent any
                        stages {
                            stage('Build') {
                                steps {
                                    echo 'Building the project'
                                    sh 'gradle build'
                                }
                            }
                            stage('Test') {
                                steps {
                                    echo 'Running tests'
                                    sh 'gradle test'
                                }
                            }
                        }
                    }
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = validScript,
                    scriptName = "valid.pipeline.kts"
                )
                
                report.isValid shouldBe true
                report.errorCount shouldBe 0
                report.issues.filter { it.severity == DslValidationSeverity.ERROR }.shouldBeEmpty()
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should generate formatted report correctly" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val scriptWithIssues = """
                    import java.lang.Runtime
                    
                    pipeline {
                        stage("test") {
                            steps {
                                Runtime.getRuntime().exec("echo hello"
                            // Missing closing parenthesis and brace
                    }
                """.trimIndent()
                
                val report = validator.validateScript(
                    scriptContent = scriptWithIssues,
                    scriptName = "test-report.kts"
                )
                
                val formattedReport = report.getFormattedReport()
                
                formattedReport shouldNotBe ""
                formattedReport.contains("DSL Validation Report") shouldBe true
                formattedReport.contains("test-report.kts") shouldBe true
                formattedReport.contains("Issues Found") shouldBe true
                formattedReport.contains("🚨") shouldBe true // Error icon
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should validate blocked package imports" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            val validator = DslValidator(sandboxManager, logger)
            
            try {
                val context = DslCompilationContext(
                    imports = setOf(
                        "java.lang.reflect.Method",
                        "sun.misc.Unsafe",
                        "kotlin.collections.*"
                    ),
                    blockedPackages = setOf(
                        "java.lang.reflect.*",
                        "sun.*"
                    )
                )
                
                val report = validator.validateScript(
                    scriptContent = "println('test')",
                    scriptName = "blocked-imports.kts",
                    context = context
                )
                
                report.issues.count { it.code == "BLOCKED_PACKAGE_IMPORT" } shouldBe 2 // reflect and sun packages
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
})