// WU-LPR-062: real Gradle JVM project built through pipelinek (installed distribution).
// Exercises: actual subprocesses (gradle), artifact output (jar), failure path (broken test).
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/gradle-demo.jar")
            echo("GRADLE-DEMO-OK")
        }
    }
}
