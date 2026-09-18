// WU-LPR-063 failure path: -Ddemo.brokenProp=true must fail the pipeline with typed outcome.
pipeline {
    stages {
        stage("build") {
            sh("mvn -q -B test -Ddemo.brokenProp=true")
        }
    }
}
