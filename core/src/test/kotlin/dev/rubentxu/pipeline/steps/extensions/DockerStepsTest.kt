package dev.rubentxu.pipeline.steps.extensions

import dev.rubentxu.pipeline.steps.testing.runStepTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

// Dummy functions for type-safe mocking
suspend fun sh(command: String): String = ""
suspend fun echo(message: String) {}
suspend fun dockerBuild(imageName: String, dockerfile: String = "Dockerfile", context: String = "."): String = ""
suspend fun dockerRun(imageName: String, args: String = ""): String = ""
suspend fun dockerStop(containerId: String) {}
suspend fun dockerPush(imageName: String) {}
suspend fun dockerPull(imageName: String): String = ""

/**
 * Tests for Docker-related @Step functions using the modernized testing framework.
 */
class DockerStepsTest : FunSpec({

    test("dockerBuild should build image with default settings") {
        runStepTest {
            // Setup test Dockerfile
            workingDir.resolve("Dockerfile").toFile().writeText("""
                FROM alpine:latest
                CMD ["echo", "Hello World"]
            """.trimIndent())

            // Mock the underlying sh command
            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker build") -> {
                        command shouldContain "-t myapp:latest"
                        command shouldContain "."
                        "Successfully built abc123"
                    }
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Build") {
                        steps {
                            dockerBuild("myapp:latest")
                        }
                    }
                }
            }

            verifyStepCalled(::sh)
        }
    }

    test("dockerRun should run container with default settings") {
        runStepTest {
            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker run") -> {
                        command shouldContain "-d"
                        command shouldContain "--rm"
                        command shouldContain "myapp:latest"
                        "container123"
                    }
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Run") {
                        steps {
                            dockerRun("myapp:latest")
                        }
                    }
                }
            }

            verifyStepCalled(::sh)
        }
    }

    test("dockerStop should stop and remove container") {
        runStepTest {
            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker stop") -> {
                        command shouldContain "myapp-container"
                        ""
                    }
                    command.contains("docker rm") -> {
                        command shouldContain "myapp-container"
                        ""
                    }
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Stop") {
                        steps {
                            dockerStop("myapp-container")
                        }
                    }
                }
            }

            verifyStepCalled(::sh, times = 2)
        }
    }

    test("dockerPush should push image to registry") {
        runStepTest {
            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker push") -> {
                        command shouldContain "myregistry.com/myapp:v1.0"
                        "The push refers to repository [myregistry.com/myapp]"
                    }
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Push") {
                        steps {
                            dockerPush("myregistry.com/myapp:v1.0")
                        }
                    }
                }
            }

            verifyStepCalled(::sh)
        }
    }

    test("dockerPull should pull image from registry") {
        runStepTest {
            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker pull") -> {
                        command shouldContain "myregistry.com/myapp:latest"
                        "latest: Pulling from myapp"
                    }
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Pull") {
                        steps {
                            dockerPull("myregistry.com/myapp:latest")
                        }
                    }
                }
            }

            verifyStepCalled(::sh)
        }
    }

    test("Docker operations workflow test") {
        runStepTest {
            workingDir.resolve("Dockerfile").toFile().writeText("""
                FROM alpine:latest
                RUN apk add --no-cache curl
                CMD ["echo", "Hello Docker"]
            """.trimIndent())

            mockStep(::sh) { params ->
                val command = params["command"] as String
                when {
                    command.contains("docker build") -> "Successfully built workflow123"
                    command.contains("docker run") -> "workflowcontainer456"
                    command.contains("docker stop") -> ""
                    command.contains("docker rm") -> ""
                    command.contains("docker push") -> "The push refers to repository"
                    else -> ""
                }
            }

            testPipeline {
                stages {
                    stage("Workflow") {
                        steps {
                            dockerBuild("workflow-image")
                            dockerRun("workflow-image")
                            dockerStop("workflowcontainer456")
                            dockerPush("myregistry.com/workflow-image")
                        }
                    }
                }
            }

            verifyStepCalled(::sh, times = 5) // build, run, stop, rm, push
        }
    }
})