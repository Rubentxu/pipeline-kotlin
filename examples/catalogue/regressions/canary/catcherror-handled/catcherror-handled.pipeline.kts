// The subject is a CAUGHT failure: the run continues, and the run's
// outcome says so.
//
// A step that fails inside a `catchError` block is not a failed run: the
// handler runs, the stage goes on, and the engine reports the outcome as
// `unstable` rather than `success` or `failure`. The canary pins exactly
// that third value, and pinning it is the point: an engine that reported
// a handled failure as `success` would let a canary that swallows its own
// error look like a clean pass.
//
// The artifact is what proves the handler RAN. Without it, a run that
// never reached the block would look the same.
pipeline {
    stages {
        stage("handled") {
            catchError {
                sh("exit 1")
            }
            sh("mkdir -p /workspace/out && printf 'caught\\n' > /workspace/out/canary-caught.txt")
        }
    }
}
