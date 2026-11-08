
// The subject is an external plugin's step that asks the RUNTIME to observe its own work.
//
// The plugin declares one capability — the observation seam — and the runtime has to admit the
// Step because of it. That admission is the thing this scenario proves, and it is a
// fail-closed decision: a runtime that did not bind the seam would REFUSE the Step before its
// handler ran, and the run would end in a refusal instead of a finished step.
//
// Two facts are observable, and both are asserted here. The discovery line proves the plugin was
// found on the runtime classpath and admitted into the composition. The step line proves the step
// actually RAN, which is what a refusal would have prevented.
//
// The observation itself is NOT asserted, and the reason is worth stating: what the runtime
// publishes about the work belongs to the tracing plane, and this run asks for no telemetry at
// all — the seam handed to the plugin is the one that observes nothing. The run asked for no
// collector, so there is nothing here that could show one.
//
// What that proves is exactly the property that matters for a plugin author: the code that
// observes is the SAME code whether or not anything is listening. A plugin that had to ask "is
// telemetry on?" before working would have two paths, and the one nobody exercises is the one
// that breaks.
//
// The transformed text is deliberately NOT asserted. The DSL is declarative -- a step contributes
// to a compiled list and its result is never handed back to the script -- so nothing the plugin
// computed can reach stdout.
import example.uppercase.uppercaseMeasured

pipeline {
    stages {
        stage("External") {
            uppercaseMeasured("hello")
        }
    }
}
