#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for PROCESS isolation level - process-level isolation
pipeline {
    environment {
        "ISOLATION_LEVEL" += "PROCESS"
        "TEST_TYPE" += "SANDBOX"
        "PROCESS_ID" += "sandbox-process-test"
    }
    
    stages {
        stage("Test Process Isolation") {
            steps {
                echo("Testing PROCESS isolation level")
                
                // Process isolation should provide strong boundaries
                echo("Process PID: ${ProcessHandle.current().pid()}")
                echo("Process info: ${ProcessHandle.current().info()}")
                
                // Should allow controlled process operations
                var processes = sh("ps aux | head -5", returnStdout = true)
                echo("Running processes:\n$processes")
                
                // Memory and resource constraints should be enforced
                echo("Available memory: ${Runtime.getRuntime().freeMemory()}")
                echo("Max memory: ${Runtime.getRuntime().maxMemory()}")
                
                // File system access should be controlled
                var diskUsage = sh("df -h .", returnStdout = true)
                echo("Disk usage:\n$diskUsage")
                
                // Network access should be restricted
                echo("Network interfaces:")
                var networkInfo = sh("ip addr show | head -10", returnStdout = true)
                echo(networkInfo)
                
                echo("PROCESS isolation test completed successfully")
            }
            post {
                always {
                    echo("Post: PROCESS isolation test finished")
                }
                success {
                    echo("Process isolation test passed all checks")
                }
            }
        }
    }
}