// 22-wait-until.pipeline.kts — WU-G5R.6 / S2-A8 / G5R installed-CLI canary
// Real .pipeline.kts using the structural waitUntil block through the canonical
// dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody).
//
// Validates that after G5R.4 (registry removal) and G5R.5 (durable journal), the DSL
// `waitUntil(initialRecurrencePeriod) { body }` produces a correct durable trace with
// WaitUntilPolled (≥1) + WaitUntilCompleted(origin=canonical).
//
// The marker file path is unique to this corpus to avoid collision with other test runs.
pipeline {
    stages {
        stage("wait-until") {
            // Setup: create the marker file so the predicate succeeds on the first poll.
            sh("touch /tmp/marker-wu-g5-restore")
            // Predicate: succeeds immediately because the marker file exists.
            // Initial period = 100ms ensures the loop enters the canonical dispatch path.
            waitUntil(initialRecurrencePeriod = 100L) {
                sh("test -f /tmp/marker-wu-g5-restore && echo READY")
            }
            // Cleanup: remove the marker file.
            sh("rm /tmp/marker-wu-g5-restore")
        }
    }
}
