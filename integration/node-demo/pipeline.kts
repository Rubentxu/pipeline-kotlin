// WU-LPR-064: real Node.js project exercised through pipelinek (installed distribution).
pipeline {
    stages {
        stage("build") {
            sh("node --test")
            echo("NODE-DEMO-OK")
        }
    }
}
