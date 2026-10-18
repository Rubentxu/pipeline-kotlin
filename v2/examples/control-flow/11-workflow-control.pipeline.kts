pipeline {
    stages {
        stage("workspace-control") {
            dir("/tmp/workflow-test") {
                sh("echo 'inside dir'")
            }
            deleteDir()
        }
        stage("retry-block") {
            timeout(30, "SECONDS") {
                retry(3) {
                    sh("echo 'step-A'")
                    sh("echo 'step-B'")
                }
            }
        }
        stage("error-handling") {
            catchError {
                sh("exit 1")
            }
            unstable("flaky")
        }
    }
}
