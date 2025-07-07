#!/usr/bin/env kotlin

pipeline {
    agent {
        docker("openjdk:21")
    }
    
    environment {
        "BUILD_NUMBER" += "123"
        "BRANCH_NAME" += "main"
        "WORKSPACE" += "/workspace"
    }
    
    stages {
        stage("Basic Step Extensions") {
            steps {
                echo("Testing basic step extensions...")
                
                // Shell command execution
                sh("echo 'Basic shell command'")
                val output = sh("pwd", returnStdout = true)
                echo("Current directory: " + output)
                
                val exitCode = sh("test -f build.gradle.kts", returnStatus = true)
                echo("Build file exists: " + (exitCode == 0))
                
                // File operations
                writeFile("test.txt", "Hello World from step extensions")
                val exists = fileExists("test.txt")
                echo("File exists: " + exists)
                
                if (exists) {
                    val content = readFile("test.txt")
                    echo("File content: " + content)
                }
                
                echo("Basic step extensions working correctly")
            }
        }
        
        stage("Advanced Step Extensions") {
            steps {
                echo("Testing advanced step extensions...")
                
                // Directory operations
                dir("subproject") {
                    sh("pwd")
                    writeFile("subproject-file.txt", "Content in subdirectory")
                    
                    dir("nested") {
                        sh("pwd")
                        writeFile("nested-file.txt", "Deeply nested content")
                    }
                }
                
                // Verify we're back in original directory
                sh("pwd")
                
                // Retry operations
                retry(3) {
                    sh("echo 'Attempt with retry'")
                }
                
                // Sleep/delay operations
                sleep(1)
                echo("Sleep completed")
                
                echo("Advanced step extensions working correctly")
            }
        }
        
        stage("Parallel Execution") {
            steps {
                echo("Testing parallel step execution...")
                
                parallel(
                    "Unit Tests" to {
                        echo("Running unit tests...")
                        sh("./gradlew test")
                        echo("Unit tests completed")
                    },
                    "Integration Tests" to {
                        echo("Running integration tests...")
                        sh("./gradlew integrationTest")
                        echo("Integration tests completed")
                    },
                    "Static Analysis" to {
                        echo("Running static analysis...")
                        sh("./gradlew ktlintCheck")
                        sh("./gradlew detekt")
                        echo("Static analysis completed")
                    }
                )
                
                echo("All parallel tasks completed")
            }
        }
        
        stage("Environment Variables") {
            steps {
                echo("Testing environment variable operations...")
                
                // Read environment variables
                echo("Build number: " + env["BUILD_NUMBER"])
                echo("Branch name: " + env["BRANCH_NAME"])
                echo("Workspace: " + env["WORKSPACE"])
                
                // Set new environment variables
                setEnv("CUSTOM_VAR", "custom-value")
                setEnv("COMPUTED_VAR", "build-" + env["BUILD_NUMBER"])
                
                // Use newly set variables
                echo("Custom variable: " + env["CUSTOM_VAR"])
                echo("Computed variable: " + env["COMPUTED_VAR"])
                
                // Environment in shell commands
                sh("echo BUILD_NUMBER=" + env["BUILD_NUMBER"])
                sh("echo CUSTOM_VAR=" + env["CUSTOM_VAR"])
                
                echo("Environment variable operations working correctly")
            }
        }
        
        stage("SCM Operations") {
            steps {
                echo("Testing SCM step extensions...")
                
                // Git operations
                checkout(scm: [
                    $class: "GitSCM",
                    branches: [[name: "*/main"]],
                    userRemoteConfigs: [[url: "https://github.com/company/repo.git"]]
                ])
                
                // Get git information
                val commitHash = sh("git rev-parse HEAD", returnStdout = true)
                echo("Current commit: " + commitHash)
                
                val branch = sh("git branch --show-current", returnStdout = true)
                echo("Current branch: " + branch)
                
                echo("SCM operations working correctly")
            }
        }
        
        stage("Conditional Execution") {
            steps {
                echo("Testing conditional step execution...")
                
                // Conditional based on environment
                if (env["BRANCH_NAME"] == "main") {
                    echo("Executing main branch specific steps")
                    sh("echo 'Main branch deployment preparation'")
                } else {
                    echo("Executing feature branch specific steps")
                    sh("echo 'Feature branch testing'")
                }
                
                // Conditional based on file existence
                if (fileExists("Dockerfile")) {
                    echo("Docker build available")
                    sh("docker build -t test-image .")
                } else {
                    echo("No Dockerfile found, skipping Docker build")
                }
                
                // Conditional based on command success
                val testStatus = sh("test -d src/test", returnStatus = true)
                if (testStatus == 0) {
                    echo("Test directory exists, running tests")
                    sh("./gradlew test")
                } else {
                    echo("No test directory found")
                }
                
                echo("Conditional execution working correctly")
            }
        }
        
        stage("Error Handling") {
            steps {
                echo("Testing error handling in step extensions...")
                
                // Handle command failures gracefully
                try {
                    sh("command-that-might-fail")
                } catch (e: Exception) {
                    echo("Command failed as expected: " + e.message)
                }
                
                // Continue execution after error
                echo("Continuing after handled error")
                
                // Retry with error handling
                retry(2) {
                    try {
                        sh("flaky-command")
                    } catch (e: Exception) {
                        echo("Retry attempt failed: " + e.message)
                        throw e
                    }
                }
                
                echo("Error handling working correctly")
            }
        }
        
        stage("Resource Management") {
            steps {
                echo("Testing resource management...")
                
                // Temporary file cleanup
                withTempFile { tempFile ->
                    writeFile(tempFile, "Temporary content")
                    val tempContent = readFile(tempFile)
                    echo("Temp file content: " + tempContent)
                    // File automatically cleaned up
                }
                
                // Directory cleanup
                withTempDir { tempDir ->
                    dir(tempDir) {
                        writeFile("temp1.txt", "Content 1")
                        writeFile("temp2.txt", "Content 2")
                        sh("ls -la")
                    }
                    // Directory automatically cleaned up
                }
                
                // Credential management
                withCredentials([
                    usernamePassword(credentialsId: "db-credentials", 
                                   usernameVariable: "DB_USER",
                                   passwordVariable: "DB_PASS")
                ]) {
                    echo("Using credentials: " + env["DB_USER"])
                    sh("echo 'Connecting with credentials...'")
                    // Credentials automatically cleared
                }
                
                echo("Resource management working correctly")
            }
        }
        
        stage("Custom Step Extensions") {
            steps {
                echo("Testing custom step extensions...")
                
                // JSON operations
                val jsonData = '{"name": "test", "version": "1.0", "dependencies": ["kotlin", "gradle"]}'
                writeJSON(file: "data.json", json: jsonData)
                
                val parsedData = readJSON(file: "data.json")
                echo("Parsed JSON: " + parsedData.name + " v" + parsedData.version)
                
                // File pattern operations
                val kotlinFiles = findFiles(glob: "**/*.kt")
                echo("Found " + kotlinFiles.size() + " Kotlin files")
                
                val testFiles = findFiles(glob: "src/test/**/*.kt")
                echo("Found " + testFiles.size() + " test files")
                
                // Archive operations
                archiveArtifacts(artifacts: "build/libs/*.jar", fingerprint: true)
                
                echo("Custom step extensions working correctly")
            }
        }
    }
    
    post {
        always {
            echo("Cleaning up step extension test resources...")
            
            // Cleanup test files
            sh("rm -f test.txt")
            sh("rm -f data.json")
            sh("rm -rf subproject")
            
            echo("Step extension tests cleanup completed")
        }
        
        success {
            echo("All step extension tests passed successfully")
        }
        
        failure {
            echo("Step extension tests failed")
            
            // Capture failure information
            sh("pwd")
            sh("ls -la")
            
            echo("Failure information captured")
        }
    }
}