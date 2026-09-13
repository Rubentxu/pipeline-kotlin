// S2-B10 / G7 — installed acceptance: non-empty match on the REGISTRY path.
// The observable archived payload must be byte-identical to the workspace source.
// Source content is `seq 1 2000`; its sha256 is computed OUTSIDE the run
// (independently of the engine) so the assertion cannot be self-referential.
pipeline {
    stages {
        stage("ar-g7-01") {
            sh("mkdir -p build/libs && seq 1 2000 > build/libs/artifact.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
