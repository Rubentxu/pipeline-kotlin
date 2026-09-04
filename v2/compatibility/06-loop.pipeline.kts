pipeline {
    stages {
        stage("loop") {
            sh("""for i in 1 2 3; do echo "iteration $i"; done""", isScriptBlock = true, returnStdout = false)
        }
    }
}
