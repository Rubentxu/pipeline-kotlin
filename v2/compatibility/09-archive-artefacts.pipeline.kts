pipeline {
    stages {
        stage("archive") {
            sh("echo 'artifact content' > artifact.txt")
            archiveArtifacts(artifacts = "artifact.txt", allowEmptyArchive = false)
        }
    }
}
