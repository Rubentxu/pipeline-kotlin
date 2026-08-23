pipeline {
    stages {
        stage("build") {
            echo("compiling")
            sh("make")
        }
        stage("test") {
            echo("testing")
            sh("make test")
        }
    }
}
