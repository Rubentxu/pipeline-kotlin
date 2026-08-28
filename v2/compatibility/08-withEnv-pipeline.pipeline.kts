pipeline {
    stages {
        stage("envtest") {
            withEnv(["MY_VAR=test_value", "PATH+EXTRA=/usr/local/bin"]) {
                echo("MY_VAR is set")
            }
        }
    }
}
