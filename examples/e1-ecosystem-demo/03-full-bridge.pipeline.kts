// E1.3 demo step 3 — full local-first ecosystem bridge in a single run.
//
// Pipeline shape:
//   1. seed + archive with name -> records handle under "e1-demo-bridge"
//   2. query by name -> returns Success with the recorded handle
//   3. sh observable marker -> "E1_DEMO_BRIDGE_OK"
//
// This is the canonical demo of the E1.ecosystem-local-first cycle:
// a single run, the bridge goes from sh side-effect -> archive (with
// name) -> query (resolves the handle).
pipeline {
    stages {
        stage("seed-then-archive") {
            sh("mkdir -p build/e1-bridge && echo 'one' > build/e1-bridge/a.txt && echo 'two' > build/e1-bridge/b.txt")
            archiveArtifacts(
                artifacts = "build/e1-bridge/**",
                allowEmptyArchive = true,
                name = "e1-demo-bridge",
            )
        }
        stage("query-by-name") {
            artifactQuery("e1-demo-bridge")
        }
        stage("marker") {
            sh("echo E1_DEMO_BRIDGE_OK")
        }
    }
}
