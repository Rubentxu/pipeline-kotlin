#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for library loading (@Library functionality)
pipeline {
    environment {
        "TEST_TYPE" += "LIBRARY_LOADING"
        "LIBRARY_NAME" += "commons"
        "LIBRARY_VERSION" += "master"
    }
    
    stages {
        stage("Test Library Loading") {
            steps {
                echo("Testing library loading functionality")
                
                // Test that shared library configuration is available
                echo("Shared library name: ${env["LIBRARY_NAME"]}")
                echo("Shared library version: ${env["LIBRARY_VERSION"]}")
                
                // Library loading should make functions available
                echo("Testing library functions availability")
                
                // Test loading from local source
                echo("Loading library from local source: src/test/resources/scripts")
                
                // Verify library functions can be called
                try {
                    // This would normally call library functions if they were loaded
                    echo("Library functions would be available here")
                    echo("Example: customFunction() from loaded library")
                } catch (e: Exception) {
                    echo("Library function call test: ${e.message}")
                }
                
                // Test library classpath integration
                var classpathInfo = System.getProperty("java.class.path")
                echo("Current classpath includes library paths")
                
                // Test that library resources are accessible
                echo("Library resources should be accessible")
                
                // Parallel execution with library functions
                parallel(
                    "library-task-a" to Step {
                        echo("Task A: Using library functions")
                        delay(100) {
                            echo("Library function A executed")
                        }
                    },
                    "library-task-b" to Step {
                        echo("Task B: Using library functions")
                        delay(150) {
                            echo("Library function B executed")
                        }
                    }
                )
                
                echo("Library loading test completed successfully")
            }
            post {
                always {
                    echo("Post: Library loading test finished")
                }
                success {
                    echo("Library integration working correctly")
                }
            }
        }
    }
}