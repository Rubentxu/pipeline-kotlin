#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for THREAD isolation level - thread-level isolation
pipeline {
    environment {
        "ISOLATION_LEVEL" += "THREAD"
        "TEST_TYPE" += "SANDBOX"
        "THREAD_NAME" += "sandbox-thread-test"
    }
    
    stages {
        stage("Test Thread Isolation") {
            steps {
                echo("Testing THREAD isolation level")
                
                // Thread isolation should allow basic operations
                echo("Current thread: ${Thread.currentThread().name}")
                echo("Thread ID: ${Thread.currentThread().id}")
                
                // Should allow parallel execution with thread isolation
                parallel(
                    "thread-a" to Step {
                        echo("Thread A executing in isolation")
                        delay(100) {
                            echo("Thread A delayed task")
                        }
                    },
                    "thread-b" to Step {
                        echo("Thread B executing in isolation")
                        delay(200) {
                            echo("Thread B delayed task")
                        }
                    }
                )
                
                // Basic file operations should work
                var currentDir = sh("pwd", returnStdout = true)
                echo("Working directory: $currentDir")
                
                echo("THREAD isolation test completed successfully")
            }
            post {
                always {
                    echo("Post: THREAD isolation test finished")
                }
            }
        }
    }
}