//@ expect-stdout RAW_PROCESS_MARKER

// The output of a PROCESS has to reach the terminal, not only the output of
// a script that echoes a value back.
//
// The step below writes to its own stdout and the script does not capture it,
// so nothing here can be re-printed by the pipeline itself. What has to appear
// is the bytes the child process wrote, which the run committed to the Output
// Plane and the live observer then delivered. A console that only showed what
// a script prints would pass every other scenario in this suite and still hide
// everything a build said.
pipeline {
    stages {
        stage("live") {
            sh("echo RAW_PROCESS_MARKER", isScriptBlock = false, returnStdout = false)
        }
    }
}
