#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for CLASSLOADER isolation level - classloader isolation
pipeline {
    environment {
        "ISOLATION_LEVEL" += "CLASSLOADER"
        "TEST_TYPE" += "SANDBOX"
    }
    
    stages {
        stage("Test ClassLoader Isolation") {
            steps {
                echo("Testing CLASSLOADER isolation level")
                
                // ClassLoader isolation should provide controlled class loading
                echo("ClassLoader: ${this::class.java.classLoader}")
                echo("Context ClassLoader: ${Thread.currentThread().contextClassLoader}")
                
                // Should allow script execution with isolated classloader
                echo("Running isolated script operations")
                
                // Basic operations should work
                var timestamp = sh("date", returnStdout = true)
                echo("Current time: $timestamp")
                
                // Environment variables should be accessible
                echo("Test isolation level: ${env["ISOLATION_LEVEL"]}")
                
                // Parallel execution with classloader isolation
                parallel(
                    "isolated-a" to Step {
                        echo("ClassLoader A: ${Thread.currentThread().contextClassLoader}")
                        delay(50) {
                            echo("Isolated execution A completed")
                        }
                    },
                    "isolated-b" to Step {
                        echo("ClassLoader B: ${Thread.currentThread().contextClassLoader}")
                        delay(100) {
                            echo("Isolated execution B completed")
                        }
                    }
                )
                
                echo("CLASSLOADER isolation test completed successfully")
            }
            post {
                always {
                    echo("Post: CLASSLOADER isolation test finished")
                }
            }
        }
    }
}