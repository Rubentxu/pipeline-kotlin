// E1.1 / T8 corpus fixture — DSL compile surface for core.artifact.query.
//
// Purpose: assert the E1.1 ecosystem-local-first DSL facade
// compiles inside a real pipeline script and is reachable by an
// orchestrator bootstrap (NOT a data round-trip — that belongs to
// E1.2 once ArtifactIndexCapability is wired into the production
// runtime context).
//
// Pipeline shape (single stage, single step, legacy path only):
//   archiveArtifacts("build/utils/**", allowEmptyArchive = true)
//     — registers the legacy codec path unchanged from F1.
//   sh("echo E1_1_T8_DSL_OK")
//     — terminates with an observable marker so an external
//       probe (UAT S1..S5) can distinguish a green run from a
//       coroutine-only exit-zero.
//
// The cross-step archive→query data path is intentionally
// deferred to E1.2; a runtime-bound artifactQuery that resolves
// against a wired artifact index would fail-closed today and
// produce a red fixture, which is the wrong narrative for a
// "DSL compile surface" check.
pipeline {
    stages {
        stage("artifact-query-dsl-surface") {
            archiveArtifacts(
                artifacts = "build/utils/**",
                allowEmptyArchive = true,
            )
            sh("echo E1_1_T8_DSL_OK")
        }
    }
}
