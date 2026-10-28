//@ expect-exit 2
//@ expect-stderr custom.notCanonical
//@ storage-agnostic

// THE GUARANTEE UNDER TRIAL
//
// A pipeline the canonical engine cannot execute is refused with the SAME answer
// in both execution worlds: the same exit code, and the same refusal text
// naming the step that has to change.
//
// The operator is the consumer here, and the refusal is the whole of what they
// get. "your script uses non-canonical plugins" says a pipeline is wrong; it
// does not say WHICH step is wrong, and a script can hold twenty of them. The
// refusal that names the offending plugin key is the one that lets the
// operator act, so the guarantee is not "the run is refused" -- that is the
// fail-closed rule and it held -- but "the refusal says which step to fix".
//
// The launcher runs this scenario in BOTH worlds (with a journal and without
// one) and holds both to the same expectations, so a refusal that names the
// step in one world and not in the other fails here. That is what happened:
// the durable world computed the same analysis and threw the reasons away, so
// with `--db` the operator got the header and nothing else.
//
// The plugin key asserted below is what the AUTHOR wrote, not an internal id:
// the step's identity inside the compiled pipeline is the compiler's business
// and may change; the key is the thing the operator has to look up.
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

pipeline {
    stages {
        stage("never-runs") {
            // A plugin key no plugin claims: the run cannot be executed by the
            // canonical engine, and the refusal has to say so with the key in
            // it. Nothing in this stage may run.
            registryStep(PluginStepId("custom.notCanonical"), EncodedStepValue("{}"))
        }
    }
}
