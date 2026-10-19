// The subject is an external plugin's step: the DSL resolves a name it does not
// know at compile time, the plugin claims it, and the run completes.
//
// The transformed text is deliberately NOT asserted. The DSL is declarative -- a
// step contributes to a compiled list and its result is never handed back to the
// script -- so nothing the plugin computed can reach stdout, and an assertion on
// it would be unachievable rather than discriminating. What this proves is that
// the plugin loaded, the step resolved and nothing threw; what the step returned
// is not observable from a script and is left to the step's own coverage.
import example.uppercase.uppercase

pipeline {
    stages {
        stage("External") {
            uppercase("hello")
        }
    }
}