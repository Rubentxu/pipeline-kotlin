#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

println("HOLA MUNDO..................................................")
println("Is agent -> ${System.getenv("IS_AGENT")} .........")

pipeline {
//    agent {
//        docker {
//            label = "docker"
//            image = "openjdk"
//            tag = "17"
//        }
//    }
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
                var stdOut = sh("pwd", returnStdout = true)
                echo(stdOut)
                var text = readFile("build.gradle.kts")
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