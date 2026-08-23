// UAT-STEP-002: echo capture fixture
pipeline {
    stages {
        stage("EchoTest") {
            echo("payload")
        }
    }
}
