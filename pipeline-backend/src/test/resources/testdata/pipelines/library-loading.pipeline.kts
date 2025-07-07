#!/usr/bin/env kotlin

pipeline {
    agent {
        docker("openjdk:21")
    }
    
    libraries {
        source("local", "/path/to/database-utils.jar")
        source("git", "https://github.com/company/build-tools.git") {
            branch = "main"
            path = "build/libs"
        }
        source("maven", "com.company:pipeline-extensions:1.0.0")
    }
    
    environment {
        "DATABASE_URL" += "postgresql://localhost:5432/testdb"
        "BUILD_PROFILE" += "production"
    }
    
    stages {
        stage("Setup") {
            steps {
                echo("Setting up libraries and dependencies...")
                
                // Verify library loading
                checkLibraries()
                
                echo("Libraries loaded successfully")
            }
        }
        
        stage("Database Operations") {
            steps {
                echo("Performing database operations...")
                
                // Step from database-utils.jar
                connectDatabase(env["DATABASE_URL"])
                executeQuery("CREATE TABLE IF NOT EXISTS builds (id SERIAL, name VARCHAR(255))")
                executeQuery("INSERT INTO builds (name) VALUES ('test-build')")
                
                val buildCount = executeQuery("SELECT COUNT(*) FROM builds", returnResult = true)
                echo("Total builds: " + buildCount)
                
                disconnectDatabase()
                
                echo("Database operations completed")
            }
        }
        
        stage("Build Operations") {
            steps {
                echo("Performing build operations...")
                
                // Steps from build-tools git repo
                initializeBuild(env["BUILD_PROFILE"])
                
                parallel(
                    "Compile" to {
                        compileCode("src/main/kotlin")
                        echo("Compilation completed")
                    },
                    "Resources" to {
                        processResources("src/main/resources")
                        echo("Resources processed")
                    }
                )
                
                packageArtifact("jar", "target/app.jar")
                
                echo("Build operations completed")
            }
        }
        
        stage("Publishing") {
            steps {
                echo("Publishing artifacts...")
                
                // Step from Maven artifact
                publishArtifact("nexus", "com.company:test-app:1.0.0") {
                    file = "target/app.jar"
                    metadata = [
                        "buildNumber": env["BUILD_NUMBER"] ?: "unknown",
                        "branch": env["BRANCH_NAME"] ?: "main"
                    ]
                }
                
                // Notification step from extension
                sendNotification("Build completed successfully") {
                    channels = ["#build", "#team"]
                    includeArtifacts = true
                }
                
                echo("Publishing completed")
            }
        }
    }
    
    post {
        always {
            echo("Cleaning up library resources...")
            cleanupLibraries()
        }
        
        success {
            echo("Pipeline completed successfully with all libraries")
        }
        
        failure {
            echo("Pipeline failed - check library compatibility")
        }
    }
}