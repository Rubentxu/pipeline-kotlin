package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.MockResult
import io.kotest.matchers.shouldBe

/**
 * Simple test to verify the Pipeline Testing Framework works correctly
 * Validates basic step extensions functionality
 */
class SimpleStepExtensionTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "Basic Step Test - should execute sh and echo steps",
            testBlock = {
                pipelineScriptContent(simplePipelineScript("""
                    sh("ls -la")
                    echo("Hello from pipeline test")
                    writeFile("test.txt", "content")
                    val content = readFile("test.txt")
                    echo("File content: " + content)
                """))
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("total 8")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                mockStep("readFile") {
                    returnOutput("content")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "ls -la", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to "Hello from pipeline test"))
                stepCalledWith("echo", mapOf("message" to "File content: content"))
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "test.txt", "text" to "content"))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "test.txt"))
                
                stepsCalledInOrder("sh", "echo", "writeFile", "readFile", "echo")
            }
        )
        
        testPipeline(
            "Parallel Execution Test - should handle parallel steps",
            testBlock = {
                pipelineScriptContent(parallelPipelineScript())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("SUCCESS")
                }
                
                mockStep("parallel") {
                    returnExitCode(0)
                }
            },
            verificationBlock = {
                stepWasCalled("parallel")
                stepWasCalled("sh")
                stepCallCount("sh", 3)
            }
        )
        
        testPipelineFailure(
            "Error Handling Test - should handle command failures",
            testBlock = {
                pipelineScriptContent(simplePipelineScript("""
                    sh("failing-command")
                    echo("This should not be reached")
                """))
                
                mockStep("sh") {
                    returnExitCode(1)
                    returnError("Command failed")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("failed") == true
            }
        )
        
        testPipelineExecution(
            "Build Pipeline Test - should execute complete build pipeline",
            testBlock = {
                pipelineScriptContent(buildPipelineScript())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("BUILD SUCCESSFUL")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            }
        )
    }
}