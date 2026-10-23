//@ expect-stdout RAW_PROCESS_MARKER
//@ expect-transcript STDERR_ONLY_MARKER

// The output of a PROCESS has to reach the terminal, not only the output of
// a script that echoes a value back.
//
// The step below writes to its own stdout and the script does not capture it,
// so nothing here can be re-printed by the pipeline itself. What has to appear
// is the bytes the child process wrote, which the run committed to the Output
// Plane and the live observer then delivered. A console that only showed what
// a script prints would pass every other scenario in this suite and still hide
// everything a build said.
//
// The second step writes to its own stderr and to nothing else. A process
// that warns, logs or fails has only that channel, and what it wrote there is
// as much part of what the run committed as what it wrote on stdout. The
// transcript check is the durable one: it asks the Output Plane what the run
// COMMITTED, so a step whose only output went to stderr is either in that
// plane or in neither of them. A reader that looked at one channel only
// would report a run that said something as a run that said nothing.
pipeline {
    stages {
        stage("live") {
            sh("echo RAW_PROCESS_MARKER", isScriptBlock = false, returnStdout = false)
            sh("echo STDERR_ONLY_MARKER >&2", isScriptBlock = false, returnStdout = false)
        }
    }
}
