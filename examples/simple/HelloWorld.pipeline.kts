#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.pipeline

println("HOLA MUNDO..................................................")

pipeline {
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