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
// The symptom of the opposite is a run that ends ABORTED instead of finishing with its
// `RunFinished` bookend: the producer's own terminal fact never arrives. An observer that
// can end the run is not an observer; it is a participant in the run's life.
//
// WHAT THE SCRIPT DOES
//
// It starts a real observer — the `pipelinek events --follow` verb, in a process of its own,
// pointed at this run's own database — waits until that observer has actually delivered a fact
// (so the coupling was live, not hypothetical), and then the pipeline kills it with SIGKILL
// while the producer keeps working.
//
// Four things are asserted, and each of them can fail on its own:
//   * the observer really attached: its output carries a fact of THIS run.
//   * the observer really died: the kill is a step, and that step checks that nothing of the
//     observer is left running.
//   * the producer kept going: the step AFTER the kill ran, and the run finished.
//   * the run ended `succeeded`: the launcher's own terminal-fact check. A run that was
//     aborted instead says so, and the scenario fails.
//
// WHY THE OBSERVER GOES THROUGH THE `sh` STEP
//
// A program that starts a process of its own owns nothing: not the temporaries the new
// process takes, not the deadline it runs under, not the exit code it leaves behind. The
// `sh` step is the door the product owns end to end, so the observer is started through it.
// A scenario that reaches for the process API behind the product's back is a second
// authority that the product neither knows nor refuses, and the repository does not allow
// it: a guard reads every scenario program and refuses one that creates a process.
//
// The observer is given a session of its own and none of the cookies that identify the step
// that started it. Two reasons, both measured: the step's watchdog kills the session of a
// step that ran too long, and it finds that session by a cookie that every child inherits —
// so an observer that kept the cookie could be killed together with the step, and the
// scenario would then be asserting something it never made happen.
//
// The observer is not detached from the product's accounting either: it inherits the options
// the launcher gave the product, so its own temporaries land in this cycle's directory and
// not in the shared temporary directory of the machine.
//
// WHY THE OBSERVER IS A SEPARATE PROCESS
//
// The guarantee is about a process boundary: a follower that dies in another JVM must not
// signal the owner. A follower inside this JVM would prove nothing, because its death could
// not be separated from the producer's. So the observer is a process of its own, reading the
// producer's database over the same file the producer writes.
//
// WHAT THIS DOES NOT CLAIM
//
// The observer is killed with SIGKILL, so it never gets to clean up. A producer that survived
// this says nothing about a producer that survived an observer which died in some other way.
// What is claimed is the terminal fact: the run ended the way a run ends when nothing took it
// down — with its own bookend.

val root = System.getenv("PIPELINEK_E2E_ROOT")
    ?: error("PIPELINEK_E2E_ROOT is not set: this scenario must run under examples/run.sh")
val control = System.getenv("PIPELINEK_E2E_CONTROL")
    ?: error("PIPELINEK_E2E_CONTROL is not set: this scenario must run under examples/run.sh")
val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

// The run's own id. The producer recorded it before the script was even evaluated, because
// a consumer that attaches to a live run cannot know a UUID in advance either. The file's
// name is the definition, not the run: the id is the FIRST line of the file.
val runId = java.io.File(control, "last-run")
    .listFiles()
    ?.filter { it.isFile }
    ?.sortedBy { it.name }
    ?.mapNotNull { record -> record.readLines().firstOrNull { line -> line.isNotBlank() } }
    ?.firstOrNull()
    ?: error("the producer left no run id, so there is nothing to observe")

// The journal this run writes to, and the file the observer's own output goes to. Both are
// absolute. The output is a FILE and not a pipe because a step waits for its pipes to close,
// and an observer holding one open would keep the step waiting for a run that has not
// finished yet.
val journal = java.io.File(root, "events.db").path
val observerOut = java.io.File(root, "observer.out").path

pipeline {
    stages {
        stage("observe") {
            // The observer starts here, through the product's own door. It is detached into a
            // session of its own, and the cookies that identify THIS step are taken away from
            // it, so the step's watchdog can never mistake it for the step's own machinery.
            //
            // The wait is the first assertion: an observer that died at startup would look
            // exactly like an observer whose death was harmless, so the coupling has to be
            // proven live before anything is killed.
            sh(
                """
                setsid env -u PIPELINE_OP_COOKIE -u DURABLE_SH_COOKIE -u DURABLE_SH_OPID '${product}' events '${runId}' --db '${journal}' --follow > '${observerOut}' 2>&1 < /dev/null &
                attempt=0
                while (( attempt < 100 )); do
                    if command grep -q '"kind":' '${observerOut}' 2>/dev/null; then
                        exit 0
                    fi
                    attempt=${'$'}(( attempt + 1 ))
                    sleep 0.2
                done
                echo 'the observer never delivered a fact of this run; it said:' >&2
                command cat '${observerOut}' >&2 || true
                exit 1
                """.trimIndent(),
            )

            // The observer dies here: SIGKILL, in mid-flight, with the producer running.
            //
            // The victim is found by what it is, not by what it was told: the process that
            // follows THIS journal. The run id is NOT the marker, because the run id is part
            // of the name of the directory that holds this very script -- a marker built from
            // it would find the step that is doing the killing. The marker is the following
            // flag together with this journal's path, and only the observer carries both.
            //
            // Every process of the observer is killed, not only the one that was launched
            // first: the entry point, the timeout around it and the JVM under it are three
            // processes, and a SIGKILL to the outer one leaves the inner one running.
            sh(
                """
                observer_pids() {
                    local record cmd pid
                    for record in ${'$'}(command grep -l -z -F -- '--follow' /proc/[0-9]*/cmdline 2>/dev/null); do
                        cmd=${'$'}(tr '\0' ' ' < "${'$'}record" 2>/dev/null) || continue
                        case "${'$'}cmd" in
                            *'${journal}'*) pid="${'$'}{record#/proc/}"; printf '%s\n' "${'$'}{pid%/cmdline}" ;;
                        esac
                    done
                }
                victims=${'$'}(observer_pids)
                if [[ -z "${'$'}victims" ]]; then
                    echo 'no process is following this run, so there was nothing to kill' >&2
                    exit 1
                fi
                for pid in ${'$'}victims; do
                    kill -9 "${'$'}pid" 2>/dev/null || true
                done
                attempt=0
                while (( attempt < 20 )); do
                    survivors=${'$'}(observer_pids)
                    if [[ -z "${'$'}survivors" ]]; then
                        exit 0
                    fi
                    attempt=${'$'}(( attempt + 1 ))
                    sleep 0.5
                done
                echo 'the observer survived the kill; these processes still follow the run:' >&2
                printf '%s\n' "${'$'}survivors" >&2
                exit 1
                """.trimIndent(),
            )

            // And the producer is still here: this step runs AFTER the observer died.
            sh("echo 'the producer survived the death of its observer'")
        }
    }
}
