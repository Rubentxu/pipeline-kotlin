#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

// Test pipeline for NONE isolation level - no security restrictions
pipeline {
    environment {
        "ISOLATION_LEVEL" += "NONE"
        "TEST_TYPE" += "SANDBOX"
    }
    
    stages {
        stage("Test No Isolation") {
            steps {
                echo("Testing NONE isolation level")
                
                // Should allow access to system properties
                echo("Java version: ${System.getProperty("java.version")}")
                echo("User home: ${System.getProperty("user.home")}")
                
                // Should allow file system access
                var files = sh("ls -la", returnStdout = true)
                echo("Directory listing:\n$files")
                
                // Should allow environment variable access
                echo("PATH: ${System.getenv("PATH")}")
                
                echo("NONE isolation test completed successfully")
            }
            post {
                always {
                    echo("Post: NONE isolation test finished")
                }
            }
        }
    }
}