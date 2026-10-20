//@ expect-stderr Discovered external Step plugins: example.uppercase
//@ expect-stdout registrystep-0 finished

// The subject is an external plugin's step: the DSL resolves a name it does not
// know at compile time, the plugin claims it, and the run completes.
//
// Two facts about that are observable, and both are asserted here. The
// discovery line proves the plugin was found on the runtime classpath and
// admitted into the composition, which is what an external plugin has to earn
// before any of its steps exist. The step line proves the step actually RAN.
//
// The transformed text is deliberately NOT asserted. The DSL is declarative --
// a step contributes to a compiled list and its result is never handed back
// to the script -- so nothing the plugin computed can reach stdout, and an
// assertion on it would be unachievable rather than discriminating. What this
// scenario can rule out is the failure that looks like success: a run that
// resolved the step and never executed it would print the stage and exit
// successfully, and the second assertion is what rules that out.
import example.uppercase.uppercase

pipeline {
    stages {
        stage("External") {
            uppercase("hello")
        }
    }
}