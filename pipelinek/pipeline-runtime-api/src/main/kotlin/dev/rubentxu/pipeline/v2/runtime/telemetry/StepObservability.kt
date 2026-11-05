package dev.rubentxu.pipeline.v2.runtime.telemetry

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The capability key of the step-facing observability seam, in the same
 * published contract as the seam it names.
 *
 * A plugin declares it in `StepContract.requiredCapabilities`, so the runtime
 * admits the step fail-closed before any handler runs and re-checks before the
 * handler executes. A plugin that does not declare it is not affected at all.
 */
val STEP_OBSERVABILITY_CAPABILITY: StepCapability = StepCapability("plugin.observability")

/**
 * The name a plugin gives to one of its own observations.
 *
 * ## WHY A TOKEN AND NOT A STRING
 *
 * The name travels into a span name and into an instrument name, so it is the
 * one thing the plugin contributes to the observable plane. A free string
 * could carry a command, a path, an address or a line of output, and a span
 * name carrying any of those is a copy of a payload outside the plane that
 * owns it. A token is not a promise about the plugin's manners: it is a SHAPE
 * that cannot spell those things, so the payload has nowhere to go. A command
 * has a space, a path has a separator, an address has a scheme, and output has
 * whatever the step printed. None of them can be written.
 *
 * ## WHY THE PLUGIN MAY NOT SUPPLY ATTRIBUTES
 *
 * The published identity table allows a fixed set of attribute keys, and no
 * plugin-owned key is among them. So the seam offers no way to attach a
 * value of the plugin's choosing: there is nowhere for one to go even if a
 * plugin wanted to put one there.
 *
 * ## WHY THE NAME IS BOUNDED
 *
 * The longest name a plugin may use is [MAX_LENGTH] characters, which is the
 * same budget the published redaction rule gives a text value. An unbounded
 * name is an unbounded number of series in somebody else's collector.
 */
class ObservationName private constructor(val value: String) {

    override fun equals(other: Any?): Boolean = other is ObservationName && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    /**
     * The rules that decide what a plugin may call one of its observations:
     * the length budget, the shape, and the factory that refuses a name it
     * would have to rewrite.
     */
    companion object {
        /** The longest name a plugin may give to one of its observations. */
        const val MAX_LENGTH: Int = 64

        /**
         * Dotted lowercase words: the shape of a name, and nothing else.
         *
         * A segment starts with a letter and continues with letters or digits,
         * so a version-like suffix is allowed and a path is not.
         */
        private val SHAPE = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")

        /**
         * The name [raw] denotes.
         *
         * It refuses rather than repairs: a name that had to be rewritten to
         * become acceptable would be a name the plugin did not choose, and the
         * span would then carry a word nobody wrote.
         *
         * The refusal does not repeat [raw]. The value a plugin wrote into a
         * name is a value this product does not echo back, because a name is
         * one of the few places a plugin can put something it should not.
         */
        fun of(raw: String): ObservationName {
            require(raw.isNotEmpty()) { "an observation name is not empty" }
            require(raw.length <= MAX_LENGTH) {
                "an observation name is at most $MAX_LENGTH characters long"
            }
            require(SHAPE.matches(raw)) {
                "an observation name is dotted lowercase words, and this one is not"
            }
            return ObservationName(raw)
        }

        /** The name [raw] denotes, or null when it does not denote one. */
        fun ofOrNull(raw: String): ObservationName? = runCatching { of(raw) }.getOrNull()
    }
}

/**
 * How an observation ended.
 *
 * The three shapes are closed and carry no payload: the class of a failure is
 * a word from this file, never a message from the run.
 */
enum class ObservationStatus {
    /** The observed work succeeded. */
    Succeeded,

    /** The observed work failed, and the fault was inside the plugin. */
    Failed,

    /** The observed work could not be attempted: something outside the plugin was unavailable. */
    Unavailable,
}

/**
 * An observation in flight.
 *
 * The two endings are different on purpose: [end] publishes the observation
 * with a status, [discard] publishes nothing at all. A plugin that knows the
 * work it described did NOT happen discards it, because an observation that
 * says the work happened is a lie about work that did not.
 */
interface StepSpan {
    /** Ends the observation with [status], so it becomes visible. */
    fun end(status: ObservationStatus)

    /** Abandons the observation: it is never ended and therefore never visible. */
    fun discard()
}

/**
 * What a step may observe about itself.
 *
 * ## WHY THE SEAM IS PUBLISHED AND NOT THE SDK
 *
 * A plugin compiled against the published artifacts cannot name a tracing
 * library's type without that library on its compile classpath, and a library
 * on a plugin's classpath is that library's API — its versions, its
 * conventions, its exporter configuration — inherited by every plugin that
 * happens to want a span. This interface is the whole surface a plugin sees:
 * three methods, no library, no exporter, no wire format.
 *
 * ## WHAT A PLUGIN MAY SAY
 *
 * A name and a status. Nothing else. There is no attribute map, no free
 * string, no payload of any kind, so a plugin cannot put a command, a secret
 * or a line of output into the observable plane even by mistake: there is
 * nowhere to put it.
 *
 * ## WHY A DISABLED RUNTIME IS STILL A SEAM
 *
 * The seam is always present for a step that declares it, even when the run
 * asked for no telemetry at all. A plugin that has to ask "is telemetry on?"
 * before it works has two code paths, and the one nobody exercises is the one
 * that breaks. Here the answer is the same seam with nothing behind it:
 * [enabled] says so, and the plugin can skip the work it does not need.
 */
interface StepObservability {
    /** Whether anything is being observed at all. */
    val enabled: Boolean

    /**
     * Opens an observation named [name], hanging under the step that is
     * running.
     */
    fun openSpan(name: ObservationName): StepSpan

    /** Records one occurrence of [status] under [name]. */
    fun count(name: ObservationName, status: ObservationStatus)
}

/**
 * The seam that observes nothing.
 *
 * It is what a run that asked for no telemetry hands to a step that declared
 * the capability, and it is a real implementation rather than a null: the
 * capability is admitted, the handler runs, and nothing is published.
 */
object NoopStepObservability : StepObservability {
    override val enabled: Boolean = false

    override fun openSpan(name: ObservationName): StepSpan = NoopStepSpan

    override fun count(name: ObservationName, status: ObservationStatus) = Unit
}

/** The observation that publishes nothing. */
object NoopStepSpan : StepSpan {
    override fun end(status: ObservationStatus) = Unit

    override fun discard() = Unit
}

/**
 * Runs [block] inside an observation named [name].
 *
 * The scope is bounded by the block: the observation ends when the block
 * returns, and it ends as [ObservationStatus.Failed] when the block throws.
 * The failure is published and the exception keeps travelling, because the
 * caller of the block still has to see it.
 */
inline fun <T> StepObservability.inSpan(name: ObservationName, block: () -> T): T {
    val span = openSpan(name)
    return try {
        val value = block()
        span.end(ObservationStatus.Succeeded)
        value
    } catch (failure: Throwable) {
        span.end(ObservationStatus.Failed)
        throw failure
    }
}
