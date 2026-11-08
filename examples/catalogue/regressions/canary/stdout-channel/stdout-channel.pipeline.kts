// The subject is the CHANNEL: what a step prints has to reach the run's
// stdout, and the oracle has to be able to see it there.
//
// The oracle judges stdout and stderr SEPARATELY, and until now no canary
// of the battery used either field: the battery proved that steps ran and
// left artifacts, but not that the run REPORTS what it did. A run that
// swallowed the step's output would pass every other canary in the battery
// and leave nothing to audit.
//
// The marker is printed by the step itself and looked for in stdout, so
// the assertion is about the channel the text came out of and not about
// the exit code: a step that failed to print anything would still exit 0.
pipeline {
    stages {
        stage("stdout") {
            sh("printf 'CANARY-STDOUT-MARKER\\n'")
        }
    }
}
