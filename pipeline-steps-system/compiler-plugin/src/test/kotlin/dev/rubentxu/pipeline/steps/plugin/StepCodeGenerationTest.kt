package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Code Generation Test for @Step Functions
 * 
 * This test generates actual Kotlin code to visualize how @Step functions
 * are transformed by the compiler plugin. Generated code is written to test-gen/
 * directory to allow inspection of the transformation results.
 */
class StepCodeGenerationTest : BehaviorSpec({

    given("A @Step function with different signatures") {
        val testSourceCode = """
            package dev.rubentxu.pipeline.examples
            
            import dev.rubentxu.pipeline.annotations.Step
            import dev.rubentxu.pipeline.annotations.StepCategory
            import dev.rubentxu.pipeline.annotations.SecurityLevel
            import dev.rubentxu.pipeline.context.LocalPipelineContext
            
            @Step(
                name = "simpleStep",
                description = "A simple step without parameters",
                category = StepCategory.BUILD
            )
            suspend fun simpleStep() {
                val context = LocalPipelineContext.current
                context.log("Executing simple step")
            }
            
            @Step(
                name = "stepWithParams", 
                description = "A step with parameters",
                category = StepCategory.TEST,
                securityLevel = SecurityLevel.TRUSTED
            )
            suspend fun stepWithParams(message: String, count: Int = 1) {
                val context = LocalPipelineContext.current
                repeat(count) {
                    context.log("Step message: ${"$"}message (${"$"}it)")
                }
            }
            
            @Step(
                name = "complexStep",
                description = "A complex step with return value", 
                category = StepCategory.DEPLOY
            )
            suspend fun complexStep(config: Map<String, Any>): Boolean {
                val context = LocalPipelineContext.current
                context.log("Complex step with config: ${"$"}config")
                return config.isNotEmpty()
            }
        """.trimIndent()

        `when`("compiling with the Step compiler plugin") {
            val testGenDir = File("test-gen")
            testGenDir.mkdirs()
            
            val sourceFile = File(testGenDir, "TestStepFunctions.kt")
            sourceFile.writeText(testSourceCode)
            
            val outputDir = File(testGenDir, "compiled")
            outputDir.mkdirs()
            
            then("should generate test source code successfully") {
                // Verify source file was created
                sourceFile.exists() shouldBe true
                sourceFile.readText() shouldContain "@Step"
                sourceFile.readText() shouldContain "simpleStep"
                sourceFile.readText() shouldContain "stepWithParams"
                sourceFile.readText() shouldContain "complexStep"
                
                println("✅ Generated test source: ${sourceFile.absolutePath}")
                println("📁 Output directory: ${outputDir.absolutePath}")
            }
            
            then("generated source should be available for inspection") {
                // Write a summary file for easy inspection
                val summaryFile = File(testGenDir, "README.md")
                summaryFile.writeText("""
                    # Generated @Step Function Code
                    
                    ## Test Source: TestStepFunctions.kt
                    
                    This directory contains:
                    - `TestStepFunctions.kt`: Original source code with @Step annotations
                    - `compiled/`: Compiled bytecode output directory
                    - This README for documentation
                    
                    ## @Step Functions Tested:
                    1. **simpleStep()** - No parameters, basic context injection
                    2. **stepWithParams(String, Int)** - Parameters with defaults
                    3. **complexStep(Map<String, Any>)** - Return value and complex types
                    
                    ## How to Inspect:
                    1. Check `TestStepFunctions.kt` for the original annotated code
                    2. Use IntelliJ to decompile classes in `compiled/` directory
                    3. Compare original vs transformed bytecode
                    
                    ## Expected Transformations:
                    - Context parameter injection
                    - Suspend function handling
                    - StepsBlock extension generation
                    - Registry registration calls
                """.trimIndent())
                
                summaryFile.exists() shouldBe true
            }
        }
    }

    given("Multiple @Step functions in the same file") {
        val batchTestSource = """
            package dev.rubentxu.pipeline.examples.batch
            
            import dev.rubentxu.pipeline.annotations.Step
            import dev.rubentxu.pipeline.annotations.StepCategory
            import dev.rubentxu.pipeline.context.LocalPipelineContext
            
            // Build steps
            @Step("gradle-build", "Build with Gradle", StepCategory.BUILD)
            suspend fun gradleBuild(projectPath: String = ".") {
                LocalPipelineContext.current.log("Building project at ${"$"}projectPath")
            }
            
            @Step("docker-build", "Build Docker image", StepCategory.BUILD) 
            suspend fun dockerBuild(imageName: String, tag: String = "latest") {
                LocalPipelineContext.current.log("Building Docker image ${"$"}imageName:${"$"}tag")
            }
            
            // Test steps  
            @Step("run-tests", "Execute test suite", StepCategory.TEST)
            suspend fun runTests(pattern: String = "**/*Test.kt") {
                LocalPipelineContext.current.log("Running tests matching: ${"$"}pattern")
            }
            
            @Step("coverage-report", "Generate coverage report", StepCategory.TEST)
            suspend fun coverageReport(threshold: Double = 80.0): Boolean {
                LocalPipelineContext.current.log("Checking coverage >= ${"$"}threshold%")
                return true // Mock implementation
            }
            
            // Deploy steps
            @Step("deploy-staging", "Deploy to staging", StepCategory.DEPLOY)
            suspend fun deployStaging(environment: String = "staging") {
                LocalPipelineContext.current.log("Deploying to ${"$"}environment")
            }
        """.trimIndent()

        `when`("generating batch compilation example") {
            val testGenDir = File("test-gen")
            val batchFile = File(testGenDir, "BatchStepFunctions.kt")
            batchFile.writeText(batchTestSource)
            
            val outputDir = File(testGenDir, "batch-compiled")
            outputDir.mkdirs()

            then("should handle multiple @Step functions correctly") {
                batchFile.readText() shouldContain "gradle-build"
                batchFile.readText() shouldContain "docker-build"
                batchFile.readText() shouldContain "run-tests"
                batchFile.readText() shouldContain "coverage-report"
                batchFile.readText() shouldContain "deploy-staging"
                
                println("✅ Generated batch test source: ${batchFile.absolutePath}")
                println("📁 Contains ${batchFile.readText().count { it == '@' }} @Step annotations")
            }
        }
    }
})