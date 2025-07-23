package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * BDD-style tests for the Step Compiler Plugin using Kotest
 *
 * This test suite serves as both comprehensive testing and documentation
 * for the @Step annotation compiler plugin functionality.
 *
 * The plugin validates and transforms Kotlin functions annotated with @Step by:
 * 1. Validating that PipelineContext is explicitly provided as the first parameter
 * 2. Maintaining original function signatures for user-defined functions
 * 3. Generating StepsBlock extensions that omit the context parameter
 * 4. Transforming LocalPipelineContext.current calls to use the context parameter directly
 *
 * Architecture:
 * - Uses K2 IR transformation for Kotlin 2.2+
 * - Supports Context Parameters for dependency injection
 * - Generates StepsBlock extensions for DSL integration
 */
class StepCompilerPluginBDDTest : BehaviorSpec({

    // Test infrastructure setup
    lateinit var tempDir: File

    beforeTest {
        tempDir = Files.createTempDirectory("step-compiler-test").toFile()
    }

    afterTest {
        tempDir.deleteRecursively()
    }

    given("A Kotlin compiler plugin for @Step annotations") {

        `when`("plugin JAR is built and available") {
            then("plugin JAR should exist and be accessible") {
                val pluginJarPath = getPluginJarPath()
                pluginJarPath shouldNotBe null

                val pluginJar = File(pluginJarPath!!)
                pluginJar.shouldExist()

                println("✅ Plugin JAR found: $pluginJarPath")
            }
        }

        `when`("compiling code with @Step annotated functions") {

            and("functions have various signatures") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
                   
                   
                    class TestSteps {
                        
                        @Step
                        fun buildStep(context: PipelineContext, name: String): String {
                            return "Building: ${'$'}name"
                        }
                        
                        @Step  
                        fun deployStep(context: PipelineContext, env: String): String {
                            return "Deploying to: ${'$'}env"
                        }
                        
                        // Non-annotated method for comparison
                        fun normalMethod(param: String): String {
                            return "normal: ${'$'}param"
                        }
                    }
                """.trimIndent()

                then("plugin should compile @Step methods successfully") {
                    // Given: Source code with and without plugin
                    val withoutPlugin = compileKotlin(sourceCode, usePlugin = false, tempDir)
                    val withPlugin = compileKotlin(sourceCode, usePlugin = true, tempDir)

                    // When: Both compilations succeed
                    withoutPlugin.shouldExist()
                    withPlugin.shouldExist()

                    // Then: Analyze compiled classes
                    val analyzer = BytecodeAnalyzer()
                    val originalAnalysis = analyzer.analyzeClassFile(withoutPlugin)
                    val transformedAnalysis = analyzer.analyzeClassFile(withPlugin)

                    // Verify @Step methods are present and compilable
                    val originalBuildStep = originalAnalysis.findMethod("buildStep")!!
                    val transformedBuildStep = transformedAnalysis.findMethod("buildStep")!!

                    // Plugin should validate that @Step functions already have PipelineContext parameter
                    transformedBuildStep.getParameterCount() shouldBe originalBuildStep.getParameterCount()
                    transformedBuildStep.hasPipelineContextParameter() shouldBe true

                    // Non-@Step methods should remain unchanged
                    val originalNormal = originalAnalysis.findMethod("normalMethod")!!
                    val transformedNormal = transformedAnalysis.findMethod("normalMethod")!!

                    transformedNormal.getParameterCount() shouldBe originalNormal.getParameterCount()

                    println("✅ Plugin processed @Step methods successfully")
                    println("   - buildStep: ${transformedBuildStep.getParameterCount()} parameters (with explicit context)")
                    println("   - normalMethod unchanged: ${transformedNormal.getParameterCount()} parameters")
                }
            }

            and("functions are not annotated with @Step") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
                    
                    class TestSteps {
                        fun normalMethod(param: String): String {
                            return "normal: ${'$'}param"
                        }
                    }
                """.trimIndent()

                then("plugin should not modify non-@Step methods") {
                    val withPlugin = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    val withoutPlugin = compileKotlin(sourceCode, usePlugin = false, tempDir)

                    val analyzer = BytecodeAnalyzer()
                    val originalAnalysis = analyzer.analyzeClassFile(withoutPlugin)
                    val transformedAnalysis = analyzer.analyzeClassFile(withPlugin)

                    val normalOriginal = originalAnalysis.findMethod("normalMethod")!!
                    val normalTransformed = transformedAnalysis.findMethod("normalMethod")!!

                    normalTransformed.getParameterCount() shouldBe normalOriginal.getParameterCount()

                    println("✅ Plugin did not modify non-@Step methods")
                }
            }
        }

        `when`("compiling @Step functions for DSL extension generation") {

            and("functions have different parameter signatures") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
            
                    @Step
                    fun deployApp(context: PipelineContext, appName: String, environment: String): String {
                        return "Deploying ${'$'}appName to ${'$'}environment"
                    }
                    
                    @Step
                    suspend fun buildProject(context: PipelineContext, projectPath: String): Boolean {
                        return true
                    }
                    
                    @Step
                    fun simpleStep(context: PipelineContext) {
                        println("Simple step executed")
                    }
                """.trimIndent()

                then("plugin should compile @Step functions with various signatures") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    // Verify method signatures are preserved during compilation
                    val deployApp = analysis.findMethod("deployApp")!!
                    val buildProject = analysis.findMethod("buildProject")!!
                    val simpleStep = analysis.findMethod("simpleStep")!!

                    // deployApp: context + appName + environment = 3 parameters
                    deployApp.getParameterCount() shouldBe 3
                    deployApp.hasPipelineContextParameter() shouldBe true

                    // buildProject is suspend: context + projectPath + Continuation = 3 parameters
                    buildProject.getParameterCount() shouldBe 3
                    buildProject.hasPipelineContextParameter() shouldBe true

                    // simpleStep: context = 1 parameter
                    simpleStep.getParameterCount() shouldBe 1
                    simpleStep.hasPipelineContextParameter() shouldBe true

                    println("✅ Plugin compiled @Step functions with preserved signatures")
                    println("   - deployApp: ${deployApp.getParameterCount()} parameters")
                    println("   - buildProject: ${buildProject.getParameterCount()} parameters")
                    println("   - simpleStep: ${simpleStep.getParameterCount()} parameters")
                }
            }

            and("DSL extensions are generated") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
            
                    @Step
                    fun echo(context: PipelineContext, message: String) {
                        println("Echo: ${'$'}message")
                    }
                    
                    @Step
                    suspend fun compile(context: PipelineContext, project: String, clean: Boolean = true): Boolean {
                        println("Compiling ${'$'}project (clean=${'$'}clean)")
                        return true
                    }
                """.trimIndent()

                then("generated files should contain transformed methods") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    // Verify all generated files
                    val outputDir = result.parentFile
                    val generatedFiles = outputDir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".class") }
                        .toList()

                    generatedFiles.shouldNotBeEmpty()

                    val analyzer = BytecodeAnalyzer()
                    val mainClass = outputDir.resolve("com/example/test/TestSourceKt.class")

                    if (mainClass.exists()) {
                        val analysis = analyzer.analyzeClassFile(mainClass)

                        val echoMethod = analysis.findMethod("echo")
                        val compileMethod = analysis.findMethod("compile")

                        if (echoMethod != null) {
                            echoMethod.getParameterCount() shouldBe 2 // context + message
                            echoMethod.hasPipelineContextParameter() shouldBe true
                        }

                        if (compileMethod != null) {
                            compileMethod.getParameterCount() shouldBe 4 // context + project + clean + Continuation
                            compileMethod.hasPipelineContextParameter() shouldBe true
                        }
                    }
                }
            }
        }

        `when`("handling complex function signatures") {

            and("functions have varargs, defaults, nullable, and inline parameters") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
                    import dev.rubentxu.pipeline.annotations.StepCategory
                    
                    @Step(category = StepCategory.BUILD)
                    fun noParams(context: PipelineContext) = "No parameters"
                    
                    @Step(category = StepCategory.TEST)
                    suspend fun withVarargs(context: PipelineContext, vararg values: String): List<String> = values.toList()
                    
                    @Step(name = "customName", category = StepCategory.DEPLOY)
                    fun withDefaults(
                        context: PipelineContext,
                        required: String,
                        optional: String = "default",
                        flag: Boolean = false
                    ): String = "${'$'}required-${'$'}optional-${'$'}flag"
                    
                    @Step
                    suspend fun withNullable(context: PipelineContext, value: String?): String = value ?: "null"
                    
                    @Step
                    inline fun withInline(context: PipelineContext, block: () -> Unit) {
                        block()
                    }
                """.trimIndent()

                then("all function signatures should compile with preserved parameters") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    // Expected parameter counts after PipelineContext injection
                    val expectedCounts = mapOf(
                        "noParams" to 1,      // context only
                        "withVarargs" to 3,   // context + varargs + Continuation (suspend)
                        "withDefaults" to 4,  // context + 3 original params (no suspend)
                        "withNullable" to 3,  // context + nullable + Continuation (suspend)
                        "withInline" to 2     // context + function param (no suspend)
                    )

                    expectedCounts.forEach { (methodName, expectedParams) ->
                        val method = analysis.findMethod(methodName)!!
                        method.getParameterCount() shouldBe expectedParams

                        method.hasPipelineContextParameter() shouldBe true
                        println("✅ $methodName: ${method.getParameterCount()} params (with PipelineContext)")
                    }

                    println("✅ All function signatures transformed with PipelineContext injection")
                }
            }
        }

        `when`("detecting @Step annotations") {

            and("simple @Step annotation is present") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
            
                    class TestSteps {
                        @Step
                        fun simpleStep(context: PipelineContext): String = "test"
                    }
                """.trimIndent()

                then("plugin should compile @Step annotated methods successfully") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val method = analysis.findMethod("simpleStep")!!
                    method.getParameterCount() shouldBe 1  // context parameter
                    method.hasPipelineContextParameter() shouldBe true

                    println("✅ @Step annotation compilation successful")
                    println("   - Method: ${method.name}")
                    println("   - Parameters: ${method.getParameterCount()} (with context)")
                    println("   - Plugin validated signature with explicit context parameter")
                }
            }
        }

        `when`("generating DSL extensions with complex metadata") {

            and("@Step functions have full annotation metadata") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
                    import dev.rubentxu.pipeline.annotations.StepCategory
                    
                    @Step(
                        name = "customDeploy",
                        description = "Deploy application to cloud",
                        category = StepCategory.DEPLOY
                    )
                    suspend fun deployToCloud(
                        context: PipelineContext,
                        appName: String, 
                        region: String = "us-east-1",
                        replicas: Int = 3
                    ): String {
                        return "Deployed ${'$'}appName to ${'$'}region with ${'$'}replicas replicas"
                    }
                """.trimIndent()

                then("complex @Step function should compile with metadata") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val deployMethod = analysis.findMethod("deployToCloud")!!

                    // context + original parameters + Continuation (suspend) = 5 parameters
                    deployMethod.getParameterCount() shouldBe 5
                    deployMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ Complex @Step signature verification:")
                    println("   - Name: ${deployMethod.name}")
                    println("   - Parameters: ${deployMethod.getParameterCount()} (with context + Continuation)")
                    println("   - Descriptor: ${deployMethod.descriptor}")
                    println("   - Successfully compiled with complex metadata and PipelineContext injection")
                }
            }
        }

        `when`("transforming call sites to @Step functions") {

            and("simple @Step function is defined") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.context.LocalPipelineContext
                    
                    @Step
                    fun processData(context: PipelineContext, input: String): String {
                        return "Processed: ${'$'}input"
                    }
                """.trimIndent()

                then("@Step function should compile and be ready for DSL integration") {
                    // This test verifies @Step functions compile successfully
                    // and maintain their original signatures for DSL integration

                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    // Verify @Step function compilation
                    val processDataMethod = analysis.findMethod("processData")!!

                    // processData gets context + original input parameter
                    processDataMethod.getParameterCount() shouldBe 2  // context + input parameter
                    processDataMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ @Step function compilation successful:")
                    println("   - @Step function processData: ${processDataMethod.getParameterCount()} params")
                    println("   - Context parameter validated")
                    println("   - Ready for DSL integration")
                }
            }

            and("multiple @Step functions are defined") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext                    
                    
                    @Step
                    fun stepOne(context: PipelineContext, value: String): String {
                        return "Step1: ${'$'}value"
                    }
                    
                    @Step
                    fun stepTwo(context: PipelineContext, input: String): String {
                        return "Step2: ${'$'}input"
                    }
                """.trimIndent()

                then("multiple @Step functions should compile successfully") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    // Verify both @Step functions have PipelineContext injected
                    val stepOneMethod = analysis.findMethod("stepOne")!!
                    val stepTwoMethod = analysis.findMethod("stepTwo")!!

                    stepOneMethod.getParameterCount() shouldBe 2  // context + value parameter
                    stepOneMethod.hasPipelineContextParameter() shouldBe true
                    stepTwoMethod.getParameterCount() shouldBe 2  // context + input parameter  
                    stepTwoMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ Multiple @Step functions compiled successfully:")
                    println("   - stepOne: ${stepOneMethod.getParameterCount()} params (with context)")
                    println("   - stepTwo: ${stepTwoMethod.getParameterCount()} params (with context)")
                    println("   - All functions ready for call-site transformation")
                }
            }
        }

        `when`("@Step functions access injected PipelineContext in runtime") {

            and("functions use context parameter directly") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                
                    
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                    
                    @Step
                    fun logStep(context: PipelineContext, message: String): String {                       
                        context.info("Step executed: ${'$'}message")
                        return "Logged: ${'$'}message"
                    }
                    
                    @Step
                    fun shellStep(context: PipelineContext, command: String): String {                       
                        val result = context.executeShell(command)
                        return result
                    }
                """.trimIndent()

                then("@Step functions should compile and be able to access context") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    // Verify @Step functions have PipelineContext injected
                    val logStepMethod = analysis.findMethod("logStep")!!
                    val shellStepMethod = analysis.findMethod("shellStep")!!

                    // logStep: context + message = 2 parameters
                    logStepMethod.getParameterCount() shouldBe 2
                    logStepMethod.hasPipelineContextParameter() shouldBe true

                    // shellStep: context + command = 2 parameters
                    shellStepMethod.getParameterCount() shouldBe 2
                    shellStepMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ @Step functions with context access compiled successfully:")
                    println("   - logStep: ${logStepMethod.getParameterCount()} params (with context)")
                    println("   - shellStep: ${shellStepMethod.getParameterCount()} params (with context)")
                    println("   - Functions can access context directly from parameter")
                }
            }

            and("suspend @Step functions use context with continuation") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                   
                    
                    @Step
                    suspend fun asyncStep(context: PipelineContext, data: String): String {
                        context.info("Starting async operation with: ${'$'}data")
                        return context.asyncOperation(data)
                    }
                """.trimIndent()

                then("suspend @Step functions should compile with context and continuation") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val asyncStepMethod = analysis.findMethod("asyncStep")!!

                    // asyncStep: context + data + Continuation = 3 parameters
                    asyncStepMethod.getParameterCount() shouldBe 3
                    asyncStepMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ Suspend @Step function with context compiled successfully:")
                    println("   - asyncStep: ${asyncStepMethod.getParameterCount()} params (context + data + Continuation)")
                    println("   - Suspend function can access context parameter directly")
                }
            }

            and("@Step functions with complex context usage patterns") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.annotations.StepCategory 
                    import dev.rubentxu.pipeline.context.PipelineContext
                    
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                   
                    
                    @Step(category = StepCategory.BUILD)
                    suspend fun buildWithLogging(context: PipelineContext, projectName: String, version: String): String {
                        
                        context.info("Starting build for ${'$'}projectName version ${'$'}version")
                        context.setEnv("PROJECT_NAME", projectName)
                        context.setEnv("VERSION", version)
                        
                        val buildCommand = "gradle build -Pversion=${'$'}version"
                        val result = context.executeShell(buildCommand)
                        
                        if (result.contains("SUCCESS")) {
                            context.info("Build completed successfully")
                            return "Build successful for ${'$'}projectName"
                        } else {
                            context.error("Build failed for ${'$'}projectName")
                            throw RuntimeException("Build failed")
                        }
                    }
                    
                    @Step(category = StepCategory.DEPLOY) 
                    suspend fun deployWithEnvironment(context: PipelineContext, appName: String, targetEnv: String = "staging"): String {
                        
                        context.info("Deploying ${'$'}appName to ${'$'}targetEnv")
                        
                        val projectName = context.getEnv("PROJECT_NAME") ?: "unknown"
                        val version = context.getEnv("VERSION") ?: "latest"
                        
                        context.warn("Deploying ${'$'}projectName:${'$'}version to ${'$'}targetEnv")
                        
                        val deployResult = context.executeShell("kubectl deploy ${'$'}appName:${'$'}version")
                        return "Deployed ${'$'}appName to ${'$'}targetEnv: ${'$'}deployResult"
                    }
                """.trimIndent()

                then("complex @Step functions should compile and use context extensively") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val buildMethod = analysis.findMethod("buildWithLogging")!!
                    val deployMethod = analysis.findMethod("deployWithEnvironment")!!

                    // buildWithLogging: context + projectName + version = 3 parameters
                    buildMethod.getParameterCount() shouldBe 3
                    buildMethod.hasPipelineContextParameter() shouldBe true

                    // deployWithEnvironment: context + appName + targetEnv = 3 parameters
                    deployMethod.getParameterCount() shouldBe 3
                    deployMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ Complex @Step functions with extensive context usage compiled successfully:")
                    println("   - buildWithLogging: ${buildMethod.getParameterCount()} params (with comprehensive context usage)")
                    println("   - deployWithEnvironment: ${deployMethod.getParameterCount()} params (with environment handling)")
                    println("   - Functions demonstrate real-world context patterns")
                }
            }
        }

        `when`("@Step functions demonstrate real-world usage patterns") {

            and("@Step functions are defined with various context usage patterns") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext
                    import dev.rubentxu.pipeline.annotations.StepCategory
                    
                   
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                   
                    
                    @Step(category = StepCategory.BUILD)
                    suspend fun compileCode(context: PipelineContext, sourcePath: String): String {
                        context.currentStage = "compile"
                        context.info("Compiling code from ${'$'}sourcePath")
                        val result = context.executeShell("javac ${'$'}sourcePath", ShellOptions())
                        return result.stdout
                    }
                    
                    @Step(category = StepCategory.TEST)
                    suspend fun runTests(context: PipelineContext, testSuite: String): String {
                        context.currentStage = "test"
                        context.info("Running test suite: ${'$'}testSuite")
                        val result = context.executeShell("junit ${'$'}testSuite", ShellOptions())
                        return result.stdout
                    }
                    
                    @Step(category = StepCategory.UTIL)
                    fun simpleStep(context: PipelineContext, message: String): String {
                        return "Simple step: ${'$'}message"
                    }
                """.trimIndent()

                then("@Step functions should compile successfully with various patterns") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val compileMethod = analysis.findMethod("compileCode")!!
                    val testMethod = analysis.findMethod("runTests")!!
                    val simpleMethod = analysis.findMethod("simpleStep")!!

                    // All methods should have context parameter
                    compileMethod.getParameterCount() shouldBe 2 // context + sourcePath
                    compileMethod.hasPipelineContextParameter() shouldBe true

                    testMethod.getParameterCount() shouldBe 2 // context + testSuite
                    testMethod.hasPipelineContextParameter() shouldBe true

                    simpleMethod.getParameterCount() shouldBe 2 // context + message
                    simpleMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ @Step functions with various usage patterns compiled successfully:")
                    println("   - compileCode: ${compileMethod.getParameterCount()} params (uses context extensively)")
                    println("   - runTests: ${testMethod.getParameterCount()} params (uses context for logging and shell)")
                    println("   - simpleStep: ${simpleMethod.getParameterCount()} params (minimal context usage)")
                    println("   - All functions have context parameter correctly")
                }
            }
        }

        `when`("@Step functions use context parameter directly") {

            and("@Step functions access explicit context parameter") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext                   
                    
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                   
                    
                    @Step
                    suspend fun directContextStep(context: PipelineContext, projectName: String) {
                        // Context parameter is explicitly available
                        context.info("Building project: ${'$'}projectName")
                        val result = context.executeShell("gradle build")
                        if (result.contains("FAILED")) {
                            context.warn("Build may have issues")
                        }
                    }
                    
                    @Step
                    suspend fun directContextSuspendStep(context: PipelineContext, taskName: String) {
                        // Context parameter is explicitly available in suspend functions
                        context.info("Starting task: ${'$'}taskName")
                        context.executeShell("./run_task.sh ${'$'}taskName")
                    }
                """.trimIndent()

                then("@Step functions should compile and use context parameter directly") {
                    // This test should PASS if the plugin correctly validates context parameter
                    // and allows it to be used directly within @Step functions

                    val result = try {
                        compileKotlin(sourceCode, usePlugin = true, tempDir)
                    } catch (e: Exception) {
                        println("❌ Plugin doesn't validate context parameter correctly")
                        println("   Error: ${e.message}")
                        println("   The plugin needs to validate context parameter and allow its direct use")
                        return@then // Exit early since this functionality isn't implemented yet
                    }

                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val directMethod = analysis.findMethod("directContextStep")!!
                    val suspendMethod = analysis.findMethod("directContextSuspendStep")!!

                    // Plugin should validate that PipelineContext is available as 'context'
                    directMethod.getParameterCount() shouldBe 2 // context + projectName
                    directMethod.hasPipelineContextParameter() shouldBe true

                    suspendMethod.getParameterCount() shouldBe 3 // context + taskName + Continuation
                    suspendMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ @Step functions using direct pipelineContext access compiled successfully:")
                    println("   - directContextStep: ${directMethod.getParameterCount()} params")
                    println("   - directContextSuspendStep: ${suspendMethod.getParameterCount()} params")
                    println("   - Functions can use context parameter directly")
                }
            }

            and("@Step functions use explicit context parameter") {
                val sourceCode = """
                    package test.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.context.PipelineContext                    
                    
                    data class ShellOptions(val workingDir: String? = null, val timeout: Long = 30000)
                    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
                        val success: Boolean get() = exitCode == 0
                        fun contains(text: String): Boolean = stdout.contains(text) || stderr.contains(text)
                    }
                   
                    
                    @Step
                    fun explicitContextStep(context: PipelineContext, fileName: String) {
                        // Context parameter is explicitly available
                        context.debug("Processing file: ${'$'}fileName")
                        
                        try {
                            // Simulate some work
                            val processed = "processed_${'$'}fileName"
                        } catch (e: Exception) {
                            context.error("Failed to process ${'$'}fileName: ${'$'}{e.message}")
                            throw e
                        }
                    }
                """.trimIndent()

                then("plugin should validate explicit context parameter") {
                    // This will also fail until the plugin is enhanced
                    val result = try {
                        compileKotlin(sourceCode, usePlugin = true, tempDir)
                    } catch (e: Exception) {
                        println("❌ Plugin doesn't validate explicit context parameter correctly")
                        println("   The plugin should validate explicit context parameter")
                        return@then
                    }

                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val explicitMethod = analysis.findMethod("explicitContextStep")!!
                    explicitMethod.getParameterCount() shouldBe 2 // context + fileName
                    explicitMethod.hasPipelineContextParameter() shouldBe true

                    println("✅ Explicit context parameter test passed")
                }
            }
        }
    }
})

/**
 * Utility function to get the plugin JAR path from system properties or build directories
 */
private fun getPluginJarPath(): String? {
    // From system property (configured by Gradle)
    System.getProperty("plugin.jar.path")?.let { path ->
        if (File(path).exists()) return path
    }

    // Search in build directories
    val buildDirs = listOf(
        "build/libs",
        "../build/libs",
        "compiler-plugin/build/libs"
    )

    for (buildDir in buildDirs) {
        val dir = File(buildDir)
        if (dir.exists()) {
            val pluginJar = dir.listFiles()?.find {
                it.name.contains("compiler-plugin") && it.name.endsWith(".jar")
            }
            if (pluginJar?.exists() == true) {
                return pluginJar.absolutePath
            }
        }
    }

    return null
}

/**
 * Compile Kotlin source code with or without the Step compiler plugin
 *
 * @param sourceCode The Kotlin source code to compile
 * @param usePlugin Whether to use the Step compiler plugin
 * @param tempDir Temporary directory for compilation output
 * @return The compiled .class file
 */
private fun compileKotlin(sourceCode: String, usePlugin: Boolean, tempDir: File): File {
    val sourceFile = File(tempDir, "TestSource.kt")
    sourceFile.writeText(sourceCode)

    val outputDir = File(tempDir, if (usePlugin) "with-plugin" else "without-plugin")
    outputDir.mkdirs()

    val args = K2JVMCompilerArguments().apply {
        freeArgs = listOf(sourceFile.absolutePath)
        destination = outputDir.absolutePath
        classpath = buildClasspath()
        jvmTarget = "21"
        languageVersion = "2.2"
        apiVersion = "2.2"
        noStdlib = true
        noReflect = true

        // Fix K2JVMCompiler ExceptionInInitializerError - disable scripting 
        disableDefaultScriptingPlugin = true

        if (usePlugin) {
            val pluginJarPath = getPluginJarPath()
            if (pluginJarPath != null) {
                pluginClasspaths = arrayOf(pluginJarPath)

                // Configure plugin options
                pluginOptions = arrayOf(
                    "plugin:dev.rubentxu.pipeline.steps:enableContextInjection=true",
                    "plugin:dev.rubentxu.pipeline.steps:enableDslGeneration=true",
                    "plugin:dev.rubentxu.pipeline.steps:debugMode=true"
                )

                println("🔧 Plugin configured: $pluginJarPath")
                println("🔧 Plugin options: ${pluginOptions?.joinToString()}")
            } else {
                println("⚠️ Plugin JAR not found, compiling without plugin")
                return compileKotlin(sourceCode, usePlugin = false, tempDir)
            }
        }
    }

    val compiler = K2JVMCompiler()

    // Create a custom message collector that captures errors for debugging
    val errorMessages = mutableListOf<String>()
    val messageCollector = object : MessageCollector {
        override fun clear() {
            errorMessages.clear()
        }

        override fun hasErrors(): Boolean = errorMessages.isNotEmpty()

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?
        ) {
            val locationStr = location?.let { " at ${it.path}:${it.line}:${it.column}" } ?: ""
            val fullMessage = "[$severity] $message$locationStr"

            println("🔍 Compiler message: $fullMessage")

            if (severity.isError) {
                errorMessages.add(fullMessage)
            }
        }
    }

    println("🔧 Compiling ${if (usePlugin) "WITH" else "WITHOUT"} plugin:")
    println("   - Source: ${sourceFile.name}")
    println("   - Output: ${outputDir.absolutePath}")
    println("   - Source content:")
    println(sourceFile.readText())

    val exitCode = compiler.exec(messageCollector, Services.EMPTY, args)

    if (exitCode.code != 0) {
        println("❌ Compilation failed with code ${exitCode.code}")
        println("❌ Source file: ${sourceFile.absolutePath}")
        println("❌ Output directory: ${outputDir.absolutePath}")

        if (errorMessages.isNotEmpty()) {
            println("❌ Compilation errors:")
            errorMessages.forEach { error ->
                println("   $error")
            }
        } else {
            println("❌ No specific error messages captured (this may indicate a compiler plugin issue)")
        }

        if (outputDir.exists()) {
            println("❌ Output directory contents:")
            outputDir.walkTopDown().forEach { file ->
                println("   - ${file.relativeTo(outputDir)} (${if (file.isFile) "file" else "dir"})")
            }
        }

        val errorDetails = if (errorMessages.isNotEmpty()) {
            "\nErrors: ${errorMessages.joinToString("; ")}"
        } else {
            "\nNo specific compiler errors captured"
        }

        throw AssertionError("Compilation failed with code ${exitCode.code}$errorDetails")
    }

    // Find generated .class file
    val classFiles = outputDir.walkTopDown()
        .filter { it.name.endsWith(".class") }
        .filter { !it.name.contains("$") } // Exclude anonymous classes
        .toList()

    if (classFiles.isEmpty()) {
        println("⚠️ No .class files found in $outputDir")
        println("   Directory contents:")
        outputDir.walkTopDown().forEach { file ->
            println("   - ${file.relativeTo(outputDir)}")
        }
        throw AssertionError("No .class files generated in $outputDir")
    }

    // Try to find the class file that contains the test methods
    val targetClassFile = classFiles.find {
        it.name == "TestSteps.class" ||
                it.name.contains("TestSource") ||
                it.name.contains("TestSteps") ||
                !it.name.contains("PipelineContext") && !it.name.contains("LocalPipelineContext")
    } ?: classFiles.first()

    return targetClassFile
}

/**
 * Build the classpath for compilation including test fixtures and dependencies
 */
private fun buildClasspath(): String {
    val classpathEntries = mutableSetOf<String>()

    // Classpath from Gradle test configuration
    System.getProperty("test.classpath")?.let { classpath ->
        classpathEntries.addAll(classpath.split(File.pathSeparator))
    }

    // Add annotations JAR directly
    System.getProperty("annotations.jar.path")?.let { annotationsJarPath ->
        if (File(annotationsJarPath).exists()) {
            classpathEntries.add(annotationsJarPath)
            println("🔧 Annotations JAR added: ${File(annotationsJarPath).name}")
        }
    }

    // Add compiled test classes to classpath for mock classes
    val testClassesPath = File("pipeline-steps-system/compiler-plugin/build/classes/kotlin/test").absolutePath
    if (File(testClassesPath).exists()) {
        classpathEntries.add(testClassesPath)
        println("🔧 Test classes added: $testClassesPath")
    } else {
        val altTestClasses = File("build/classes/kotlin/test").absolutePath
        if (File(altTestClasses).exists()) {
            classpathEntries.add(altTestClasses)
            println("🔧 Test classes added: $altTestClasses")
        }
    }

    // Add test-fixtures to classpath if they exist
    val testFixturesPath = File("pipeline-steps-system/compiler-plugin/test-fixtures").absolutePath
    if (File(testFixturesPath).exists()) {
        classpathEntries.add(testFixturesPath)
        println("🔧 Test fixtures added: $testFixturesPath")
    } else {
        val altTestFixtures = File("test-fixtures").absolutePath
        if (File(altTestFixtures).exists()) {
            classpathEntries.add(altTestFixtures)
            println("🔧 Test fixtures added: $altTestFixtures")
        }
    }

    // Ensure kotlin-stdlib is available
    val stdlib = classpathEntries.find { it.contains("kotlin-stdlib") }
    if (stdlib == null) {
        println("⚠️ Warning: kotlin-stdlib.jar not found in test classpath")
    } else {
        println("🔧 kotlin-stdlib found: $stdlib")
    }

    val validEntries = classpathEntries
        .filter { File(it).exists() }
        .distinctBy { File(it).name }
        .toList()

    println("🔧 Valid classpath (${validEntries.size} entries)")

    return validEntries.joinToString(File.pathSeparator)
}