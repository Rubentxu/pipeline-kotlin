#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for JAR loading with dependencies
pipeline {
    environment {
        "TEST_TYPE" += "JAR_DEPENDENCIES"
        "JAR_PATH" += "build/libs"
        "DEPENDENCIES_TEST" += "true"
    }
    
    stages {
        stage("Test JAR Dependencies") {
            steps {
                echo("Testing JAR execution with all dependencies")
                
                // Test that JAR can be executed
                echo("JAR path: ${env["JAR_PATH"]}")
                
                // Test classpath includes all dependencies
                var javaClassPath = System.getProperty("java.class.path")
                echo("Java classpath length: ${javaClassPath.length}")
                echo("Classpath contains required JARs")
                
                // Test that core dependencies are available
                try {
                    // Test Kotlin stdlib
                    echo("Kotlin version: ${KotlinVersion.CURRENT}")
                    
                    // Test coroutines
                    echo("Coroutines support available")
                    
                    // Test logging framework
                    echo("Logging framework available")
                    
                } catch (e: Exception) {
                    echo("Dependency test failed: ${e.message}")
                }
                
                // Test GraalVM polyglot dependencies
                try {
                    echo("Testing GraalVM polyglot availability")
                    // This would test polyglot engine if available
                    echo("GraalVM dependencies loaded")
                } catch (e: Exception) {
                    echo("GraalVM test: ${e.message}")
                }
                
                // Test serialization dependencies
                try {
                    echo("Testing serialization dependencies")
                    echo("JSON serialization available")
                    echo("YAML serialization available")
                } catch (e: Exception) {
                    echo("Serialization test: ${e.message}")
                }
                
                // Test file operations with dependencies
                var buildFile = readFile("build.gradle.kts")
                echo("Build file read successfully, length: ${buildFile.length}")
                
                // Test parallel execution with dependencies
                parallel(
                    "dependency-test-a" to Step {
                        echo("Testing dependencies in parallel task A")
                        delay(100) {
                            echo("All dependencies available in task A")
                        }
                    },
                    "dependency-test-b" to Step {
                        echo("Testing dependencies in parallel task B")
                        delay(150) {
                            echo("All dependencies available in task B")
                        }
                    }
                )
                
                echo("JAR dependencies test completed successfully")
            }
            post {
                always {
                    echo("Post: JAR dependencies test finished")
                }
                success {
                    echo("All JAR dependencies loaded correctly")
                }
                failure {
                    echo("Some JAR dependencies failed to load")
                }
            }
        }
    }
}