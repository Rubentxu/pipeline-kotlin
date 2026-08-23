environment {
    KEY = "value"
    BUILD_VERSION = "1.0.0"
}

pipeline {
    stages {
        stage("build") {
            echo("building...")
        }
    }
}
