pipeline {
    stages {
        stage("workspace-control") {
            // A relative path, resolved inside this example's own private workspace. It used to
            // be `/tmp/workflow-test`, which was outside the authorised root: the working-directory
            // projection exempted absolute paths from containment, so the pipeline passed and the
            // exemption is now gone. A shared /tmp path was also a second, separate violation --
            // AGENTS.md section 3 gives every example a private workspace and forbids shared
            // marker paths -- so this fixes both at once instead of reintroducing an escape
            // through the relative form.
            dir("workflow-test") {
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
