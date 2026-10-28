// 40-recover-refuses-a-half-finished-run.pipeline.kts — recovering a run that did not finish.
//
//@ durable-once
//@ recover
//@ expect-exit 0
//@ expect-stdout nothing was recovered
//
// THE GUARANTEE UNDER TRIAL
//
// `runs recover` reconciles a run that did all its work and never said so.
// It does NOT reconcile a run that has not finished its work.
//
// The run's work is its OPERATIONS, and the run's outcome is not an
// operation's outcome. A run whose first step succeeded and whose second
// step was still running when its writer died carries a terminal OPERATION
// row and NO terminal RUN fact. Reading that row as "the run finished" closes
// a half-finished run as successful — a result nobody observed, published
// under the operator's name.
//
// The same distinction the cancel adapter got:
// a terminal operation is not a terminal run. This time the adapter that got
// it wrong was the RECOVER one, and it was wrong in the same way.
//
// WHAT THE SCRIPT DOES
//
// It finishes one step and then kills its own writer in the middle of the
// next one, so the step that was in flight never reached a terminal state and
// the run never got its terminal bookend.
//
// The step that DOES finish matters: a half-finished run already has a
// terminal journal row (the finished step's), so anything that reads "a
// terminal row" as "the run is over" reconciles exactly this run.
//
// WHAT THE LAUNCHER CHECKS
//
//  1. the recover answers "nothing was recovered" — it did not close the run;
//  2. the run's own state, read through the published introspection port, is
//     still RUNNING — the run was not closed by the recover.
val pidFile = System.getenv("PIPELINEK_E2E_PID_FILE")
    ?: error("PIPELINEK_E2E_PID_FILE is not set: this scenario must run under examples/run.sh")

// The writer's own pid, so the stage below can kill the writer. The file
// lives beside the store, not in the scenario's working directory, because the
// launcher compares that directory between runs and a pid file would look
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
