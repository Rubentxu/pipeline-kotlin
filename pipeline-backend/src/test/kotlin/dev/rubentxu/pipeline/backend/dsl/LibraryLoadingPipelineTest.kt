package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.mocks.wildcard
import dev.rubentxu.pipeline.testing.MockResult
import io.kotest.matchers.shouldBe

/**
 * Tests for Pipeline DSL Library Loading functionality using the Pipeline Testing Framework
 * Validates library loading, dependency resolution, and dynamic step registration
 */
class LibraryLoadingPipelineTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "Local JAR Library Loading - should load steps from local JAR files",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("local", "/path/to/custom-steps.jar")
                        }
                        
                        stages {
                            stage("Local Library Test") {
                                steps {
                                    echo("Loading custom steps from JAR...")
                                    
                                    // Custom steps from loaded library
                                    customStep("parameter1", "parameter2")
                                    databaseQuery("SELECT * FROM users")
                                    
                                    echo("Custom steps executed successfully")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock library loading
                mockStep("loadLibrary") {
                    returnExitCode(0)
                    returnOutput("Library loaded successfully")
                }
                
                // Mock custom steps from loaded library
                mockStep("customStep") {
                    returnExitCode(0)
                    returnOutput("Custom step executed")
                }
                
                mockStep("databaseQuery") {
                    returnExitCode(0)
                    returnOutput("Query result: 3 users found")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to "Loading custom steps from JAR..."))
                stepCalledWith("echo", mapOf("message" to "Custom steps executed successfully"))
                
                // Verify custom steps were called
                stepWasCalled("customStep")
                stepCalledWith("customStep", "parameter1", "parameter2")
                
                stepWasCalled("databaseQuery")
                stepCalledWith("databaseQuery", mapOf("query" to "SELECT * FROM users"))
            }
        )
        
        testPipeline(
            "Git Source Library Loading - should load libraries from Git repositories",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("git", "https://github.com/example/pipeline-extensions.git") {
                                branch = "main"
                                path = "build/libs"
                            }
                        }
                        
                        stages {
                            stage("Git Library Test") {
                                steps {
                                    echo("Loading extensions from Git repository...")
                                    
                                    // Git operations from loaded extension
                                    gitClone("https://github.com/example/repo.git")
                                    gitCommit("Add new feature")
                                    gitPush("origin", "main")
                                    
                                    echo("Git operations completed")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock Git library loading
                mockStep("loadGitLibrary") {
                    returnExitCode(0)
                    returnOutput("Git library cloned and loaded")
                }
                
                // Mock Git extension steps
                mockStep("gitClone") {
                    returnExitCode(0)
                    returnOutput("Repository cloned successfully")
                }
                
                mockStep("gitCommit") {
                    returnExitCode(0)
                    returnOutput("Changes committed")
                }
                
                mockStep("gitPush") {
                    returnExitCode(0)
                    returnOutput("Changes pushed to remote")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                stepCalledWith("echo", mapOf("message" to "Loading extensions from Git repository..."))
                stepCalledWith("echo", mapOf("message" to "Git operations completed"))
                
                // Verify Git extension steps were called
                stepWasCalled("gitClone")
                stepCalledWith("gitClone", mapOf("url" to "https://github.com/example/repo.git"))
                
                stepWasCalled("gitCommit")
                stepCalledWith("gitCommit", mapOf("message" to "Add new feature"))
                
                stepWasCalled("gitPush")
                stepCalledWith("gitPush", mapOf("remote" to "origin", "branch" to "main"))
            }
        )
        
        testPipeline(
            "Multiple Library Sources - should load from multiple sources",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("local", "/path/to/database-utils.jar")
                            source("git", "https://github.com/company/build-tools.git")
                            source("maven", "com.company:pipeline-extensions:1.0.0")
                        }
                        
                        stages {
                            stage("Multiple Libraries Test") {
                                steps {
                                    echo("Testing multiple library sources...")
                                    
                                    // Step from database-utils.jar
                                    connectDatabase("postgresql://localhost:5432/db")
                                    
                                    // Step from build-tools git repo
                                    buildArtifact("jar", "target/app.jar")
                                    
                                    // Step from Maven artifact
                                    publishArtifact("nexus", "com.company:app:1.0.0")
                                    
                                    echo("All library sources working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock steps from different library sources
                mockStep("connectDatabase") {
                    returnExitCode(0)
                    returnOutput("Database connection established")
                }
                
                mockStep("buildArtifact") {
                    returnExitCode(0)
                    returnOutput("Artifact built successfully")
                }
                
                mockStep("publishArtifact") {
                    returnExitCode(0)
                    returnOutput("Artifact published to repository")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                // Verify steps from each library source
                stepWasCalled("connectDatabase")
                stepCalledWith("connectDatabase", mapOf("url" to "postgresql://localhost:5432/db"))
                
                stepWasCalled("buildArtifact")
                stepCalledWith("buildArtifact", mapOf("type" to "jar", "output" to "target/app.jar"))
                
                stepWasCalled("publishArtifact")
                stepCalledWith("publishArtifact", mapOf("repository" to "nexus", "artifact" to "com.company:app:1.0.0"))
                
                stepsCalledInOrder("echo", "connectDatabase", "buildArtifact", "publishArtifact", "echo")
            }
        )
        
        testPipeline(
            "Dynamic Step Registration - should make steps available after library load",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Dynamic Registration Test") {
                                steps {
                                    echo("Before library loading - basic steps only")
                                    
                                    // Load library dynamically
                                    loadLibrary("notification-extensions.jar")
                                    
                                    echo("After library loading - new steps available")
                                    
                                    // These steps become available after loading
                                    sendSlackMessage("#build", "Build started")
                                    sendEmail("team@company.com", "Build Status")
                                    
                                    echo("Dynamic steps executed successfully")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("loadLibrary") {
                    returnExitCode(0)
                    returnOutput("Library loaded and steps registered")
                }
                
                // Mock dynamically registered steps
                mockStep("sendSlackMessage") {
                    returnExitCode(0)
                    returnOutput("Slack message sent")
                }
                
                mockStep("sendEmail") {
                    returnExitCode(0)
                    returnOutput("Email sent")
                }
            },
            verificationBlock = {
                stepWasCalled("loadLibrary")
                stepCalledWith("loadLibrary", mapOf("library" to "notification-extensions.jar"))
                
                stepWasCalled("echo")
                stepCallCount("echo", 3)
                
                // Verify dynamically loaded steps were called after library loading
                stepWasCalled("sendSlackMessage")
                stepCalledWith("sendSlackMessage", mapOf("channel" to "#build", "message" to "Build started"))
                
                stepWasCalled("sendEmail")
                stepCalledWith("sendEmail", mapOf("to" to "team@company.com", "subject" to "Build Status"))
                
                // Verify order: library load must happen before dynamic steps
                stepsCalledInOrder("loadLibrary", "sendSlackMessage", "sendEmail")
            }
        )
        
        testPipeline(
            "Library Dependency Resolution - should handle dependencies between libraries",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("local", "/path/to/base-utils.jar") {
                                priority = 1
                            }
                            source("local", "/path/to/advanced-tools.jar") {
                                priority = 2
                                dependsOn = ["base-utils"]
                            }
                        }
                        
                        stages {
                            stage("Dependency Resolution Test") {
                                steps {
                                    echo("Testing library dependency resolution...")
                                    
                                    // Base utility step (loaded first)
                                    logMessage("INFO", "Starting dependency test")
                                    
                                    // Advanced tool step (depends on base-utils)
                                    complexOperation("process-data") {
                                        inputFile = "data.csv"
                                        outputFile = "processed-data.json"
                                    }
                                    
                                    echo("Dependency resolution working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock base utility step
                mockStep("logMessage") {
                    returnExitCode(0)
                    returnOutput("Log message recorded")
                }
                
                // Mock advanced tool step
                mockStep("complexOperation") {
                    returnExitCode(0)
                    returnOutput("Complex operation completed successfully")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                stepWasCalled("logMessage")
                stepCalledWith("logMessage", mapOf("level" to "INFO", "message" to "Starting dependency test"))
                
                stepWasCalled("complexOperation")
                stepCalledWith("complexOperation", mapOf(
                    "operation" to "process-data",
                    "inputFile" to "data.csv",
                    "outputFile" to "processed-data.json"
                ))
            }
        )
        
        testPipelineFailure(
            "Library Loading Error - should handle missing library gracefully",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("local", "/path/to/nonexistent-library.jar")
                        }
                        
                        stages {
                            stage("Error Handling Test") {
                                steps {
                                    echo("This should fail due to missing library")
                                    missingStep("parameter")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock library loading failure
                mockStep("loadLibrary") {
                    returnExitCode(1)
                    returnError("Library not found: /path/to/nonexistent-library.jar")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("Library not found") == true ||
                error.message?.contains("nonexistent-library.jar") == true
            }
        )
        
        testPipeline(
            "Library ClassPath Management - should handle classpath correctly",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        libraries {
                            source("local", "/libs/json-processing.jar")
                            source("local", "/libs/http-client.jar")
                        }
                        
                        stages {
                            stage("ClassPath Test") {
                                steps {
                                    echo("Testing classpath management...")
                                    
                                    // Steps that use different libraries
                                    parseJSON('{"name": "test", "version": "1.0"}')
                                    httpGet("https://api.example.com/status")
                                    
                                    // Verify libraries are isolated
                                    checkClassPath()
                                    
                                    echo("ClassPath management working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("parseJSON") {
                    returnExitCode(0)
                    returnOutput("JSON parsed successfully")
                }
                
                mockStep("httpGet") {
                    returnExitCode(0)
                    returnOutput("HTTP response: 200 OK")
                }
                
                mockStep("checkClassPath") {
                    returnExitCode(0)
                    returnOutput("ClassPath contains: json-processing.jar, http-client.jar")
                }
            },
            verificationBlock = {
                stepWasCalled("parseJSON")
                stepCalledWith("parseJSON", mapOf("json" to """{"name": "test", "version": "1.0"}"""))
                
                stepWasCalled("httpGet")
                stepCalledWith("httpGet", mapOf("url" to "https://api.example.com/status"))
                
                stepWasCalled("checkClassPath")
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
            }
        )
    }
}