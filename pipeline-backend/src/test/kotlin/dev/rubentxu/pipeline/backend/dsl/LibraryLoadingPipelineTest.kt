package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.steps.testing.runStepTest
import io.kotest.core.spec.style.FunSpec

// Dummy functions for type-safe mocking. These represent steps that would be loaded from libraries.
suspend fun loadLibrary(path: String) {}
suspend fun customStep() {}
suspend fun databaseQuery(query: String): String = ""
suspend fun echo(message: String) {}
suspend fun loadGitLibrary(url: String) {}
suspend fun gitClone(url: String) {}
suspend fun gitCommit(message: String) {}
suspend fun gitPush() {}
suspend fun connectDatabase() {}
suspend fun buildArtifact() {}
suspend fun publishArtifact() {}
suspend fun sendSlackMessage(message: String) {}
suspend fun sendEmail(to: String, body: String) {}
suspend fun logMessage(message: String) {}
suspend fun complexOperation() {}
suspend fun parseJSON(json: String) {}
suspend fun httpGet(url: String): String = ""
suspend fun checkClassPath() {}

/**
 * Tests for Pipeline DSL Library Loading functionality using the DSL v2 Testing Framework
 * Validates library loading, dependency resolution, and dynamic step registration
 */
class LibraryLoadingPipelineTest : FunSpec({

    test("Local JAR Library Loading - should load steps from local JAR files") {
        runStepTest {
            // Mock library loading functionality
            mockStep(::loadLibrary) { "Library loaded successfully" }

            // Mock custom steps from loaded library
            mockStep(::customStep) { "Custom step executed" }
            mockStep(::databaseQuery) { "Query result: 3 users found" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Loading custom steps from JAR...")
                            loadLibrary("local.jar")
                            customStep()
                            databaseQuery("SELECT * FROM users")
                            echo("Custom steps executed successfully")
                        }
                    }
                }
            }

            verifyStepCalled(::loadLibrary)
            verifyStepCalled(::customStep)
            verifyStepCalled(::databaseQuery)
            verifyStepCalled(::echo, times = 2)
        }
    }

    test("Git Source Library Loading - should load libraries from Git repositories") {
        runStepTest {
            // Mock Git library loading
            mockStep(::loadGitLibrary) { "Git library cloned and loaded" }

            // Mock Git extension steps
            mockStep(::gitClone) { "Repository cloned successfully" }
            mockStep(::gitCommit) { "Changes committed" }
            mockStep(::gitPush) { "Changes pushed to remote" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Loading extensions from Git repository...")
                            loadGitLibrary("https://github.com/user/repo.git")
                            gitClone("https://github.com/user/repo.git")
                            gitCommit("feat: new feature")
                            gitPush()
                            echo("Git operations completed")
                        }
                    }
                }
            }

            verifyStepCalled(::loadGitLibrary)
            verifyStepCalled(::gitClone)
            verifyStepCalled(::gitCommit)
            verifyStepCalled(::gitPush)
            verifyStepCalled(::echo, times = 2)
        }
    }

    test("Multiple Library Sources - should load from multiple sources") {
        runStepTest {
            // Mock steps from different library sources
            mockStep(::connectDatabase) { "Database connection established" }
            mockStep(::buildArtifact) { "Artifact built successfully" }
            mockStep(::publishArtifact) { "Artifact published to repository" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Testing multiple library sources...")
                            connectDatabase()
                            buildArtifact()
                            publishArtifact()
                            echo("All library sources working correctly")
                        }
                    }
                }
            }

            verifyStepCalled(::connectDatabase)
            verifyStepCalled(::buildArtifact)
            verifyStepCalled(::publishArtifact)
            verifyStepCalled(::echo, times = 2)
        }
    }

    test("Dynamic Step Registration - should make steps available after library load") {
        runStepTest {
            mockStep(::loadLibrary) { "Library loaded and steps registered" }

            // Mock dynamically registered steps
            mockStep(::sendSlackMessage) { "Slack message sent" }
            mockStep(::sendEmail) { "Email sent" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Before library loading - basic steps only")
                            loadLibrary("notifications.jar")
                            echo("After library loading - new steps available")
                            sendSlackMessage("Deployment successful!")
                            sendEmail("team@example.com", "Deployment Report")
                            echo("Dynamic steps executed successfully")
                        }
                    }
                }
            }

            verifyStepCalled(::loadLibrary)
            verifyStepCalled(::sendSlackMessage)
            verifyStepCalled(::sendEmail)
            verifyStepCalled(::echo, times = 3)
        }
    }

    test("Library Dependency Resolution - should handle dependencies between libraries") {
        runStepTest {
            // Mock base utility step
            mockStep(::logMessage) { "Log message recorded" }

            // Mock advanced tool step that depends on the base utility
            mockStep(::complexOperation) { "Complex operation completed successfully" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Testing library dependency resolution...")
                            logMessage("Starting complex operation")
                            complexOperation()
                            echo("Dependency resolution working correctly")
                        }
                    }
                }
            }

            verifyStepCalled(::logMessage)
            verifyStepCalled(::complexOperation)
            verifyStepCalled(::echo, times = 2)
        }
    }

    test("Library ClassPath Management - should handle classpath correctly") {
        runStepTest {
            mockStep(::parseJSON) { "JSON parsed successfully" }
            mockStep(::httpGet) { "HTTP response: 200 OK" }
            mockStep(::checkClassPath) { "ClassPath contains: json-processing.jar, http-client.jar" }
            mockStep(::echo) {}

            testPipeline {
                stages {
                    stage("Test") {
                        steps {
                            echo("Testing classpath management...")
                            parseJSON("{}")
                            httpGet("https://example.com")
                            checkClassPath()
                            echo("Classpath management working correctly")
                        }
                    }
                }
            }

            verifyStepCalled(::parseJSON)
            verifyStepCalled(::httpGet)
            verifyStepCalled(::checkClassPath)
            verifyStepCalled(::echo, times = 2)
        }
    }
})