// The subject is the OTHER channel: the engine's own launch event has to
// reach stderr.
//
// The oracle judges the two channels apart, and a battery that only ever
// looked at stdout would never notice that the engine had stopped
// reporting its launch. The launch event is the structured fact the
// engine puts on stderr: its schema name is the assertion, and the run
// that does not emit it is a run whose stderr is empty for a reason
// nobody can audit.
//
// This canary and `stdout-channel` are a PAIR: one proves the text of a
// step reaches stdout, the other proves the engine's structured report
// reaches stderr. Neither says anything about the other channel.
//
// The step prints ON PURPOSE, and the oracle asks for that text on
// stdout: the two facts together say that the channels are SEPARATE. An
// engine that sent the step's text to stderr as well would satisfy
// `stderr_contains` alone, and the run would still be a run where the
// channels are not what they claim to be.
pipeline {
    stages {
        stage("launch") {
            sh("printf 'CANARY-STEP-TEXT\\n'")
        }
    }
}
