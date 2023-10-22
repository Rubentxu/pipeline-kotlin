package dev.rubentxu.pipeline.dsl
import dev.rubentxu.pipeline.steps.*
import io.kotest.core.spec.style.StringSpec



class StagesDslTest : StringSpec({

    "Pipeline with stages" {
        pipeline {
            agent(any)
            environment {
                "DISABLE_AUTH" += "true"
                "DB_ENGINE"    += "sqlite"
            }
            stages {
                stage("Build") {
                    steps {
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
    }
})
