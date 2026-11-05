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
