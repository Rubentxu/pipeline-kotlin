//@ expect-stdout MY_VAR is set
//@ expect-stdout KEY is value
pipeline {
    stages {
        stage("envtest") {
            sh("""export MY_VAR=test_value && echo 'MY_VAR is set'""", isScriptBlock = true, returnStdout = false)
            withEnv(listOf("KEY=value", "BUILD_VERSION=1.0.0")) {
                sh("echo KEY is ${'$'}KEY")
            }
        }
    }
}
