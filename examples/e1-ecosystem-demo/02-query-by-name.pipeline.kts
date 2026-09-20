// E1.3 demo step 2 — query by name in a fresh run (handle gone — index is per-run).
//
// This pipeline demonstrates the typed NOT_FOUND path: the per-run
// ArtifactIndex is deliberately ephemeral; a second invocation of
// pipelinek cannot look up handles recorded in a previous run. The
// filesystem under <control-root>/artefacts/ remains the durable record;
// an external re-derivation is out of cycle scope.
//
// Expected terminal outcome: failure (typed USER failure, NotFound, kind USER).
pipeline {
    stages {
        stage("query-prior-run-handle") {
            artifactQuery("e1-demo-jar")
        }
    }
}
