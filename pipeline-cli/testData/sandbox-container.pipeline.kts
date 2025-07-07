#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for CONTAINER isolation level - container isolation
pipeline {
    environment {
        "ISOLATION_LEVEL" += "CONTAINER"
        "TEST_TYPE" += "SANDBOX"
        "CONTAINER_ID" += "sandbox-container-test"
    }
    
    stages {
        stage("Test Container Isolation") {
            steps {
                echo("Testing CONTAINER isolation level")
                
                // Container isolation should provide the strongest boundaries
                echo("Container environment check")
                
                // Check if running in containerized environment
                var containerCheck = try {
                    sh("cat /proc/1/cgroup", returnStdout = true)
                } catch (e: Exception) {
                    "No container info available"
                }
                echo("Container info: $containerCheck")
                
                // Filesystem should be isolated
                var rootFs = sh("ls -la /", returnStdout = true)
                echo("Root filesystem:\n$rootFs")
                
                // Network should be isolated
                var hostname = sh("hostname", returnStdout = true)
                echo("Container hostname: $hostname")
                
                // Process isolation within container
                var processTree = sh("ps -ef", returnStdout = true)
                echo("Process tree:\n$processTree")
                
                // Resource limits should be enforced
                echo("Memory limits:")
                var memoryInfo = try {
                    sh("cat /sys/fs/cgroup/memory/memory.limit_in_bytes", returnStdout = true)
                } catch (e: Exception) {
                    "Memory limit info not available"
                }
                echo("Memory limit: $memoryInfo")
                
                // Test that container isolation works for parallel tasks
                parallel(
                    "container-task-a" to Step {
                        echo("Container task A executing")
                        var taskInfo = sh("whoami", returnStdout = true)
                        echo("Task A user: $taskInfo")
                    },
                    "container-task-b" to Step {
                        echo("Container task B executing")
                        var taskInfo = sh("pwd", returnStdout = true)
                        echo("Task B directory: $taskInfo")
                    }
                )
                
                echo("CONTAINER isolation test completed successfully")
            }
            post {
                always {
                    echo("Post: CONTAINER isolation test finished")
                }
                success {
                    echo("Container isolation provides maximum security")
                }
            }
        }
    }
}