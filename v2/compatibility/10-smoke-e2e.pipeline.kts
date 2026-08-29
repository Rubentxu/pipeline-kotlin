pipeline {
    stages {
        stage("checkout") {
            sh("git clone https://github.com/remkop/picocli.git . && git checkout 10509c0af89aa3254ca14ba90d9b3b7168e57994")
        }
        stage("build") {
            sh("./gradlew assemble --no-daemon")
        }
        stage("archive") {
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
