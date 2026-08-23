pipeline {
    stages {
        stage("shell") {
            sh(["echo", "hello from sh"])
        }
    }
}
