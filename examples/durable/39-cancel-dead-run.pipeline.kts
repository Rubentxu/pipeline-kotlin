// 39-cancel-dead-run.pipeline.kts — closing a run whose writer was killed.
//
//@ durable-once
//@ cancel
//@ expect-exit 0
//@ expect-stdout cancelled run
//
// THE GUARANTEE UNDER TRIAL
//
// A run that no live process is working on can be closed, exactly once.
//
// A writer that is killed — the CI agent reaped, the machine rebooted, the
// job cancelled — leaves the run in a state nobody designed: the journal says
// a step was still running, and the event lane says the run never finished.
// Two facts that disagree, and no way for an operator to say "that run is
// over". `pipelinek runs cancel` is that way out.
//
// The guarantee it carries is that the run is closed ONCE. The second call
// has to be a no-op — not a second terminal state, and not a refusal. The
// launcher checks all three halves: the first cancel closes the run, the
// second one reports it as already closed, and the run's history ends with a
// terminal bookend.
//
// WHAT THE SCRIPT DOES
//
// It finishes one step and then kills its own writer in the middle of the
// next one, so the step that was in flight never reached a terminal state
// and the run never got its bookend. That is the state the cancel is for.
//
// The step that DOES finish matters: a half-finished run already has a
// terminal journal row (the finished step's), so anything that reads "a
// terminal row" as "the run is over" refuses to cancel exactly this run.
val pidFile = System.getenv("PIPELINEK_E2E_PID_FILE")
    ?: error("PIPELINEK_E2E_PID_FILE is not set: this scenario must run under examples/run.sh")

// The writer's own pid, so the stage below can kill the writer. The file
// lives beside the store, not in the scenario's working directory, because
// the launcher compares that directory between runs and a pid file would look
// like a changed effect.
java.io.File(pidFile).writeText(ProcessHandle.current().pid().toString())

pipeline {
    stages {
        stage("work") {
            // A step that finishes. Its row reaches a terminal state.
            sh("echo 'the-step-that-finished'")

            // The writer dies here. The step below never runs, its journal row
            // stays RUNNING, and the run never gets its terminal bookend.
            sh("kill -9 \$(cat '$pidFile')")
            sh("echo 'never-reached'")
        }
    }
}
