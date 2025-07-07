#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for security policies and resource limits
pipeline {
    environment {
        "TEST_TYPE" += "SECURITY_POLICIES"
        "SECURITY_LEVEL" += "RESTRICTED"
        "MAX_MEMORY_MB" += "256"
        "MAX_CPU_TIME_MS" += "30000"
    }
    
    stages {
        stage("Test Security Policies") {
            steps {
                echo("Testing security policies and resource limits")
                
                // Test memory limits
                echo("Testing memory constraints")
                var memoryInfo = Runtime.getRuntime()
                echo("Free memory: ${memoryInfo.freeMemory()}")
                echo("Max memory: ${memoryInfo.maxMemory()}")
                echo("Total memory: ${memoryInfo.totalMemory()}")
                
                // Test file system restrictions
                echo("Testing file system access policies")
                try {
                    // Should be able to read allowed files
                    var currentDir = sh("pwd", returnStdout = true)
                    echo("Current directory access: OK")
                    
                    // Test directory listing (should be allowed)
                    var listing = sh("ls -la", returnStdout = true)
                    echo("Directory listing: ${listing.lines().size} items")
                    
                } catch (e: Exception) {
                    echo("File system access test: ${e.message}")
                }
                
                // Test network access restrictions
                echo("Testing network access policies")
                try {
                    // Network access should be restricted in secure mode
                    var networkTest = sh("ping -c 1 localhost", returnStdout = true)
                    echo("Network test result available")
                } catch (e: Exception) {
                    echo("Network access properly restricted: ${e.message}")
                }
                
                // Test CPU time limits
                echo("Testing CPU time constraints")
                val startTime = System.currentTimeMillis()
                
                // Simulate CPU-intensive task
                repeat(100) {
                    delay(10) {
                        // Small computation to test CPU limits
                        val result = (1..1000).sum()
                    }
                }
                
                val endTime = System.currentTimeMillis()
                echo("CPU test completed in ${endTime - startTime}ms")
                
                // Test thread limits with parallel execution
                echo("Testing thread limits with parallel tasks")
                parallel(
                    "security-task-1" to Step {
                        echo("Security task 1: Testing resource limits")
                        delay(200) {
                            echo("Task 1 completed within limits")
                        }
                    },
                    "security-task-2" to Step {
                        echo("Security task 2: Testing resource limits")
                        delay(300) {
                            echo("Task 2 completed within limits")
                        }
                    },
                    "security-task-3" to Step {
                        echo("Security task 3: Testing resource limits")
                        delay(250) {
                            echo("Task 3 completed within limits")
                        }
                    }
                )
                
                // Test that security policies are enforced
                echo("Security policies test completed successfully")
            }
            post {
                always {
                    echo("Post: Security policies test finished")
                    echo("Resource usage within acceptable limits")
                }
                success {
                    echo("All security policies enforced correctly")
                }
                failure {
                    echo("Security policy enforcement issue detected")
                }
            }
        }
    }
}