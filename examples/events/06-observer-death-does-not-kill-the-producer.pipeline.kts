// 06-observer-death-does-not-kill-the-producer.pipeline.kts — the observer dies; the run does not.
//
//@ durable-once
//@ expect-exit 0
//@ run-state run-state terminal outcome=succeeded
//
// THE GUARANTEE UNDER TRIAL
//
// An observer is a VIEW over a run's journal. The run belongs to the producer; the observer
// only looks at it. So an observer that dies — a consumer that crashes, is reaped, or is
// killed for any reason at all — must not take the producer with it.
//
// The published symptom of the opposite is a run that ends ABORTED instead of finishing with
// its `RunFinished` bookend: the producer's own terminal fact never arrives. An observer that
// can end the run is not an observer; it is a participant in the run's life.
//
// WHAT THE SCRIPT DOES
//
// It spawns a real observer — a second process, the `pipelinek events --follow` verb, pointed
// at this run's own database — waits until that observer has actually delivered a fact (so the
// coupling was live, not hypothetical), and then the pipeline kills it with SIGKILL while the
// producer keeps working.
//
// Four things are asserted, and each of them can fail on its own:
//   * the observer really attached: its output carries a fact of THIS run.
//   * the observer really died: the kill is a step, so a kill that did not take fails the run.
//   * the producer kept going: the step AFTER the kill ran, and the run finished.
//   * the run ended `succeeded`: the launcher's `run-state` check, which is the run's own
//     terminal fact. A run that was aborted instead says so, and the scenario fails.
//
// WHY THE OBSERVER IS A SEPARATE PROCESS
//
// The guarantee is about a process boundary: a follower that dies in another JVM must not
// signal the owner. A follower inside this JVM would prove nothing, because its death could
// not be separated from the producer's. So the observer is spawned as its own `java` process,
// reading the producer's database over the same file the producer writes.
//
// WHAT THIS DOES NOT CLAIM
//
// The observer is killed with SIGKILL, so it never gets to clean up. A producer that survived
// this says nothing about a producer that survived an observer which died in some other way.
// What is claimed is the terminal fact: the run ended the way a run ends when nothing took it
// down — with its own bookend.

val root = System.getenv("PIPELINEK_E2E_ROOT")
    ?: error("PIPELINEK_E2E_ROOT is not set: this scenario must run under examples/run.sh")

// The run's own id. The producer recorded it before the script was even evaluated, because
// a consumer that attaches to a live run cannot know a UUID in advance either.
val runId = java.io.File(root, "control/last-run")
    .listFiles()
    ?.filter { it.isFile }
    ?.map { it.readLines().firstOrNull { line -> line.isNotBlank() } }
    ?.firstOrNull { it != null }
    ?: error("the producer left no run id, so there is nothing to observe")

// The observer: the real verb, in a real second process, pointed at this run's own database.
val observerOut = java.io.File(root, "observer.out")
val observer = ProcessBuilder(
    "${System.getProperty("java.home")}/bin/java",
    "-cp",
    System.getProperty("java.class.path"),
    "dev.rubentxu.pipeline.v2.application.MainKt",
    "events",
    runId,
    "--db",
    "$root/events.db",
    "--follow",
)
    // The observer's output goes to a FILE, not to this process's pipes: a step waits for
    // its pipes to close, and an observer holding them open would keep the step waiting for
    // a run that has not finished yet.
    .redirectOutput(observerOut)
    .redirectErrorStream(true)
    .start()

// The observer's pid, so the stage below can kill it. The file lives in the scenario's
// private root, NOT in its working directory: the launcher compares that directory between
// runs, and a pid file differs between them (two processes, two pids).
val observerPidFile = java.io.File(root, "observer.pid")
observerPidFile.writeText(observer.pid().toString())

// Wait until the observer has really delivered a fact of THIS run. Without this the
// scenario could pass without the coupling ever having been live: an observer that died at
// startup would look exactly like an observer whose death was harmless.
val deadline = System.nanoTime() + 60_000_000_000L
var delivered = false
while (System.nanoTime() < deadline) {
    if (runCatching { observerOut.readText() }.getOrDefault("").contains("\"kind\":")) {
        delivered = true
        break
    }
    if (!observer.isAlive) break
    Thread.sleep(50)
}
check(delivered) {
    "the observer never delivered a fact of this run; it said: " +
        runCatching { observerOut.readText() }.getOrDefault("")
}

pipeline {
    stages {
        stage("observe") {
            // The observer dies here: SIGKILL, in mid-flight, with the producer running.
            // The step fails if the kill does not take, so "the observer was killed" is
            // never assumed.
            sh("kill -9 \$(cat '$observerPidFile')")

            // And the producer is still here: this step runs AFTER the observer died.
            sh("echo 'the producer survived the death of its observer'")
        }
    }
}
