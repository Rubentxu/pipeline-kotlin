package dev.rubentxu.pipeline.v2.application.attribution

/**
 * Which open step a terminal event belongs to.
 *
 * A terminal event names a step by its ordinal, and an ordinal means a
 * different step in each stage. When the terminal also names its stage,
 * exactly one open step can match — unless the run has a stage whose
 * branches run at the same time, because the branches of a parallel stage
 * share the stage index AND the ordinal: three branches with one step each
 * produce three `StepStarted` rows with the same `(stageIndex, stepIndex)`
 * and only the step NAME tells them apart.
 *
 * So the identity of an open step is the triple `(stage, ordinal, name)`,
 * and the name is used the only way it can be used safely: as a
 * DISAMBIGUATOR among the candidates that the ordinal and the stage already
 * selected. A name that selects nothing is ignored — some terminals carry a
 * generic name ("sh", "error") that names no step at all, and a terminal that
 * names nothing is not thereby a terminal that belongs to nothing.
 *
 * A terminal that names no stage —a row written by an older build decodes
 * that way, and the decoder supports that on purpose— matches by ordinal
 * alone, and then the name decides among those candidates too.
 *
 * This decision is made ONCE, here, because three projections ask it and
 * they used to answer it three different ways: the cost profile and the
 * explanation refused to attribute anything to an ambiguous terminal, and the
 * telemetry attributed the duration to the first candidate it happened to
 * find. A reader comparing the profile and the telemetry of the same run
 * would have seen two different stories about the same step, and neither
 * could be called wrong on its own terms.
 *
 * The decision is pure: it reads the open steps and the terminal, and returns
 * what it decided. It performs no I/O, holds no state and consults no clock,
 * so the same question asked twice gets the same answer.
 */
sealed interface StepAttribution {

    /**
     * Exactly one open step matches the terminal, and [openIndex] is its
     * position in the list of open steps.
     */
    data class Matched(val openIndex: Int) : StepAttribution

    /**
     * Several open steps match the terminal, [candidateCount] says how many,
     * and the terminal belongs to none of them. A caller that receives this
     * must attribute nothing: not a duration, not a closure, not a verdict.
     */
    data class Ambiguous(val candidateCount: Int) : StepAttribution

    /** No open step matches the terminal. */
    data object None : StepAttribution

    companion object {

        /**
         * Decide which open step a terminal belongs to.
         *
         * A terminal matches an open step when both name the same ordinal and
         * either the terminal names the same stage or it names no stage at
         * all. Zero matches is [None], one is [Matched], and more than one is
         * [Ambiguous] — unless [stepName] names exactly one of them, which is
         * what the branches of a parallel stage need.
         *
         * [identityOf] reads the stage, the ordinal and the name out of an
         * open step, because each projection keeps its own open-step type
         * —the event, the interval, the span— and none of them is another's.
         */
        fun <T> resolve(
            open: List<T>,
            stageIndex: Int?,
            stepIndex: Int,
            stepName: String?,
            identityOf: (T) -> OpenStepIdentity,
        ): StepAttribution {
            val byOrdinal = open.withIndex().filter { (_, candidate) ->
                val identity = identityOf(candidate)
                identity.stepIndex == stepIndex && (stageIndex == null || identity.stageIndex == stageIndex)
            }
            val candidates = disambiguate(byOrdinal, stepName, identityOf)
            return when (candidates.size) {
                0 -> None
                1 -> Matched(candidates.single().index)
                else -> Ambiguous(candidates.size)
            }
        }

        /**
         * Narrow the candidates the ordinal and the stage selected, using the
         * name the terminal carries.
         *
         * The name only ever narrows: it is applied when there is more than
         * one candidate, and a name that selects none of them is discarded
         * rather than obeyed, because a terminal that names no step —a shell
         * failure names "sh", the SDK's own failure names "error"— says
         * nothing about which step it belongs to, and obeying it would turn
         * a decidable terminal into an undecidable one.
         */
        private fun <T> disambiguate(
            candidates: List<IndexedValue<T>>,
            stepName: String?,
            identityOf: (T) -> OpenStepIdentity,
        ): List<IndexedValue<T>> {
            if (candidates.size < 2) return candidates
            val name = stepName?.takeIf { it.isNotBlank() } ?: return candidates
            val named = candidates.filter { (_, candidate) -> identityOf(candidate).stepName == name }
            return named.ifEmpty { candidates }
        }
    }
}

/**
 * The identity of an open step: the stage it belongs to, its ordinal within
 * that stage, and its name.
 *
 * The name is part of the identity because the branches of a parallel stage
 * share the stage and the ordinal: without it, three steps of one parallel
 * stage are one identity, and no terminal could be attributed to any of them.
 */
data class OpenStepIdentity(val stageIndex: Int, val stepIndex: Int, val stepName: String)
