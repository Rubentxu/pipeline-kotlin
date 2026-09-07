pipeline {
    stages {
        stage("archive") {
            sh("""echo 'artifact content' > artifact.txt""", isScriptBlock = false, returnStdout = false)
            echo("archived: artifact.txt")
        }
    }
}
