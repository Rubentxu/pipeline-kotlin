#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.extensions.*
import kotlinx.coroutines.delay

println("HOLA MUNDO..................................................")

pipeline {
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