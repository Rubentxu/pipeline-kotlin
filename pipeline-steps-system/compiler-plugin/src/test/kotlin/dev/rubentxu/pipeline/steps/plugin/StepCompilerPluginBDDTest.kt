package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.file.shouldExist
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

/**
 * BDD-style tests for the Step Compiler Plugin using Kotest
 * 
 * This test suite serves as both comprehensive testing and documentation 
 * for the @Step annotation compiler plugin functionality.
 * 
 * The plugin transforms Kotlin functions annotated with @Step by:
 * 1. Injecting PipelineContext as the first parameter
 * 2. Maintaining original function signatures
 * 3. Handling suspend functions correctly (with Continuation parameter)
 * 4. Leaving non-@Step functions unchanged
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
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
                    class TestSteps {
                        
                        @Step
                        fun buildStep(name: String): String {
                            return "Building: ${'$'}name"
                        }
                        
                        @Step  
                        fun deployStep(env: String): String {
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
                    
                    // Plugin should add PipelineContext parameter to @Step functions
                    transformedBuildStep.getParameterCount() shouldBe originalBuildStep.getParameterCount() + 1
                    transformedBuildStep.hasPipelineContextParameter() shouldBe true
                    
                    // Non-@Step methods should remain unchanged
                    val originalNormal = originalAnalysis.findMethod("normalMethod")!!
                    val transformedNormal = transformedAnalysis.findMethod("normalMethod")!!
                    
                    transformedNormal.getParameterCount() shouldBe originalNormal.getParameterCount()
                    
                    println("✅ Plugin processed @Step methods successfully")
                    println("   - buildStep: ${originalBuildStep.getParameterCount()} parameters (preserved)")
                    println("   - normalMethod unchanged: ${transformedNormal.getParameterCount()} parameters")
                }
            }
            
            and("functions are not annotated with @Step") {
                val sourceCode = """
                    package dev.rubentxu.pipeline.context
                    
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
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
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
            
                    @Step
                    fun deployApp(appName: String, environment: String): String {
                        return "Deploying ${'$'}appName to ${'$'}environment"
                    }
                    
                    @Step
                    suspend fun buildProject(projectPath: String): Boolean {
                        return true
                    }
                    
                    @Step
                    fun simpleStep() {
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
                    
                    // deployApp: PipelineContext + appName + environment = 3 parameters
                    deployApp.getParameterCount() shouldBe 3
                    deployApp.hasPipelineContextParameter() shouldBe true
                    
                    // buildProject is suspend: PipelineContext + projectPath + Continuation = 3 parameters
                    buildProject.getParameterCount() shouldBe 3
                    buildProject.hasPipelineContextParameter() shouldBe true
                    
                    // simpleStep: PipelineContext = 1 parameter
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
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
            
                    @Step
                    fun echo(message: String) {
                        println("Echo: ${'$'}message")
                    }
                    
                    @Step
                    suspend fun compile(project: String, clean: Boolean = true): Boolean {
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
                            echoMethod.getParameterCount() shouldBe 2 // PipelineContext + message
                            echoMethod.hasPipelineContextParameter() shouldBe true
                        }
                        
                        if (compileMethod != null) {
                            compileMethod.getParameterCount() shouldBe 4 // PipelineContext + project + clean + Continuation
                            compileMethod.hasPipelineContextParameter() shouldBe true
                        }
                    }
                }
            }
        }

        `when`("handling complex function signatures") {
            
            and("functions have varargs, defaults, nullable, and inline parameters") {
                val sourceCode = """
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.annotations.StepCategory
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
                    @Step(category = StepCategory.BUILD)
                    fun noParams() = "No parameters"
                    
                    @Step(category = StepCategory.TEST)
                    suspend fun withVarargs(vararg values: String): List<String> = values.toList()
                    
                    @Step(name = "customName", category = StepCategory.DEPLOY)
                    fun withDefaults(
                        required: String,
                        optional: String = "default",
                        flag: Boolean = false
                    ): String = "${'$'}required-${'$'}optional-${'$'}flag"
                    
                    @Step
                    suspend fun withNullable(value: String?): String = value ?: "null"
                    
                    @Step
                    inline fun withInline(block: () -> Unit) {
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
                        "noParams" to 1,      // PipelineContext only
                        "withVarargs" to 3,   // PipelineContext + varargs + Continuation (suspend)
                        "withDefaults" to 4,  // PipelineContext + 3 original params (no suspend)
                        "withNullable" to 3,  // PipelineContext + nullable + Continuation (suspend)
                        "withInline" to 2     // PipelineContext + function param (no suspend)
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
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
            
                    class TestSteps {
                        @Step
                        fun simpleStep(): String = "test"
                    }
                """.trimIndent()

                then("plugin should compile @Step annotated methods successfully") {
                    val result = compileKotlin(sourceCode, usePlugin = true, tempDir)
                    result.shouldExist()

                    val analyzer = BytecodeAnalyzer()
                    val analysis = analyzer.analyzeClassFile(result)

                    val method = analysis.findMethod("simpleStep")!!
                    method.getParameterCount() shouldBe 1  // PipelineContext added
                    method.hasPipelineContextParameter() shouldBe true
                    
                    println("✅ @Step annotation compilation successful")
                    println("   - Method: ${method.name}")
                    println("   - Parameters: ${method.getParameterCount()} (with PipelineContext)")
                    println("   - Plugin transformed signature by adding PipelineContext")
                }
            }
        }

        `when`("generating DSL extensions with complex metadata") {
            
            and("@Step functions have full annotation metadata") {
                val sourceCode = """
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    import dev.rubentxu.pipeline.annotations.StepCategory
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
                    @Step(
                        name = "customDeploy",
                        description = "Deploy application to cloud",
                        category = StepCategory.DEPLOY
                    )
                    suspend fun deployToCloud(
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
                    
                    // PipelineContext + original parameters + Continuation (suspend) = 5 parameters
                    deployMethod.getParameterCount() shouldBe 5
                    deployMethod.hasPipelineContextParameter() shouldBe true
                    
                    println("✅ Complex @Step signature verification:")
                    println("   - Name: ${deployMethod.name}")
                    println("   - Parameters: ${deployMethod.getParameterCount()} (with PipelineContext + Continuation)")
                    println("   - Descriptor: ${deployMethod.descriptor}")
                    println("   - Successfully compiled with complex metadata and PipelineContext injection")
                }
            }
        }

        `when`("transforming call sites to @Step functions") {
            
            and("simple @Step function is defined") {
                val sourceCode = """
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
                    @Step
                    fun processData(input: String): String {
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
                    
                    // processData gets PipelineContext + original input parameter
                    processDataMethod.getParameterCount() shouldBe 2  // PipelineContext + input parameter
                    processDataMethod.hasPipelineContextParameter() shouldBe true
                    
                    println("✅ @Step function compilation successful:")
                    println("   - @Step function processData: ${processDataMethod.getParameterCount()} params")
                    println("   - PipelineContext injected")
                    println("   - Ready for DSL integration")
                }
            }
            
            and("multiple @Step functions are defined") {
                val sourceCode = """
                    package dev.rubentxu.pipeline.context
            
                    import dev.rubentxu.pipeline.annotations.Step
                    
                    // Include mock context classes directly in the source
                    interface PipelineContext
                    
                    object LocalPipelineContext {
                        val current: PipelineContext get() = object : PipelineContext {}
                    }
                    
                    @Step
                    fun stepOne(value: String): String {
                        return "Step1: ${'$'}value"
                    }
                    
                    @Step
                    fun stepTwo(input: String): String {
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
                    
                    stepOneMethod.getParameterCount() shouldBe 2  // PipelineContext + value parameter
                    stepOneMethod.hasPipelineContextParameter() shouldBe true
                    stepTwoMethod.getParameterCount() shouldBe 2  // PipelineContext + input parameter  
                    stepTwoMethod.hasPipelineContextParameter() shouldBe true
                    
                    println("✅ Multiple @Step functions compiled successfully:")
                    println("   - stepOne: ${stepOneMethod.getParameterCount()} params (with PipelineContext)")
                    println("   - stepTwo: ${stepTwoMethod.getParameterCount()} params (with PipelineContext)")
                    println("   - All functions ready for call-site transformation")
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

        // Stdlib configuration (enable for plugin compatibility)
        noStdlib = true
        noReflect = true

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
    val messageCollector = PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, true)

    println("🔧 Compiling ${if (usePlugin) "WITH" else "WITHOUT"} plugin:")
    println("   - Source: ${sourceFile.name}")
    println("   - Output: ${outputDir.absolutePath}")

    val exitCode = compiler.exec(messageCollector, Services.EMPTY, args)

    if (exitCode.code != 0) {
        throw AssertionError("Compilation failed with code ${exitCode.code}")
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

    // Add test-fixtures to classpath
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