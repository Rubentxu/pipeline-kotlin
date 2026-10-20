pipeline {
    stages {
        stage("envtest") {
            sh("""export MY_VAR=test_value && echo 'MY_VAR is set'""", isScriptBlock = true, returnStdout = false)
        }
    }
}
