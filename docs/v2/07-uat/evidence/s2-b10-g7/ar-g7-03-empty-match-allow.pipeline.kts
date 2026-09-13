// S2-B10 / G7 — DISCRIMINATOR. Byte-for-byte identical to AR-G7-02 except the
// policy flag. Same glob, same workspace, same absence of *.jar: if 02 fails and
// 03 succeeds, the 02 failure is the typed empty-match POLICY, not a path/glob
// defect (the G2 legacy defect could never match at all).
pipeline {
    stages {
        stage("ar-g7-03") {
            sh("mkdir -p build/libs && echo not-a-jar > build/libs/README.txt")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = true)
        }
    }
}
