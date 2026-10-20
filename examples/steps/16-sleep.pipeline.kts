//@ expect-stdout woke

// The subject is the `sleep` Step: the run must not continue before the
// requested delay has elapsed.
//
// An echo after the sleep only proves the pipeline kept going; it says
// nothing about whether the wait happened at all, and a sleep that returned
// immediately would leave this scenario exactly as green. The clock is
// therefore read before and after the sleep, and the elapsed seconds are what
// the check looks at: a delay that did not wait, or that waited less than the
// second it was asked for, leaves the difference below one.
//
// The timestamps live in this stage's own workspace, so the scenario needs no
// shared path and no cleanup outside its private directory.
pipeline {
    stages {
        stage("sleep-step") {
            sh("date +%s > sleep-started")
            sleep(1)
            sh("date +%s > sleep-ended")
            sh("test \"$(( $(cat sleep-ended) - $(cat sleep-started) ))\" -ge 1")
            echo("woke")
        }
    }
}
