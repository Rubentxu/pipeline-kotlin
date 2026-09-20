// E1.3 demo step 1 — archive with name (records handle in the index).
//
// Same bridge as compatibility/30-artifact-query-bridge.pipeline.kts but
// runs against the real installed pipelinek binary (this README links to
// the recipe at examples/e1-ecosystem-demo/README.md).
pipeline {
    stages {
        stage("seed-then-archive") {
            sh("mkdir -p build/e1-demo && echo 'alpha' > build/e1-demo/a.txt && echo 'beta' > build/e1-demo/b.txt")
            archiveArtifacts(
                artifacts = "build/e1-demo/**",
                allowEmptyArchive = true,
                name = "e1-demo-jar",
            )
        }
    }
}
