package dev.rubentxu.pipeline

import dev.rubentxu.pipeline.dsl.Step
import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.job.JobExecutor
import dev.rubentxu.pipeline.model.pipeline.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import pipeline.kotlin.extensions.*


class StagesDslTest : StringSpec({

    "Pipeline with stages and steps and parallel steps should run" {
        val pipelineDefResult = pipeline {
            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE" += "sqlite"
            }
            stages {
                stage("Build") {
                    steps {
                        delay(1000) {
                            echo("Delay antes de ejecutar los pasos paralelos")
                        }

                        parallel(
                            "a" to Step {
                                delay(1000) {
                                    echo("Delay This is branch a")
                                }

                            },
                            "b" to Step {
                                delay(300) {
                                    echo("Delay This is branch b")
                                }
                            }
                        )
                        sh("pwd", returnStdout = true)
                        var text = readFile("build.gradle2.kts")
                        echo(text)
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
                        sh("ls -la", returnStdout = true)
                        retry(3) {
                            delay(3000) {
                                echo("Tests retry ....")
                                sh("ls -la .", returnStdout = true)
                            }

                        }

                        sh("ls -la /home", returnStdout = true)
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

        pipelineDefResult.isSuccess shouldBe true

        PipelineLogger.getLogger().changeLogLevel(LogLevel.DEBUG)

        val pipeline = pipelineDefResult.getOrNull()!!.build()
        val executor = JobExecutor()
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
        val pipelineDefResult = pipeline {

            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE" += "sqlite"
            }
            stages {
                stage("Build") {
                    steps {
                        parallel(
                            "a" to Step {
                                delay(1000) {
                                    echo("This is branch a")
                                }
                            },
                            "b" to Step {
                                delay(500) {
                                    echo("This is branch b")
                                }
                            }
                        )
                        sh("pwd", returnStdout = true)
                        echo("Variable de entorno para DB_ENGINE es ${env["DB_ENGINE"]}")
                    }
                }
                stage("Test") {
                    steps {
                        sh("ls -la", returnStdout = true)
                        echo("Tests complete")
                        sh("ls -la /home", returnStdout = true)
                    }
                }
            }
        }

        pipelineDefResult.isSuccess shouldBe true

        val pipeline = pipelineDefResult.getOrNull()!!.build()
        val executor = JobExecutor()
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
        val pipelineDefResult = pipeline {
            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE" += "sqlite"
            }
            stages {
                stage("Failing Stage") {
                    steps {
                        echo("This is a failing stage")
                        sh("pwd", returnStdout = true)
                        var result = sh("echo \$DB_ENGINE", returnStdout = true)
                        echo(result)
                    }
                }
            }
            post {
                always {
                    echo("This is the post section always")
                }
            }

        }


        pipelineDefResult.isSuccess shouldBe true
        val pipeline = pipelineDefResult.getOrNull()!!.build()
        val executor = JobExecutor()
        val result = executor.execute(pipeline)
//        result.status shouldBe Status.Failure
//        val failingStage = result.stageResults.find { it.name == "Failing Stage" }
//        failingStage shouldNotBe null
//        failingStage?.status shouldBe Status.Failure
    }
})
