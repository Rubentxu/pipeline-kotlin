package dev.rubentxu.pipeline.v2.sdk

/**
 * The carrier a Step receives at call time: a run id, and room for a map of
 * parameters and a map of environment entries.
 *
 * The carrier holds data and no authority. A plugin registers its Step
 * families through
 * [dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor], receives
 * the decoded input and a narrow execution context at the handler, and
 * returns a typed value that its own codec frames into the wire form. The
 * file system, the clock and the network are reached only through the
 * capabilities the Step's contract declares, and nothing here grants more: a
 * Step that receives a context receives data, not authority.
 *
 * The engine fills the run id and leaves the two maps empty: a Step that
 * needs the parameters or the environment takes them from the capabilities
 * its contract declares. A Step MUST NOT mutate the maps it receives.
 *
 * ## What a plugin sees
 *
 * Two things, and no more. This carrier, which is data: a run id, a map of
 * parameters and a map of environment entries. And at the handler, the
 * decoded input of the Step's own type plus a narrow execution identity that
 * names the run, the index of the step within it, and the access to the
 * capabilities the engine supplied. Nothing else crosses the seam: there is
 * no store, no clock and no environment of the engine's own.
 *
 * ## What a plugin may do
 *
 * Exactly what its contract declared, and nothing else. A Step declares the
 * capabilities it needs, the engine supplies the ones it can, and the
 * invocation is admitted only when the declared set is inside the supplied
 * one: a capability that is missing is refused by name, before the handler
 * runs, and the handler never observes a half-provisioned call. What the
 * engine supplies, the plugin reads through a typed accessor that refuses
 * both an absent capability and a value of the wrong shape.
 *
 * ## What a plugin may not do
 *
 * It may not reach an effect it did not declare: the file system, the clock
 * and the network are behind capabilities, and a Step that wants one asks for
 * it in its contract. It may not mutate what it receives. It may not signal a
 * step failure by throwing: the handler returns a typed result, and a
 * throwable is treated as a defect of the adapter rather than as an outcome.
 * And an observation it emits does not choose its own place in the event
 * flow: the store assigns the sequence, so a plugin cannot invent a position
 * among the facts of the run.
 *
 * ## Where the rest of the contract is
 *
 * The declaration a plugin writes is a `StepContract` in the domain module:
 * the family key, the descriptor, the typed codecs and the capabilities it
 * requires. The outcome of an invocation is a closed algebra of four cases —
 * a typed value, an unknown Step, a decode refusal and a missing capability
 * — and the three refusals are decided before the handler runs. The manifest
 * a plugin ships declares its contributions and the range of the API it was
 * built against, and admission refuses a plugin outside that range before
 * its classes load.
 *
 * ## A plugin that observes itself
 *
 * `examples/example-uppercase-plugin` is a plugin built against the
 * published SDK alone: it declares a Step that asks for nothing, another
 * that asks for one public seam, and a third that asks the runtime to watch
 * it. It compiles against the published contracts and nothing else, so a
 * plugin that observes itself needs no dependency the consumer does not
 * already have.
 */
data class StepContext(
    val runId: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
) {
    /**
     * The companion that holds the one context a run needs nothing for: the
     * empty one.
     */
    companion object {
        /**
         * The context that carries nothing: no run, no parameters, no
         * environment.
         *
         * It is the value a caller passes when it needs a context before the
         * engine has resolved one.
         */
        val EMPTY = StepContext()
    }
}
