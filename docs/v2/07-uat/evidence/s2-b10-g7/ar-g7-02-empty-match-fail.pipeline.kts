// S2-B10 / G7 — installed acceptance: EMPTY match with allowEmptyArchive=false.
// The workspace exists and contains a file, but nothing matches *.jar, so the
// registry path MUST fail closed with the typed SCRIPT failure (no crash, no
// silent no-op) and archive nothing.
pipeline {
    stages {
        stage("ar-g7-02") {
            sh("mkdir -p build/libs && echo not-a-jar > build/libs/README.txt")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
