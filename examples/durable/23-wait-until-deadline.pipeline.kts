//@ expect-exit 1
//@ expect-outcome failure
//@ expect-stderr Pipeline finished with FAILURE
//@ expect-stderr cause [TIMEOUT]: waitUntil deadline exceeded at poll 3 (200ms backoff ceiling)

// 23-wait-until-deadline.pipeline.kts — a waitUntil whose condition never holds ENDS.
//
// The subject of this scenario is the TERMINAL of the waitUntil aggregate, and it is the only
// place where the durable branch's terminal can be observed end to end.
//
// The API has one time parameter besides the poll interval: the backoff ceiling. The ceiling IS
// the deadline — a poll whose interval has reached the ceiling is the LAST poll, and if the
// condition has still not held the wait ends there. With initial=50ms and ceiling=200ms the
// ladder of intervals is 50, 100, 200: the wait polls three times, and the THIRD poll runs at
// the ceiling, which makes it the last one. The terminal therefore names poll 3.
//
// Before this change the durable branch had NO terminal at all. It asked whether the NEXT
// interval would exceed the ceiling, and the next interval is capped AT the ceiling, so the
// question could never be answered yes: a condition that never held polled forever. A wait
// with no end is not a slow wait, it is a run that never concludes — so this scenario is also
// the one that would catch a regression as a TIMEOUT of the harness rather than as an answer.
//
// The predicate is a shell that fails: `sh("false")` inside the waitUntil body. The body IS the
// predicate, so a failed body means "not satisfied yet", NOT "the wait failed": the body failing
// is the normal reading of a condition that has not held. The wait keeps polling, and the run
// ends with the WAIT's own timeout.
//
// The expectations are exact about that terminal: the run concludes failure (exit 1), and the
// cause is a TIMEOUT that names the poll that ran at the ceiling and the ceiling itself. The
// poll count is in the message on purpose — a terminal that said only "timed out" would not say
// whether the wait polled once or thirty times, and the ladder is the product's own arithmetic.

pipeline {
    stages {
        stage("wait-until-deadline") {
            waitUntil(initialRecurrencePeriod = 50L, maxBackoffMs = 200L) {
                sh("false")
            }
        }
    }
}
