//@ expect-exit 1
//@ expect-stderr did not produce a PipelineSpec
//@ storage-agnostic

// THE GUARANTEE UNDER TRIAL
//
// A script that compiles but produces no pipeline is refused with the SAME
// answer in both execution worlds: the same exit code, and the same reason
// naming what the script did not do.
//
// The operator is the consumer here, and the refusal is the whole of what
// they get. "Pipeline finished with FAILURE" says the run did not end well;
// it does not say that the script never built a pipeline, which is a
// different fault with a different fix -- the script's last expression has
// to be the pipeline it wants to run. The guarantee is therefore not "the
// run is refused" -- the fail-closed rule held in both worlds -- but "the
// refusal says the script produced no pipeline".
//
// The launcher runs this scenario in BOTH worlds (with a journal and without
// one) and holds both to the same expectations, so a refusal that names the
// missing pipeline in one world and not in the other fails here. That is what
// happened: the durable world had no branch for a script that compiled
// without producing a pipeline, so with `--db` the operator got the verdict
// line and no reason at all.
//
// The text asserted below is the operator's clue, not the whole wording: the
// scenario must survive a rewording of the message, and it must not accept a
// run that only says the run failed.
"this script's last expression is not a PipelineSpec, so it builds no pipeline"
