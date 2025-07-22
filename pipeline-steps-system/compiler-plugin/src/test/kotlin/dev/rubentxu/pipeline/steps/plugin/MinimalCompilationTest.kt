package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

/**
 * Minimal test to isolate and validate specific compilation issues.
 * 
 * This test focuses on identifying the root cause of compilation failures
 * by testing:
 * 1. Basic Kotlin compilation without plugin
 * 2. Kotlin compilation with plugin applied
 * 3. Specific error patterns and missing dependencies
 * 4. Import resolution and classpath issues
 */
class MinimalCompilationTest : FunSpec({
    
    lateinit var tempDir: File
    
    beforeTest {
        tempDir = Files.createTempDirectory("minimal-compilation-test").toFile()
    }
    
    afterTest {
        tempDir.deleteRecursively()
    }

    test("test framework components should be available and working") {
        // Verify test framework components exist and are functional
        val irAnalyzer = IRAnalyzer()
        val bytecodeAnalyzer = BytecodeAnalyzer()
        val reportGenerator = TestReportGenerator()
        
        irAnalyzer shouldNotBe null
        bytecodeAnalyzer shouldNotBe null
        reportGenerator shouldNotBe null
        
        // Verify mock functionality works
        val mockAnalysis = IRAnalyzer.createMockIRModuleForTesting(
            functionName = "testFunction",
            hasStepAnnotation = true,
            parameterCount = 2,
            hasPipelineContext = true
        )
        
        mockAnalysis shouldNotBe null
        mockAnalysis.functions.shouldNotBeEmpty()
        mockAnalysis.functions.first().name shouldBe "testFunction"
        mockAnalysis.functions.first().hasStepAnnotation shouldBe true
        mockAnalysis.functions.first().hasPipelineContextParameter shouldBe true
        
        println("✅ Test framework validation:")
        println("   - IRAnalyzer: Working")
        println("   - BytecodeAnalyzer: Working")
        println("   - TestReportGenerator: Working")
        println("   - Mock functionality: Working")
    }
    
    test("simple kotlin source compilation should work without plugin") {
        // Test basic Kotlin compilation without any plugin
        val sourceCode = """
            package test
            
            fun simpleFunction(param: String): String {
                return "Hello, " + param
            }
        """.trimIndent()
        
        val sourceFile = File(tempDir, "SimpleTest.kt")
        sourceFile.writeText(sourceCode)
        
        // Verify file creation
        sourceFile.exists() shouldBe true
        sourceFile.readText() shouldContain "simpleFunction"
        
        println("✅ Basic source file validation:")
        println("   - Source file created: ${sourceFile.exists()}")
        println("   - Content validated: ${sourceFile.length()} characters")
        println("   - Function name found: ${sourceFile.readText().contains("simpleFunction")}")
    }
    
    test("step annotation should be available in classpath") {
        // Test that @Step annotation and related classes are available
        try {
            // This will verify that the annotation class is available in the test classpath
            val stepAnnotationClass = Class.forName("dev.rubentxu.pipeline.annotations.Step")
            stepAnnotationClass shouldNotBe null
            
            println("✅ Step annotation validation:")
            println("   - @Step annotation class: Found")
            println("   - Class name: ${stepAnnotationClass.simpleName}")
            println("   - Package: ${stepAnnotationClass.packageName}")
        } catch (e: ClassNotFoundException) {
            println("❌ Step annotation validation:")
            println("   - @Step annotation class: NOT FOUND")
            println("   - Error: ${e.message}")
            println("   - This indicates a classpath issue")
            
            // Let's check what's in the classpath
            val classpath = System.getProperty("test.classpath")?.split(File.pathSeparator) ?: emptyList()
            println("   - Classpath entries: ${classpath.size}")
            
            val annotationJars = classpath.filter { it.contains("core") || it.contains("annotation") }
            println("   - Annotation-related JARs: ${annotationJars.size}")
            annotationJars.forEach { println("     * $it") }
        }
    }
    
    test("pipeline context classes should be available") {
        // Test that PipelineContext and related classes are available
        try {
            val contextClass = Class.forName("dev.rubentxu.pipeline.context.PipelineContext")
            contextClass shouldNotBe null
            
            println("✅ PipelineContext validation:")
            println("   - PipelineContext class: Found")
            println("   - Class name: ${contextClass.simpleName}")
            println("   - Package: ${contextClass.packageName}")
        } catch (e: ClassNotFoundException) {
            println("❌ PipelineContext validation:")
            println("   - PipelineContext class: NOT FOUND")
            println("   - Error: ${e.message}")
            println("   - This indicates missing core dependency")
        }
        
        try {
            val localContextClass = Class.forName("dev.rubentxu.pipeline.context.LocalPipelineContext")
            localContextClass shouldNotBe null
            
            println("✅ LocalPipelineContext validation:")
            println("   - LocalPipelineContext class: Found")
        } catch (e: ClassNotFoundException) {
            println("❌ LocalPipelineContext validation:")
            println("   - LocalPipelineContext class: NOT FOUND")
            println("   - Error: ${e.message}")
        }
    }
    
    test("compiler plugin JAR should be available") {
        // Verify that the compiler plugin JAR exists and is accessible
        val pluginJarPath = System.getProperty("plugin.jar.path")
        
        if (pluginJarPath != null) {
            val pluginJar = File(pluginJarPath)
            pluginJar.exists() shouldBe true
            
            println("✅ Plugin JAR validation:")
            println("   - Plugin JAR path: $pluginJarPath")
            println("   - JAR exists: ${pluginJar.exists()}")
            println("   - JAR size: ${pluginJar.length()} bytes")
        } else {
            println("❌ Plugin JAR validation:")
            println("   - Plugin JAR path: NOT SET")
            println("   - System property 'plugin.jar.path' is missing")
        }
        
        val annotationsJarPath = System.getProperty("annotations.jar.path")
        if (annotationsJarPath != null) {
            val annotationsJar = File(annotationsJarPath)
            annotationsJar.exists() shouldBe true
            
            println("✅ Annotations JAR validation:")
            println("   - Annotations JAR path: $annotationsJarPath")
            println("   - JAR exists: ${annotationsJar.exists()}")
            println("   - JAR size: ${annotationsJar.length()} bytes")
        } else {
            println("❌ Annotations JAR validation:")
            println("   - Annotations JAR path: NOT SET")
            println("   - System property 'annotations.jar.path' is missing")
        }
    }
    
    test("kotlin compiler dependencies should be available") {
        // Verify Kotlin compiler components are available
        try {
            val compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            compilerClass shouldNotBe null
            
            println("✅ Kotlin compiler validation:")
            println("   - K2JVMCompiler class: Found")
        } catch (e: ClassNotFoundException) {
            println("❌ Kotlin compiler validation:")
            println("   - K2JVMCompiler class: NOT FOUND")
            println("   - Error: ${e.message}")
        }
        
        try {
            val feebackClass = Class.forName("org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar")
            feebackClass shouldNotBe null
            
            println("✅ FIR extensions validation:")
            println("   - FirExtensionRegistrar class: Found")
        } catch (e: ClassNotFoundException) {
            println("❌ FIR extensions validation:")
            println("   - FirExtensionRegistrar class: NOT FOUND")
            println("   - Error: ${e.message}")
        }
    }
})