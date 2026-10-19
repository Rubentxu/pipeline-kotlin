//@ durable
// 22-wait-until.pipeline.kts — WU-G5R.6 / S2-A8 / G5R installed-CLI canary
// Real .pipeline.kts using the structural waitUntil block through the canonical
// dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody).
//
// Validates that after G5R.4 (registry removal) and G5R.5 (durable journal), the DSL
// `waitUntil(initialRecurrencePeriod) { body }` produces a correct durable trace with
// WaitUntilPolled (≥1) + WaitUntilCompleted(origin=canonical).
//
// `//@ durable` makes the launcher run this pipeline twice against one control
// root. That is the property this scenario can actually speak for and no other
// can: a body that already reached WaitUntilCompleted must not be polled again
// on the replay, and must not fail for having done nothing new. A single run
// cannot observe it, which is why the replay branch of the launcher existed
// without ever being reached.
//
// The marker used to be the absolute path /tmp/marker-wu-g5-restore. The
// comment above it claimed the path was "unique to this corpus to avoid
// collision with other test runs", which was the opposite of what the code did:
// a fixed name in a shared directory is precisely what two concurrent runs
// collide on. It is now relative, so it resolves inside this stage's own
// workspace and the claim is true.
pipeline {
    stages {
        stage("wait-until") {
            // Setup: create the marker file so the predicate succeeds on the first poll.
            sh("touch marker-wu-g5-restore")
            // Predicate: succeeds immediately because the marker file exists.
            // Initial period = 100ms ensures the loop enters the canonical dispatch path.
            waitUntil(initialRecurrencePeriod = 100L) {
                sh("test -f marker-wu-g5-restore && echo READY")
            }
            // Cleanup: remove the marker file.
            sh("rm marker-wu-g5-restore")
        }
    }
}
