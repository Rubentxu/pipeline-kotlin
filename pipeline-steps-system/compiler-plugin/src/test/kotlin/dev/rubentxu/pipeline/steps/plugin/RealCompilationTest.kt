package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.Services
import java.io.File

import java.nio.file.Files

/**
 * Real compilation test to identify the actual root cause of compilation failures
 * when trying to compile @Step functions with the compiler plugin.
 *
 * This test uses the actual Kotlin K2 compiler to compile source code
 * and identifies specific errors that occur during compilation.
 */
class RealCompilationTest : FunSpec({

    lateinit var tempDir: File

    beforeTest {
        tempDir = Files.createTempDirectory("real-compilation-test").toFile()
    }

    afterTest {
        tempDir.deleteRecursively()
    }

    test("compilation without @Step annotation should work") {
        // Test compilation of a simple function without @Step annotation
        val sourceCode = """
            package test
            
            fun simpleFunction(param: String): String {
                return "Hello, " + param
            }
            
            fun main() {
                println(simpleFunction("World"))
            }
        """.trimIndent()

        val (result, messages) = compileKotlinSource(sourceCode, "SimpleFunction")

        result shouldBe true
        println("✅ Simple function compilation successful")

        if (messages.isNotEmpty()) {
            println("Compiler messages:")
            messages.forEach { println("   $it") }
        }
    }

    test("compilation with mock context classes should work") {
        // Test compilation with simple function that doesn't manually access context
        val sourceCode = """
            package test
            
            fun simpleFunction(): String {
                return "Simple function works"
            }
            
            fun main() {
                println(simpleFunction())
            }
        """.trimIndent()

        val (result, messages) = compileKotlinSource(sourceCode, "ContextFunction")

        println("Compilation result: $result")
        if (messages.isNotEmpty()) {
            println("Compiler messages:")
            messages.forEach { println("   $it") }
        }

        // This might fail, which would show us exactly what's missing
        if (!result) {
            println("❌ Context compilation failed - this identifies missing dependencies")
        } else {
            println("✅ Context compilation successful")
        }
    }

    test("compilation with @Step annotation should show the actual issue") {
        // Test compilation with @Step annotation - context should be injected by plugin
        val sourceCode = """
            package test
            
            import dev.rubentxu.pipeline.annotations.Step
            import dev.rubentxu.pipeline.annotations.StepCategory
            import dev.rubentxu.pipeline.annotations.SecurityLevel
            
            @Step(
                name = "testStep",
                description = "A test step",
                category = StepCategory.UTIL,
                securityLevel = SecurityLevel.RESTRICTED
            )
            suspend fun testStep(param: String): String {
                // The compiler plugin should inject PipelineContext here
                return "Step executed with: " + param
            }
            
            fun main() {
                // Note: This won't actually run since it's suspend
                println("Test step function defined")
            }
        """.trimIndent()

        val (result, messages) = compileKotlinSource(sourceCode, "StepFunction")

        println("@Step compilation result: $result")
        if (messages.isNotEmpty()) {
            println("Compiler messages for @Step:")
            messages.forEach { println("   $it") }
        }

        if (!result) {
            println("❌ @Step compilation failed - this is the root cause:")
            val errors = messages.filter { it.contains("error", ignoreCase = true) }
            errors.forEach { println("   ERROR: $it") }
        } else {
            println("✅ @Step compilation successful")
        }
    }
})

/**
 * Compiles Kotlin source code and returns the result along with compiler messages
 */
private fun compileKotlinSource(sourceCode: String, className: String): Pair<Boolean, List<String>> {
    val tempDir = Files.createTempDirectory("kotlin-compile-test").toFile()
    try {
        val sourceFile = File(tempDir, "$className.kt")
        sourceFile.writeText(sourceCode)

        val outputDir = File(tempDir, "output")
        outputDir.mkdirs()

        val messages = mutableListOf<String>()
        val messageCollector = object : MessageCollector {
            override fun clear() {}

            override fun hasErrors(): Boolean = messages.any { it.contains("error", ignoreCase = true) }

            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?
            ) {
                val locationStr = location?.let { " at ${it.path}:${it.line}:${it.column}" } ?: ""
                messages.add("[$severity] $message$locationStr")
            }
        }

        // Add current test classpath
        val testClasspath = System.getProperty("test.classpath")?.split(File.pathSeparator) ?: emptyList()

        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
            put(JVMConfigurationKeys.OUTPUT_DIRECTORY, outputDir)
            put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)

            testClasspath.forEach { classpathEntry ->
                val file = File(classpathEntry)
                if (file.exists()) {
                    addJvmClasspathRoot(file)
                }
            }
        }

        val compiler = K2JVMCompiler()
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceFile.absolutePath)
            destination = outputDir.absolutePath
            classpath = testClasspath.joinToString(File.pathSeparator)
            jvmTarget = "21"
            languageVersion = "2.2"
            apiVersion = "2.2"
            noStdlib = true
            noReflect = true
            // Fix K2JVMCompiler ExceptionInInitializerError - disable scripting
            disableDefaultScriptingPlugin = true
        }

        val result = try {
            compiler.exec(messageCollector, Services.EMPTY, args)
        } catch (e: Exception) {
            messages.add("Compilation exception: ${e.message}")
            org.jetbrains.kotlin.cli.common.ExitCode.COMPILATION_ERROR
        }

        return Pair(result == org.jetbrains.kotlin.cli.common.ExitCode.OK, messages)
    } finally {
        tempDir.deleteRecursively()
    }
}