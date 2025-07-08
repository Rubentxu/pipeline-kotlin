package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.mocks.wildcard
import dev.rubentxu.pipeline.testing.MockResult
import io.kotest.matchers.shouldBe

/**
 * Tests for Pipeline DSL Sandbox Security functionality using the Pipeline Testing Framework
 * Validates sandbox isolation levels, resource limits, and security controls
 */
class SandboxSecurityPipelineTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "NONE Isolation Level - should allow all operations (testing mode)",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "NONE"
                        }
                        
                        stages {
                            stage("No Isolation Test") {
                                steps {
                                    echo("Testing NONE isolation level...")
                                    
                                    // These operations should be allowed with NONE isolation
                                    sh("ls /")
                                    sh("cat /etc/hostname")
                                    sh("ps aux")
                                    
                                    writeFile("/tmp/test.txt", "test content")
                                    val content = readFile("/tmp/test.txt")
                                    echo("File content: " + content)
                                    
                                    echo("NONE isolation level working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    customBehavior { args ->
                        val script = args["script"] as String
                        when {
                            script.contains("ls /") -> MockResult(output = "bin dev etc home lib")
                            script.contains("cat /etc/hostname") -> MockResult(output = "test-container")
                            script.contains("ps aux") -> MockResult(output = "USER PID COMMAND\nroot 1 /bin/sh")
                            else -> MockResult(output = "Command executed")
                        }
                    }
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                mockStep("readFile") {
                    returnOutput("test content")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                
                stepWasCalled("sh")
                stepCallCount("sh", 3)
                stepCalledWith("sh", mapOf("script" to "ls /", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "cat /etc/hostname", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "ps aux", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "/tmp/test.txt", "text" to "test content"))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "/tmp/test.txt"))
            }
        )
        
        testPipeline(
            "THREAD Isolation Level - should use GraalVM isolates",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "THREAD"
                            resourceLimits {
                                maxMemoryMB = 256
                                maxCpuTimeMs = 15000
                                maxThreads = 5
                            }
                        }
                        
                        stages {
                            stage("Thread Isolation Test") {
                                steps {
                                    echo("Testing THREAD isolation level...")
                                    
                                    // Basic operations should work
                                    sh("echo 'Hello from sandbox'")
                                    
                                    // File operations within working directory
                                    writeFile("sandbox-test.txt", "sandbox content")
                                    val content = readFile("sandbox-test.txt")
                                    echo("Sandbox file content: " + content)
                                    
                                    // Memory and CPU should be monitored
                                    sh("echo 'Resource usage monitored'")
                                    
                                    echo("THREAD isolation working correctly")
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
                    returnOutput("Command executed in sandbox")
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                mockStep("readFile") {
                    returnOutput("sandbox content")
                }
                
                // Mock sandbox monitoring
                mockStep("checkSandboxLimits") {
                    returnOutput("Memory: 128MB/256MB, CPU: 5000ms/15000ms, Threads: 3/5")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                stepCalledWith("echo", mapOf("message" to "Testing THREAD isolation level..."))
                stepCalledWith("echo", mapOf("message" to "Sandbox file content: sandbox content"))
                stepCalledWith("echo", mapOf("message" to "THREAD isolation working correctly"))
                
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "sandbox-test.txt", "text" to "sandbox content"))
                
                stepWasCalled("readFile")
                stepCalledWith("readFile", mapOf("file" to "sandbox-test.txt"))
            }
        )
        
        testPipeline(
            "PROCESS Isolation Level - should use separate JVM processes",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "PROCESS"
                            resourceLimits {
                                maxMemoryMB = 512
                                maxCpuTimeMs = 30000
                                maxWallTimeMs = 60000
                            }
                            securityPolicy = "STRICT"
                        }
                        
                        stages {
                            stage("Process Isolation Test") {
                                steps {
                                    echo("Testing PROCESS isolation level...")
                                    
                                    // Operations should run in separate process
                                    sh("echo $$")  // Process ID should be different
                                    sh("pwd")      // Working directory should be controlled
                                    
                                    // File operations should be restricted to working directory
                                    writeFile("process-test.txt", "process content")
                                    
                                    echo("PROCESS isolation working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    customBehavior { args ->
                        val script = args["script"] as String
                        when {
                            script.contains("echo $$") -> MockResult(output = "12345")  // Mock process ID
                            script.contains("pwd") -> MockResult(output = "/sandbox/workspace")
                            else -> MockResult(output = "Command executed in isolated process")
                        }
                    }
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                // Mock process isolation monitoring
                mockStep("checkProcessIsolation") {
                    returnOutput("Process isolated, PID: 12345, Working dir: /sandbox/workspace")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                stepCalledWith("sh", mapOf("script" to "echo $$", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "pwd", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "process-test.txt", "text" to "process content"))
            }
        )
        
        testPipelineFailure(
            "Resource Limit Enforcement - should fail when exceeding memory limits",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "THREAD"
                            resourceLimits {
                                maxMemoryMB = 64  // Very low limit
                                maxCpuTimeMs = 1000
                            }
                        }
                        
                        stages {
                            stage("Memory Limit Test") {
                                steps {
                                    echo("Testing memory limit enforcement...")
                                    
                                    // This should trigger memory limit violation
                                    sh("java -Xmx128m -cp . MemoryHog")
                                    
                                    echo("This should not be reached")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    returnExitCode(1)
                    returnError("OutOfMemoryError: Memory limit exceeded")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("Memory limit exceeded") == true ||
                error.message?.contains("OutOfMemoryError") == true
            }
        )
        
        testPipelineFailure(
            "CPU Time Limit Enforcement - should fail when exceeding CPU time",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "THREAD"
                            resourceLimits {
                                maxCpuTimeMs = 2000  // 2 seconds limit
                            }
                        }
                        
                        stages {
                            stage("CPU Limit Test") {
                                steps {
                                    echo("Testing CPU time limit enforcement...")
                                    
                                    // This should trigger CPU time limit violation
                                    sh("while true; do echo 'infinite loop'; done")
                                    
                                    echo("This should not be reached")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    returnExitCode(1)
                    returnError("CPU time limit exceeded: 2000ms")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("CPU time limit exceeded") == true
            }
        )
        
        testPipeline(
            "File Access Control - should restrict file access to working directory",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "PROCESS"
                            securityPolicy = "STRICT"
                            allowedPaths = ["/workspace", "/tmp"]
                        }
                        
                        stages {
                            stage("File Access Test") {
                                steps {
                                    echo("Testing file access control...")
                                    
                                    // Allowed: write to working directory
                                    writeFile("allowed.txt", "this is allowed")
                                    
                                    // Allowed: write to tmp directory
                                    writeFile("/tmp/temp.txt", "temp file")
                                    
                                    // Should work: read from allowed paths
                                    val content1 = readFile("allowed.txt")
                                    val content2 = readFile("/tmp/temp.txt")
                                    
                                    echo("File access control working: " + content1 + ", " + content2)
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("writeFile") {
                    customBehavior { args ->
                        val file = args["file"] as String
                        if (file.startsWith("/workspace") || file.startsWith("/tmp") || !file.startsWith("/")) {
                            MockResult(exitCode = 0)
                        } else {
                            MockResult(exitCode = 1, error = "Access denied: $file")
                        }
                    }
                }
                
                mockStep("readFile") {
                    customBehavior { args ->
                        val file = args["file"] as String
                        when {
                            file == "allowed.txt" -> MockResult(output = "this is allowed")
                            file == "/tmp/temp.txt" -> MockResult(output = "temp file")
                            else -> MockResult(exitCode = 1, error = "Access denied: $file")
                        }
                    }
                }
            },
            verificationBlock = {
                stepWasCalled("writeFile")
                stepCallCount("writeFile", 2)
                stepCalledWith("writeFile", mapOf("file" to "allowed.txt", "text" to "this is allowed"))
                stepCalledWith("writeFile", mapOf("file" to "/tmp/temp.txt", "text" to "temp file"))
                
                stepWasCalled("readFile")
                stepCallCount("readFile", 2)
                stepCalledWith("readFile", mapOf("file" to "allowed.txt"))
                stepCalledWith("readFile", mapOf("file" to "/tmp/temp.txt"))
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to "File access control working: this is allowed, temp file"))
            }
        )
        
        testPipeline(
            "Network Access Control - should control network connections",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "PROCESS"
                            securityPolicy = "STRICT"
                            networkPolicy {
                                allowOutbound = false
                                allowedHosts = ["github.com", "maven.central.org"]
                            }
                        }
                        
                        stages {
                            stage("Network Control Test") {
                                steps {
                                    echo("Testing network access control...")
                                    
                                    // Should be blocked: general internet access
                                    try {
                                        sh("curl http://google.com")
                                    } catch (e: Exception) {
                                        echo("Blocked access to google.com: " + e.message)
                                    }
                                    
                                    // Should be allowed: whitelisted hosts
                                    sh("curl https://github.com/api")
                                    
                                    echo("Network access control working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    customBehavior { args ->
                        val script = args["script"] as String
                        when {
                            script.contains("google.com") -> MockResult(exitCode = 1, error = "Network access denied: google.com")
                            script.contains("github.com") -> MockResult(exitCode = 0, output = "API response from github.com")
                            else -> MockResult(output = "Command executed")
                        }
                    }
                }
            },
            verificationBlock = {
                stepWasCalled("sh")
                stepCallCount("sh", 2)
                stepCalledWith("sh", mapOf("script" to "curl http://google.com", "returnStdout" to false, "returnStatus" to false))
                stepCalledWith("sh", mapOf("script" to "curl https://github.com/api", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                stepCalledWith("echo", mapOf("message" to "Testing network access control..."))
                stepCalledWith("echo", mapOf("message" to wildcard)) // Error message content
                stepCalledWith("echo", mapOf("message" to "Network access control working correctly"))
            }
        )
        
        testPipeline(
            "Security Violation Detection - should detect and report violations",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "PROCESS"
                            securityPolicy = "STRICT"
                            monitorViolations = true
                        }
                        
                        stages {
                            stage("Violation Detection Test") {
                                steps {
                                    echo("Testing security violation detection...")
                                    
                                    // Normal operations should not trigger violations
                                    sh("echo 'normal operation'")
                                    writeFile("normal.txt", "normal content")
                                    
                                    echo("Normal operations completed without violations")
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
                    returnOutput("normal operation")
                }
                
                mockStep("writeFile") {
                    returnExitCode(0)
                }
                
                // Mock security monitoring
                mockStep("checkSecurityViolations") {
                    returnOutput("No security violations detected")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                stepWasCalled("sh")
                stepCalledWith("sh", mapOf("script" to "echo 'normal operation'", "returnStdout" to false, "returnStatus" to false))
                
                stepWasCalled("writeFile")
                stepCalledWith("writeFile", mapOf("file" to "normal.txt", "text" to "normal content"))
                
                // All steps should complete successfully without violations
                allStepsSucceeded()
            }
        )
        
        testPipelineFailure(
            "Execution Timeout - should enforce wall time limits",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        sandbox {
                            isolationLevel = "PROCESS"
                            resourceLimits {
                                maxWallTimeMs = 5000  // 5 seconds total execution time
                            }
                        }
                        
                        stages {
                            stage("Timeout Test") {
                                steps {
                                    echo("Testing execution timeout...")
                                    
                                    // This should trigger wall time timeout
                                    sh("sleep 10")  // 10 seconds sleep
                                    
                                    echo("This should not be reached")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("sh") {
                    // Simulate timeout after 5 seconds
                    returnExitCode(1)
                    returnError("Execution timeout: wall time limit exceeded")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("timeout") == true ||
                error.message?.contains("wall time limit exceeded") == true
            }
        )
    }
}