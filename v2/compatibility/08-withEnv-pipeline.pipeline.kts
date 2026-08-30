pipeline {
    stages {
        stage("envtest") {
            withEnv(listOf("MY_VAR=test_value", "PATH+EXTRA=/usr/local/bin")) {
                echo("MY_VAR is set")
            }
        }
    }
}
