#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for specific DSL features
pipeline {
    environment {
        "TEST_TYPE" += "DSL_FEATURES"
        "FEATURE_TEST" += "comprehensive"
        "PARALLEL_ENABLED" += "true"
        "RETRY_ENABLED" += "true"
    }
    
    stages {
        stage("Test DSL Parallelization") {
            steps {
                echo("Testing parallel execution capabilities")
                
                // Test complex parallel execution with multiple branches
                parallel(
                    "feature-a" to Step {
                        echo("Feature A: Testing echo functionality")
                        delay(200) {
                            echo("Feature A: Delayed execution completed")
                        }
                        var result = sh("echo 'Feature A output'", returnStdout = true)
                        echo("Feature A shell result: $result")
                    },
                    "feature-b" to Step {
                        echo("Feature B: Testing file operations")
                        delay(300) {
                            echo("Feature B: File operations test")
                        }
                        var pwd = sh("pwd", returnStdout = true)
                        echo("Feature B working dir: $pwd")
                    },
                    "feature-c" to Step {
                        echo("Feature C: Testing environment variables")
                        delay(100) {
                            echo("Feature C: Environment test")
                        }
                        echo("Feature C test type: ${env["TEST_TYPE"]}")
                        echo("Feature C parallel enabled: ${env["PARALLEL_ENABLED"]}")
                    }
                )
                
                echo("Parallel execution test completed successfully")
            }
        }
        
        stage("Test DSL Retry Mechanism") {
            steps {
                echo("Testing retry functionality")
                
                // Test retry with different scenarios
                retry(3) {
                    echo("Retry attempt - this should succeed")
                    delay(100) {
                        echo("Retry delay completed")
                    }
                    
                    // Simulate a task that might fail
                    var timestamp = sh("date +%s", returnStdout = true)
                    echo("Retry timestamp: $timestamp")
                }
                
                echo("Retry mechanism test completed successfully")
            }
        }
        
        stage("Test DSL Environment Variables") {
            steps {
                echo("Testing environment variable handling")
                
                // Test environment variable access and modification
                echo("Original TEST_TYPE: ${env["TEST_TYPE"]}")
                echo("Original FEATURE_TEST: ${env["FEATURE_TEST"]}")
                
                // Test setting new environment variables
                env["DYNAMIC_VAR"] = "dynamic_value"
                env["COMPUTED_VAR"] = "computed_${System.currentTimeMillis()}"
                
                echo("Dynamic variable: ${env["DYNAMIC_VAR"]}")
                echo("Computed variable: ${env["COMPUTED_VAR"]}")
                
                // Test environment variables in shell commands
                var envTest = sh("echo 'Env test: ${'$'}TEST_TYPE'", returnStdout = true)
                echo("Shell environment test: $envTest")
                
                echo("Environment variables test completed successfully")
            }
        }
        
        stage("Test DSL File Operations") {
            steps {
                echo("Testing file operations")
                
                // Test file reading
                try {
                    var buildContent = readFile("build.gradle.kts")
                    echo("Build file length: ${buildContent.length}")
                    echo("Build file contains 'plugins': ${buildContent.contains("plugins")}")
                } catch (e: Exception) {
                    echo("Build file read test: ${e.message}")
                }
                
                // Test shell commands with file output
                var listing = sh("ls -la", returnStdout = true)
                echo("Directory listing lines: ${listing.lines().size}")
                
                // Test working directory operations
                var currentDir = sh("pwd", returnStdout = true)
                echo("Current directory: $currentDir")
                
                echo("File operations test completed successfully")
            }
        }
        
        stage("Test DSL Post Actions") {
            steps {
                echo("Testing post action mechanisms")
                
                // Simulate different scenarios for post actions
                delay(200) {
                    echo("Main stage work completed")
                }
                
                echo("Post actions will be tested in post block")
            }
            post {
                always {
                    echo("Post Always: This should always execute")
                    echo("Post Always: Stage completed at ${System.currentTimeMillis()}")
                }
                success {
                    echo("Post Success: Stage completed successfully")
                    echo("Post Success: All DSL features working")
                }
                failure {
                    echo("Post Failure: This should not execute for successful stage")
                }
            }
        }
    }
    
    post {
        always {
            echo("Pipeline Post Always: DSL features test completed")
            echo("Pipeline Post Always: All stages executed")
            
            // Summary of tested features
            echo("=== DSL Features Test Summary ===")
            echo("✓ Parallel execution with multiple branches")
            echo("✓ Retry mechanism with delays")
            echo("✓ Environment variable handling")
            echo("✓ File operations and shell commands")
            echo("✓ Post action mechanisms")
            echo("=== End Summary ===")
        }
        success {
            echo("Pipeline Post Success: All DSL features passed")
            echo("Pipeline Post Success: Ready for production use")
        }
        failure {
            echo("Pipeline Post Failure: Some DSL features failed")
            echo("Pipeline Post Failure: Review test output")
        }
    }
}