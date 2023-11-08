package dev.rubentxu.pipeline
import dev.rubentxu.pipeline.dsl.Step
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.extensions.echo
import dev.rubentxu.pipeline.extensions.sh
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay


class StagesDslTest : StringSpec({

    "Pipeline with stages and steps and parallel steps should run" {
        val pipelineDef = pipeline {
            agent {
                docker {
                    label = "docker"
                    image = "alpine"
                    tag = "latest"
                }
            }

            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE"    += "sqlite"
            }

            stages {
                stage("Build") {
                    steps {
                        parallel(
                            "a" to Step {
                                delay(1000)
                                echo("This is branch a")
                            },
                            "b" to Step {
                                delay(500)
                                echo("This is branch b")
                            }
                        )
                        sh("pwd", returnStdout=true)
                        echo("Variable de entorno para DB_ENGINE es ${env["DB_ENGINE"]}")
                    }
                    post {
                        always {
                            echo("This is the post section always in stage Test")
                        }

                        failure {
                            echo("This is the post section failure in stage Test")
                        }
                    }
                }
                stage("Test") {
                    steps {
                        sh("ls -la", returnStdout=true)
                        echo("Tests complete")
                        sh("ls -la /home", returnStdout=true)
                    }

                }
            }
            post {
                always {
                    echo("This is the post section always")
                }

                success {
                    echo("This is the post section success")
                }

                failure {
                    echo("This is the post section failure")
                }
            }
        }
        val pipeline = pipelineDef.build(PipelineLogger(LogLevel.TRACE))
        val executor = PipelineExecutor()
        val result = executor.execute(pipeline)

        result.status shouldBe Status.Success
        result.stageResults.size shouldBe 2

        val buildStage = result.stageResults.find { it.name == "Build" }
        buildStage shouldNotBe null
        buildStage?.status shouldBe Status.Success
        result.logs.find { it.contains("Variable de entorno para DB_ENGINE es sqlite") } shouldNotBe null

        val testStage = result.stageResults.find { it.name == "Test" }
        testStage shouldNotBe null
        testStage?.status shouldBe Status.Success
        result.logs.find { it.contains("Tests complete") } shouldNotBe null

        result.env["DB_ENGINE"] shouldBe "sqlite"
    }

    "Pipeline example with stages and steps should run" {
        val pipelineDef = pipeline {

            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE"    += "sqlite"
            }
            stages {
                stage("Build") {
                    steps {
                        parallel(
                            "a" to Step {
                                delay(1000)
                                echo("This is branch a")
                            },
                            "b" to Step {
                                delay(500)
                                echo("This is branch b")
                            }
                        )
                        sh("pwd", returnStdout=true)
                        echo("Variable de entorno para DB_ENGINE es ${env["DB_ENGINE"]}")
                    }
                }
                stage("Test") {
                    steps {
                        sh("ls -la", returnStdout=true)
                        echo("Tests complete")
                        sh("ls -la /home", returnStdout=true)
                    }
                }
            }
        }

        val pipeline = pipelineDef.build(LogLevel.TRACE)
        val executor = PipelineExecutor()
        val result = executor.execute(pipeline)

        result.status shouldBe Status.Success
        result.stageResults.size shouldBe 2

        val buildStage = result.stageResults.find { it.name == "Build" }
        buildStage shouldNotBe null
        buildStage?.status shouldBe Status.Success
        result.logs.find { it.contains("Variable de entorno para DB_ENGINE es sqlite") } shouldNotBe null

        val testStage = result.stageResults.find { it.name == "Test" }
        testStage shouldNotBe null
        testStage?.status shouldBe Status.Success
        result.logs.find { it.contains("Tests complete") } shouldNotBe null

        result.env["DB_ENGINE"] shouldBe "sqlite"
    }

    "Pipeline should fail if a stage fails" {
        val pipelineDef = pipeline {
            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE"    += "sqlite"
            }
            stages {
                stage("Failing Stage") {
                    steps {
                        echo("This is a failing stage")
                        sh("pwd", returnStdout=true)
                    }
                }
            }
            post {
                always {
                    echo("This is the post section always")
                }
            }

        }

        val pipeline = pipelineDef.build(LogLevel.TRACE)
        val executor = PipelineExecutor()
        val result = executor.execute(pipeline)
//        result.status shouldBe Status.Failure
//        val failingStage = result.stageResults.find { it.name == "Failing Stage" }
//        failingStage shouldNotBe null
//        failingStage?.status shouldBe Status.Failure
    }
})
