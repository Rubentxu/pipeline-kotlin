package dev.rubentxu.pipeline.testing

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.mocks.wildcard
import io.kotest.matchers.shouldBe

/**
 * Example test demonstrating the Pipeline Testing Framework
 * Shows how to test Pipeline DSL scripts with mocked steps
 */
class PipelineTestFrameworkExampleTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "Build Stage - should execute gradle build successfully",
            testBlock = {
                pipelineScriptContent(buildPipelineScript())
                
                // Mock the sh step to simulate successful build
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("BUILD SUCCESSFUL")
                }
                
                // Mock the echo step
                mockStep("echo") {
                    returnOutput("")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                
                stepCalledWith("sh", mapOf("script" to "./gradlew clean", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew build", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCalledWith("echo", mapOf("message" to "Build completed successfully"))
                
                stepsCalledInOrder("sh", "sh", "echo")
            }
        )
        
        testPipeline(
            "File Operations - should read and write files correctly",
            testBlock = {
                pipelineScriptContent(fileOperationsPipelineScript())
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                mockStep("fileExists") {
                    returnOutput("true")
                }
                
                mockStep("readFile") {
                    returnOutput("apply plugin: 'java'")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            },
            verificationBlock = {
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "build.gradle", "text" to "apply plugin: 'java'"))
                
                stepWasCalled("fileExists")
                stepCalledWith("fileExists", mapOf("file" to "build.gradle"))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "build.gradle"))
                
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to "Build file exists"))
                stepCalledWith("echo", mapOf("message" to "Build file length: 21"))
            }
        )
        
        testPipeline(
            "Parallel Execution - should run tasks in parallel",
            testBlock = {
                pipelineScriptContent(parallelPipelineScript())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("SUCCESS")
                }
                
                mockStep("parallel") {
                    returnExitCode(0)
                    returnOutput("Parallel execution completed")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCallCount("sh", 3)
                
                stepCalledWith("sh", mapOf("script" to "./gradlew test", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew integrationTest", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew checkstyleMain", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("parallel")
            }
        )
        
        testPipeline(
            "Error Handling - should use retry and dir steps",
            testBlock = {
                pipelineScriptContent(errorHandlingPipelineScript())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("SUCCESS")
                }
                
                mockStep("retry") {
                    returnExitCode(0)
                    returnOutput("Retry completed")
                }
                
                mockStep("dir") {
                    returnExitCode(0)
                    returnOutput("Directory changed")
                }
            },
            verificationBlock = {
                stepWasCalled("retry")
                stepWasCalled("dir")
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                
                stepCalledWith("sh", mapOf("script" to "./gradlew build", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew test", "returnStdout" to false, "returnStatus" to false))
            }
        )
        
        testPipeline(
            "Wildcard Arguments - should support flexible argument matching",
            testBlock = {
                pipelineScriptContent(simplePipelineScript("""
                    sh("./gradlew clean")
                    sh("./gradlew build --info")
                    echo("Build finished at: " + System.currentTimeMillis())
                """))
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("SUCCESS")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            },
            verificationBlock = {
                stepCallCount("sh", 2)
                stepCallCount("echo", 1)
                
                // Use wildcards for flexible matching
                stepCalledWith("sh", mapOf("script" to "./gradlew clean", "returnStdout" to wildcard, "returnStatus" to wildcard))
                stepCalledWith("sh", mapOf("script" to "./gradlew build --info", "returnStdout" to false, "returnStatus" to false))
                
                // Verify echo was called with dynamic content using wildcard
                verifyStepInvocation("echo") {
                    callCount(1)
                    calledWith(mapOf("message" to wildcard)) // Accept any message
                }
            }
        )
        
        testPipelineFailure(
            "Failed Build - should handle build failure correctly",
            testBlock = {
                pipelineScriptContent(simplePipelineScript("""
                    sh("./gradlew build")
                    error("Build failed!")
                """))
                
                mockStep("sh") {
                    returnExitCode(1)
                    returnError("Compilation failed")
                }
                
                mockStep("error") {
                    returnExitCode(1)
                    returnError("Build failed!")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("Build failed!") == true
            }
        )
        
        testPipelineExecution(
            "Environment Variables - should handle env changes correctly",
            testBlock = {
                pipelineScriptContent(simplePipelineScript("""
                    sh("export MY_VAR=test_value")
                    echo("Variable set")
                """))
                
                mockStep("sh") {
                    returnExitCode(0)
                    setEnvironmentChanges(mapOf("MY_VAR" to "test_value"))
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            }
        )
    }
}