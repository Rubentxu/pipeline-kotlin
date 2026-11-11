package dev.rubentxu.pipeline.v2.domain.step

/**
 * The effective values of the parameters of ONE run.
 *
 * ## What it carries
 *
 * The value of every parameter the run's pipeline DECLARED: the one a caller delivered, or
 * the one the declaration itself carries when nothing was delivered. A name the pipeline does
 * not declare is not here: a run that accepts a value nobody declared is refused before the
 * run starts, so this map never carries a name the pipeline does not know.
 *
 * ## Why it is immutable and per run
 *
 * A run's parameters are decided when the run starts and do not change while it lives: two
 * steps of the same run that read the same parameter see the same value, and a resumed run
 * sees the value its own invocation delivered. The map is copied on the way in and is never
 * mutated, so a handler that holds this cannot change what the next handler reads.
 *
 * ## What it is NOT
 *
 * It is not the environment of the steps, and it is not a channel to the shell: the values
 * reach a step through the capability below and through nothing else. It is not a secret
 * store either — a parameter's value is the caller's own text, and nothing here decides
 * whether it is one.
 */
class RunParameterValues private constructor(
    /**
     * The values, by the name of the parameter they belong to.
     *
     * The map is unmodifiable and its iteration order is the order the values were accepted
     * in; the canonical order (sorted by name) is a property of the CALLER that computes a
     * fingerprint, never of this map.
     */
    val values: Map<String, String>,
) {

    /** Whether this carries no value at all. */
    val isEmpty: Boolean get() = values.isEmpty()

    /** How many values this carries. */
    val size: Int get() = values.size

    /**
     * The value of [name], or null when the run carries no value for it.
     *
     * A null here is a fact about the run, not a missing value: the run either declares the
     * parameter (and then carries a value for it) or does not declare it at all.
     */
    fun valueOf(name: String): String? = values[name]

    /**
     * The shape of these values, without the values themselves.
     *
     * The values do not travel: they are the caller's own text, and a line that carried them
     * would publish them to whoever reads the log.
     */
    override fun toString(): String = "RunParameterValues(${values.size} values)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is RunParameterValues && other.values == values)

    override fun hashCode(): Int = values.hashCode()

    /**
     * The two ways a carrier of values is built.
     *
     * The companion carries no state of its own: it is the place where the EMPTY carrier
     * and the copying factory live, so that a caller never reaches the private constructor
     * and never holds a carrier that another caller can change.
     */
    companion object {

        /** The values of a run that declares no parameter. */
        val EMPTY: RunParameterValues = RunParameterValues(emptyMap())

        /**
         * The values of [values], copied so that the caller cannot change them afterwards.
         *
         * The copy is what makes this per run rather than per caller: a map the caller keeps a
         * reference to could change under the run that is reading it, and a run's parameters
         * do not change while the run lives.
         */
        fun of(values: Map<String, String>): RunParameterValues =
            if (values.isEmpty()) EMPTY else RunParameterValues(values.toMap())
    }
}

/**
 * Capability key under which the engine supplies the [RunParameterValues] of the run to a
 * handler that reads a parameter.
 *
 * The value is populated by
 * `dev.rubentxu.pipeline.v2.runtime.durable.RuntimeCapabilityAccess` from the per-invocation
 * `dev.rubentxu.pipeline.v2.runtime.durable.RuntimeContext`, which the run's own policy
 * carries. There is NO shared mutable state: two handlers in the same JVM each receive their
 * own carrier, because the value comes from the run that owns the invocation and not from a
 * process-wide map.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is fail-closed before the handler
 * runs when it is not available. A run that carries no parameters exposes the capability with
 * an EMPTY carrier, so a handler that asks for a parameter the run does not declare learns
 * that from the value it reads and not from a missing capability.
 */
val RUN_PARAMETERS_CAPABILITY: StepCapability = StepCapability("runtime.run-parameters")
