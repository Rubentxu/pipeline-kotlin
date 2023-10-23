package dev.rubentxu.pipeline.dsl
import dev.rubentxu.pipeline.steps.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay


class StagesDslTest : StringSpec({

    "Pipeline with stages and steps should run" {
        val result = pipeline {
            agent(any)
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
                                println("This is branch a")
                            },
                            "b" to Step {
                                delay(500)
                                println("This is branch b")
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
        val result = pipeline {
            // ...
            stages {
                stage("Failing Stage") {
                    steps {
                        sh("command-that-does-not-exist", returnStdout=true)
                    }
                }
            }
        }

        result.status shouldBe Status.Failure
        val failingStage = result.stageResults.find { it.name == "Failing Stage" }
        failingStage shouldNotBe null
        failingStage?.status shouldBe Status.Failure
    }
})
