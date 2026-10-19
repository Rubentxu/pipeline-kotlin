// 22-wait-until.pipeline.kts — WU-G5R.6 / S2-A8 / G5R installed-CLI canary
// Real .pipeline.kts using the structural waitUntil block through the canonical
// dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody).
//
// Validates that after G5R.4 (registry removal) and G5R.5 (durable journal), the DSL
// `waitUntil(initialRecurrencePeriod) { body }` produces a correct durable trace with
// WaitUntilPolled (≥1) + WaitUntilCompleted(origin=canonical).
//
// The marker file lives in PIPELINEK_E2E_HOME, the launcher's private directory
// for this scenario. A fixed path under /tmp is NOT unique: two runs on the
// same machine would share it, and the cleanup below would delete the marker
// the other run's predicate is waiting for.
// PIPELINEK_E2E_HOME is the launcher's private directory for this scenario.
// Run by hand, the working directory is the fallback: still not a shared /tmp
// path, and the launcher is what makes it unique per scenario.
val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")
val marker = "$home/marker-wu-g5-restore"

pipeline {
    stages {
        stage("wait-until") {
            // Setup: create the marker file so the predicate succeeds on the first poll.
            sh("touch '$marker'")
            // Predicate: succeeds immediately because the marker file exists.
            // Initial period = 100ms ensures the loop enters the canonical dispatch path.
            waitUntil(initialRecurrencePeriod = 100L) {
                sh("test -f '$marker' && echo READY")
            }
            // Cleanup: remove the marker file.
            sh("rm -f '$marker'")
        }
    }
}
