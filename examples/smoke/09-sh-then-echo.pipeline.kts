pipeline {
    stages {
        stage("archive") {
            sh("""echo 'artifact content' > artifact.txt""", isScriptBlock = false, returnStdout = false)
            echo("archived: artifact.txt")
            // The step under test is `sh`, and its whole observable effect is
            // the file it leaves behind. `-s` rather than `-f`: a shell that
            // creates the file and then writes nothing into it satisfies `-f`
            // and would read as a passing scenario.
            sh("test -s artifact.txt && grep -q 'artifact content' artifact.txt")
        }
    }
}
