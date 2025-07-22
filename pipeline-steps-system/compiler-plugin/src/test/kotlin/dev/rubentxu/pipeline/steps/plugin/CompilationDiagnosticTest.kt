package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Files

/**
 * Simple diagnostic test to show the actual compilation errors
 * without using complex test frameworks that might hide the real issues.
 */
class CompilationDiagnosticTest : FunSpec({
    
    test("show actual compilation errors for step functions") {
        val tempDir = Files.createTempDirectory("compilation-diagnostic").toFile()
        
        try {
            // Create a simple @Step function source code
            val sourceCode = """
                package test
                
                import dev.rubentxu.pipeline.annotations.Step
                import dev.rubentxu.pipeline.annotations.StepCategory
                import dev.rubentxu.pipeline.annotations.SecurityLevel
                import dev.rubentxu.pipeline.context.PipelineContext
                import dev.rubentxu.pipeline.context.LocalPipelineContext
                
                @Step(
                    name = "testStep",
                    description = "A test step",
                    category = StepCategory.UTIL,
                    securityLevel = SecurityLevel.RESTRICTED
                )
                suspend fun testStep(param: String): String {
                    val context = LocalPipelineContext.current
                    return "Step executed with: " + param
                }
            """.trimIndent()
            
            val sourceFile = File(tempDir, "TestStep.kt")
            sourceFile.writeText(sourceCode)
            
            println("=".repeat(80))
            println("COMPILATION DIAGNOSTIC TEST")
            println("=".repeat(80))
            println("Source file created: ${sourceFile.absolutePath}")
            println("Source code length: ${sourceCode.length} characters")
            println()
            
            // Check if classes are available in classpath
            val classesToCheck = listOf(
                "dev.rubentxu.pipeline.annotations.Step",
                "dev.rubentxu.pipeline.annotations.StepCategory",
                "dev.rubentxu.pipeline.annotations.SecurityLevel",
                "dev.rubentxu.pipeline.context.PipelineContext",
                "dev.rubentxu.pipeline.context.LocalPipelineContext"
            )
            
            println("CLASSPATH VALIDATION:")
            println("-".repeat(40))
            classesToCheck.forEach { className ->
                try {
                    val clazz = Class.forName(className)
                    println("✅ $className - FOUND (package: ${clazz.packageName})")
                } catch (e: ClassNotFoundException) {
                    println("❌ $className - NOT FOUND")
                } catch (e: Exception) {
                    println("⚠️  $className - ERROR: ${e.message}")
                }
            }
            
            println()
            println("TEST CLASSPATH ENTRIES:")
            println("-".repeat(40))
            val testClasspath = System.getProperty("test.classpath")?.split(File.pathSeparator) ?: emptyList()
            println("Total classpath entries: ${testClasspath.size}")
            
            val relevantJars = testClasspath.filter { path ->
                path.contains("core") || 
                path.contains("annotation") || 
                path.contains("pipeline") ||
                path.contains("step")
            }
            
            println("Relevant JARs/paths:")
            relevantJars.forEach { jar ->
                val file = File(jar)
                val exists = if (file.exists()) "EXISTS" else "MISSING"
                val size = if (file.exists()) "${file.length()} bytes" else "N/A"
                println("  $exists: $jar ($size)")
            }
            
            println()
            println("SYSTEM PROPERTIES:")
            println("-".repeat(40))
            listOf(
                "plugin.jar.path",
                "annotations.jar.path",
                "kotlin.compiler.version",
                "kotlin.test.supportsK2"
            ).forEach { prop ->
                val value = System.getProperty(prop)
                if (value != null) {
                    println("$prop = $value")
                } else {
                    println("$prop = NOT SET")
                }
            }
            
            println()
            println("CONCLUSION:")
            println("-".repeat(40))
            
            val coreClassesAvailable = classesToCheck.all { className ->
                try {
                    Class.forName(className)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            
            if (coreClassesAvailable) {
                println("✅ All required classes are available in the test classpath")
                println("✅ The issue is likely NOT missing dependencies")
                println("✅ The issue might be:")
                println("   - Compiler plugin not being applied correctly")
                println("   - Plugin configuration issues")
                println("   - Incompatible Kotlin compiler versions")
                println("   - Plugin registration problems")
            } else {
                println("❌ Some required classes are missing from the test classpath")
                println("❌ This indicates a dependency resolution issue")
                println("❌ Possible solutions:")
                println("   - Check build.gradle.kts dependencies")
                println("   - Verify core module is being included")
                println("   - Check if annotations are properly exported")
            }
            
            println("=".repeat(80))
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
})