package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.mocks.wildcard
import dev.rubentxu.pipeline.testing.MockResult
import io.kotest.matchers.shouldBe

/**
 * Tests for Pipeline DSL Step Extensions using the Pipeline Testing Framework
 * Validates built-in and custom step extensions functionality
 */
class StepExtensionPipelineTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "Built-in Step Extensions - should execute sh, echo, and file operations",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Step Extensions Test") {
                                steps {
                                    sh("ls -la")
                                    echo("Testing step extensions")
                                    
                                    writeFile("test.txt", "Hello World")
                                    val exists = fileExists("test.txt")
                                    echo("File exists: " + exists)
                                    
                                    if (exists) {
                                        val content = readFile("test.txt")
                                        echo("File content: " + content)
                                    }
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("total 8\ndrwxr-xr-x 2 user user 4096 Jan 1 12:00 .")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                mockStep("fileExists") {
                    returnOutput("true")
                }
                
                mockStep("readFile") {
                    returnOutput("Hello World")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "ls -la", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                stepCalledWith("echo", mapOf("message" to "Testing step extensions"))
                stepCalledWith("echo", mapOf("message" to "File exists: true"))
                stepCalledWith("echo", mapOf("message" to "File content: Hello World"))
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "test.txt", "text" to "Hello World"))
                
                stepWasCalled("fileExists")
                stepCalledWith("fileExists", mapOf("file" to "test.txt"))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "test.txt"))
                
                stepsCalledInOrder("sh", "echo", "writeFile", "fileExists", "echo", "readFile", "echo")
            }
        )
        
        testPipeline(
            "Retry Step Extension - should handle retry logic with failures",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Retry Test") {
                                steps {
                                    retry(3) {
                                        sh("./flaky-command")
                                    }
                                    echo("Retry completed")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("Command succeeded")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("retry") {
                    returnExitCode(0)
                }
            },
            verificationBlock = {
                stepWasCalled("retry")
                stepCalledWith("retry", mapOf("times" to 3, "attempt" to 1))
                
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "./flaky-command", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCalledWith("echo", mapOf("message" to "Retry completed"))
            }
        )
        
        testPipeline(
            "Directory Step Extension - should change directory context",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Directory Test") {
                                steps {
                                    sh("pwd")
                                    dir("subproject") {
                                        sh("pwd")
                                        sh("ls -la")
                                    }
                                    sh("pwd")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("/workspace")
                }
                
                mockStep("dir") {
                    returnExitCode(0)
                }
            },
            verificationBlock = {
                stepWasCalled("dir")
                stepCalledWith("dir", mapOf("path" to "subproject"))
                
                stepWasCalled("sh")
                stepCallCount("sh", 4) // pwd before dir, pwd and ls inside dir, pwd after dir
                
                stepCalledWith("sh", mapOf("script" to "pwd", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "ls -la", "returnStdout" to false, "returnStatus" to false))
            }
        )
        
        testPipeline(
            "Parallel Step Extension - should execute steps in parallel",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Parallel Test") {
                                steps {
                                    parallel(
                                        "Unit Tests" to {
                                            sh("./gradlew test")
                                            echo("Unit tests completed")
                                        },
                                        "Integration Tests" to {
                                            sh("./gradlew integrationTest")
                                            echo("Integration tests completed")
                                        },
                                        "Linting" to {
                                            sh("./gradlew ktlintCheck")
                                            echo("Linting completed")
                                        }
                                    )
                                    echo("All parallel tasks completed")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("BUILD SUCCESSFUL")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("parallel") {
                    returnExitCode(0)
                }
            },
            verificationBlock = {
                stepWasCalled("parallel")
                stepCalledWith("parallel", mapOf("branches" to listOf("Unit Tests", "Integration Tests", "Linting")))
                
                stepWasCalled("sh")
                stepCallCount("sh", 3)
                stepCalledWith("sh", mapOf("script" to "./gradlew test", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew integrationTest", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "./gradlew ktlintCheck", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCallCount("echo", 4) // 3 inside parallel branches + 1 after parallel
                stepCalledWith("echo", mapOf("message" to "All parallel tasks completed"))
            }
        )
        
        testPipeline(
            "Environment Variable Access - should handle environment variables",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        environment {
                            "BUILD_NUMBER" += "123"
                            "BRANCH_NAME" += "main"
                        }
                        
                        stages {
                            stage("Environment Test") {
                                steps {
                                    echo("Build number: " + env["BUILD_NUMBER"])
                                    echo("Branch: " + env["BRANCH_NAME"])
                                    
                                    sh("echo BUILD_NUMBER=" + env["BUILD_NUMBER"])
                                    
                                    setEnv("CUSTOM_VAR", "test-value")
                                    echo("Custom variable: " + env["CUSTOM_VAR"])
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    returnExitCode(0)
                    returnOutput("BUILD_NUMBER=123")
                }
                
                mockStep("setEnv") {
                    returnExitCode(0)
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                stepCalledWith("echo", mapOf("message" to "Build number: 123"))
                stepCalledWith("echo", mapOf("message" to "Branch: main"))
                stepCalledWith("echo", mapOf("message" to "Custom variable: test-value"))
                
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "echo BUILD_NUMBER=123", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("setEnv")
                stepCalledWith("setEnv", mapOf("name" to "CUSTOM_VAR", "value" to "test-value"))
            }
        )
        
        testPipeline(
            "Return Values - should handle step return values correctly",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Return Values Test") {
                                steps {
                                    val output = sh("git rev-parse HEAD", returnStdout = true)
                                    echo("Git commit: " + output)
                                    
                                    val status = sh("test -f build.gradle.kts", returnStatus = true)
                                    echo("File check status: " + status)
                                    
                                    val content = readFile("version.txt")
                                    echo("Version: " + content)
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    customBehavior { args ->
                        val returnStdout = args["returnStdout"] as? Boolean ?: false
                        val returnStatus = args["returnStatus"] as? Boolean ?: false
                        
                        when {
                            returnStdout -> MockResult(output = "abc123def456")
                            returnStatus -> MockResult(exitCode = 0)
                            else -> MockResult(exitCode = 0)
                        }
                    }
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("readFile") {
                    returnOutput("1.0.0")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                stepCalledWith("sh", mapOf("script" to "git rev-parse HEAD", "returnStdout" to true, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "test -f build.gradle.kts", "returnStdout" to false, "returnStatus" to true))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "version.txt"))
                
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                stepCalledWith("echo", mapOf("message" to "Git commit: abc123def456"))
                stepCalledWith("echo", mapOf("message" to "File check status: 0"))
                stepCalledWith("echo", mapOf("message" to "Version: 1.0.0"))
            }
        )
        
        testPipeline(
            "Error Handling - should handle step failures appropriately",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Error Handling Test") {
                                steps {
                                    try {
                                        sh("failing-command")
                                    } catch (e: Exception) {
                                        echo("Command failed: " + e.message)
                                    }
                                    
                                    echo("Continuing after error...")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("sh") {
                    returnExitCode(1)
                    returnError("Command not found")
                }
                
                mockStep("echo") {
                    returnOutput("")
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "failing-command", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to wildcard)) // Error message contains dynamic content
                stepCalledWith("echo", mapOf("message" to "Continuing after error..."))
            }
        )
    }
}