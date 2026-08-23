// UAT-STEP-001: sh execution fixture
pipeline {
    stages {
        stage("ShTest") {
            sh("echo hello from sh")
        }
    }
}
