pipeline {
    stages {
        stage("build") {
            withEnv(listOf("KEY=value", "BUILD_VERSION=1.0.0")) {
                echo("building...")
            }
        }
    }
}
