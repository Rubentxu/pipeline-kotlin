//@ expect-stdout a.txt
val controlRoot = System.getenv("PIPELINEK_E2E_CONTROL_ROOT") ?: System.getProperty("user.dir")
pipeline {
    stages {
        stage("seed-then-archive") {
            sh("mkdir -p build/utils/mix && echo 'alpha' > build/utils/mix/a.txt && echo 'beta' > build/utils/mix/b.txt")
            archiveArtifacts(
                artifacts = "build/utils/mix/**",
                allowEmptyArchive = true,
                name = "mix",
            )
        }
        stage("query-by-name") {
            artifactQuery("mix")
            sh("find '$controlRoot/artefacts' -type f")
        }
    }
}
