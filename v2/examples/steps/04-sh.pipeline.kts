//@ expect-stdout hello from sh
pipeline {
    stages {
        stage("shell") {
            sh("echo hello from sh")
            sh("""echo 'artifact content' > artifact.txt""", isScriptBlock = false, returnStdout = false)
        }
    }
}
