pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = "USER")
        }
    }
}
