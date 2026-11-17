// =============================================================================
// The gate of this repository, run locally with PipelineK.
// =============================================================================
//
// **What this pays.** The gate this repository already declares: `scripts/verify-all.sh`, the
// ONE entrypoint that runs the checks that read only the tree, the build with its static
// analysis and its API compatibility check, the review of the published reference, the unit
// tests, the integration tests and the mutants. It is not a test framework and it has no
// registry: it stops at the first failing stage so that a red run names the stage that is red.
//
// **Why here.** The gate is the same command wherever it runs, so it is declared once and run
// on the machine of whoever changed the tree. The verdict is a run with its events and its
// receipts, not a line on somebody else's account.
//
// **The cadence is the repository's, not the pipeline's.** The gate is paid once every ten
// tasks of the board, whole and without filters. A pipeline that ran it on every change would
// be a gate nobody waits for.
//
// **No hosted workflow runs this.** GitHub Actions, if it ever does anything here, is
// distribution — publishing the wiki, the pages — and never the tests and never the gate.
//
// To run it:
//
//   root="$(git rev-parse --show-toplevel)"
//   state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/$(basename "$root")"
//   mkdir -p "$state"
//   pipelinek run --workspace "$root" --db "$state/run.sqlite" \
//     --control-root "$state/control" "$root/pipeline.kts"

pipeline {
    stages {
        stage("gate") {
            // **The whole gate, and its verdict is the line it prints.** The script stops at the
            // first failing stage, so a red run names the stage that is red instead of a wall
            // of output.
            timeout(time = 120, unit = "MINUTES") {
                sh("scripts/verify-all.sh")
            }
        }
    }
}
