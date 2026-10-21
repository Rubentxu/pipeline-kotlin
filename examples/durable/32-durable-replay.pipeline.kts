//@ expect-stdout effects=1
// 32-durable-replay.pipeline.kts — the ONE real replay guarantee.
//
//@ durable
//
// THE GUARANTEE UNDER TRIAL
//
// A resumed run does not repeat a completed effect.
//
// The `//@ durable` directive existed before any scenario used it, so it was a
// promise nobody had kept: the launcher re-ran the script, but against a
// volatile store, where there is no journal and therefore nothing to resume.
// The launcher now gives a durable scenario a real durable store (`--db`), and
// this scenario is the one that puts the promise on trial.
//
// WHAT THE PRODUCT ACTUALLY DOES (measured, not assumed)
//
// `sh` declares `ReplayPolicy.RERUN`, and a journalled SUCCESS is a reusable
// completion: the resumed run REUSES it instead of executing the command
// again. The marker below therefore holds exactly one line after BOTH runs.
//
// THE SCRIPT IS IDENTICAL IN BOTH RUNS, AND IT HAS TO BE
//
// A resumed run is not a fresh run with a warm cache: the engine compares the
// canonical trace against the journal, and a step whose INPUT changed is a
// DIVERGENCE, not a new operation. So the script must not read the marker at
// compile time — a check that observed "one line, then two lines" would change
// its own input and make the resumed run diverge instead of proving anything.
// The script is byte-for-byte the same in both invocations, and the launcher
// owns the observation of the state after the second run.
//
// The marker lives in PIPELINEK_E2E_HOME: the launcher's private directory for
// this scenario, unique per scenario, removed with it, and outside the managed
// scratch workspace that `--isolated` recreates on every run.
val home = System.getenv("PIPELINEK_E2E_HOME")
    ?: error("PIPELINEK_E2E_HOME is not set: this scenario must run under examples/run.sh")
val marker = "$home/effect.log"

pipeline {
    stages {
        stage("replay") {
            // The effect under trial. Its completion is journalled, so the
            // resumed run reuses it and the marker keeps a single line.
            sh("printf 'effect\\n' >> '$marker'")

            // The check, in the run that performs the effect. It says "exactly
            // one effect" — the count the resumed run must preserve. The
            // launcher compares the workspace after the second run, which is
            // where the second half of the guarantee is observed.
            sh("test \"\$(wc -l < '$marker')\" -eq 1 && echo 'effects=1'")
        }
    }
}
