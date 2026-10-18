pipeline {
    stages {
        stage("build") {
            echo("building...")
        }
        stage("test") {
            echo("testing...")
        }
        stage("deploy") {
            echo("deploying...")
        }
    }
}
