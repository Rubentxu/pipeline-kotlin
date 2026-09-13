// S2-B10 / G7 — frozen delta D2 on the real registry path: the legacy dispatcher
// SILENTLY IGNORED `excludes`; the certified candidate applies them (after the
// Jenkins default excludes). Only keep.jar may be archived.
pipeline {
    stages {
        stage("ar-g7-04") {
            sh("mkdir -p build/libs && echo keep > build/libs/keep.jar && echo skip > build/libs/skip.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false, excludes = "**/skip.jar")
        }
    }
}
