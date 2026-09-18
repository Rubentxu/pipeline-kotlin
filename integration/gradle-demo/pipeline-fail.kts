// WU-LPR-062 failure path: broken test must fail the pipeline with typed outcome.
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon test -Pdemo.broken=true")
        }
    }
}
