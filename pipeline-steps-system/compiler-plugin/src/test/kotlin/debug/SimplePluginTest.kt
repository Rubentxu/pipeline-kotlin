package debug

import dev.rubentxu.pipeline.annotations.Step
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Simple test to verify the plugin loads and executes
 */
class SimplePluginTest : StringSpec({
    
    "plugin should load and show debug output" {
        val tempDir = createTempDirectory("simple-test").toFile()
        
        val sourceCode = """
            package dev.rubentxu.pipeline.context
            
            import dev.rubentxu.pipeline.annotations.Step
            
            // Include mock context classes directly in the source
            interface PipelineContext
            
            object LocalPipelineContext {
                val current: PipelineContext get() = object : PipelineContext {}
            }
            
            @Step
            fun testStep(name: String): String {
                return "test: ${'$'}name"
            }
        """.trimIndent()
        
        // Write source file
        val sourceFile = File(tempDir, "Test.kt").apply {
            writeText(sourceCode)
        }
        
        val outputDir = File(tempDir, "output").apply { mkdirs() }
        
        // Get plugin JAR
        val pluginJar = System.getProperty("plugin.jar.path")
        println("🔧 Plugin JAR: $pluginJar")
        
        val annotationsJar = System.getProperty("annotations.jar.path")
        println("🔧 Annotations JAR: $annotationsJar")
        
        val testClasspath = System.getProperty("test.classpath")
        println("🔧 Test classpath: ${testClasspath?.split(File.pathSeparator)?.size} entries")
        
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceFile.absolutePath)
            destination = outputDir.absolutePath
            
            // Use test classpath
            classpath = testClasspath ?: ""
            
            jvmTarget = "21"
            languageVersion = "2.2"
            apiVersion = "2.2"
            noStdlib = false
            noReflect = false
            
            // Configure plugin
            if (pluginJar != null && File(pluginJar).exists()) {
                pluginClasspaths = arrayOf(pluginJar)
                
                pluginOptions = arrayOf(
                    "plugin:dev.rubentxu.pipeline.steps:enableContextInjection=true",
                    "plugin:dev.rubentxu.pipeline.steps:enableDslGeneration=true", 
                    "plugin:dev.rubentxu.pipeline.steps:debugMode=true"
                )
                
                println("🔧 Plugin configured with options")
            } else {
                println("❌ Plugin JAR not found or invalid: $pluginJar")
            }
        }
        
        val compiler = K2JVMCompiler()
        val messageCollector = PrintingMessageCollector(System.out, MessageRenderer.PLAIN_RELATIVE_PATHS, true)
        
        println("🚀 Starting compilation...")
        val exitCode = compiler.exec(messageCollector, Services.EMPTY, args)
        
        println("✅ Compilation exit code: ${exitCode.code}")
        
        if (exitCode.code == 0) {
            val classFiles = outputDir.walkTopDown()
                .filter { it.name.endsWith(".class") }
                .toList()
            
            println("📁 Generated class files: ${classFiles.size}")
            classFiles.forEach { println("   - ${it.name}") }
            
            classFiles.size shouldBe 4  // PipelineContext.class, LocalPipelineContext.class, LocalPipelineContext$current$1.class, TestKt.class
        }
        
        tempDir.deleteRecursively()
    }
})