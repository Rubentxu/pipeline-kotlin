package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment

/**
 * Typed operation identifier. Format: "$runId-s$stageIndex-$stepIndex[-b$branchIndex][-bp{N}-{seg}...]".
 * The format is designed to be lexicographically sortable by runId, stage, then step,
 * which enables efficient range queries on the journal.
 * When branchIndex is non-null, the format extends to: "$runId-s$stageIndex-$stepIndex-b$branchIndex"
 * When bodyPath is non-empty, the format extends to: "$runId-s$stageIndex-$stepIndex[-b$branchIndex]-bp{N}-{idx}:{pluginId}..."
 * @param runId The pipeline run identifier.
 * @param stageIndex The 0-based stage index within the pipeline.
 * @param stepIndex The 0-based step index within the stage.
 * @param branchIndex The optional 0-based branch index for parallel frame execution.
 * @param bodyPath The optional list of block segments for body dispatch (length-prefix)
*/
data class OpId(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val branchIndex: Int? = null,
    val bodyPath: List<BlockSegment> = emptyList(),
) {
    /**
     * The parallel lineage this operation runs in: the ordered list of `branch`
     * frame indices along [bodyPath], outermost first.
     * Empty for a linear path, one element for a single level of `parallel`, and
     * N elements for N levels — so nested parallel extends this WITHOUT changing
     * the shape, which is why callers should derive a lane from it rather than
     * from [branchIndex] alone.
     * This is deliberately a `List<Int>` and not a single index. Freezing
     * `branchIndex` as the model of "which lane am I in" would make nested
     * parallel impossible to represent without a breaking change, and a lane
     * identity that cannot express nesting is a lane identity that will be
     * wrong the moment nesting exists.
    */
    val parallelLineage: List<Int>
        get() = bodyPath.mapNotNull { segment ->
            // BlockSegment is a value class over "{index}:{pluginStepId}", so the
            // branch frame is recognised by its SUFFIX, not by a field that does
            // not exist on the type.
            val encoded = segment.encoded
            if (!encoded.endsWith(":$PARALLEL_BRANCH_SEGMENT")) return@mapNotNull null
            encoded.substringBeforeLast(':').toIntOrNull()
        }

    /**
     * Formats this OpId into the canonical string representation with length-prefix bodyPath.
     * @return String in format "$runId-s$stageIndex-$stepIndex[-b$branchIndex][-bp{N}-{seg}...]"
    */
    fun format(): String = buildString {
        append(runId); append("-s"); append(stageIndex); append('-'); append(stepIndex)
        if (branchIndex != null) { append("-b"); append(branchIndex) }
        if (bodyPath.isNotEmpty()) {
            append("-bp"); append(bodyPath.size)
            bodyPath.forEach { seg -> append('-'); append(seg.encoded) }
        }
    }

    /**
     * Returns the string representation (same as format).
    */
    override fun toString(): String = format()

    companion object {
        /**
         * `pluginStepId` the parallel engine writes for a branch frame segment.
         * Named here so [parallelLineage] and the engine that emits the segment
         * cannot drift apart.
    */
        const val PARALLEL_BRANCH_SEGMENT = "branch"

        /**
         * The root of an operation id: the run, the stage, the step and, when the
         * operation runs inside a branch frame, the branch. The run identifier is
         * opaque, so the pattern is greedy and reads the LAST `s{stage}-{step}`
         * — which is where the format puts it.
        */
        private val ROOT_PATTERN = Regex("^(.+)-s(\\d+)-(\\d+)(-b(\\d+))?$")

        /**
         * The body of an operation id: how many segments it has, and the segments
         * joined by hyphens.
        */
        private val BODY_PATTERN = Regex("^-bp(\\d+)-(.+)$")

        /** Where a body starts. Named so the search and the pattern cannot drift apart. */
        private const val BODY_MARKER = "-bp"

        /**
         * Reads an operation id from its text form.
         *
         * The text is read the way it is written: a root —run, stage, step and
         * an optional branch— and, after it, a body written as `-bp{N}-` followed
         * by the segments.
         *
         * The body is read only when it can be read **exactly**. Its segments are
         * joined with a hyphen, so a segment that carries a hyphen of its own
         * leaves more hyphens than the body has segments, and then the text can
         * be cut in more than one way. Each cut is a different identity, so
         * rather than pick one, the text is **refused**: a reader that returns an
         * identity it had to guess is worse than a reader that returns none,
         * because the guess looks right and names something else.
         *
         * The same applies to the text as a whole: when it can be read as more
         * than one operation id —as a root with a body, or as a root whose run
         * identifier happens to end in something that looks like one— it is
         * refused too.
         *
         * @return the identity the text names, or `null` when it names none or
         *   more than one.
        */
        fun parse(s: String): OpId? {
            val readings = readings(s)
            if (readings.size != 1) return null
            val reading = readings.single()
            val body = reading.bodyPath ?: return null
            return OpId(reading.runId, reading.stageIndex, reading.stepIndex, reading.branchIndex, body)
        }

        /**
         * Reads the stage and the step an operation id states, without reading
         * its body.
         *
         * A filter over frames asks which stage and which step an operation
         * belongs to, and both are in the root: they do not depend on how the
         * body reads. So this reads them even when the body is ambiguous —the
         * operation is still the one of that stage and that step— and refuses
         * only when the **root** reads ambiguously, which is the only case in
         * which the stage and the step could be something else.
         *
         * @return the indices the text states, or `null` when it states none or
         *   more than one.
        */
        fun parseIndices(s: String): OpIdIndices? {
            val readings = readings(s)
            if (readings.isEmpty()) return null
            val first = readings.first()
            val agrees = readings.all {
                it.stageIndex == first.stageIndex && it.stepIndex == first.stepIndex
            }
            return if (agrees) OpIdIndices(first.stageIndex, first.stepIndex) else null
        }

        /**
         * Every way the text can be read as an operation id.
         *
         * The text is split where a body starts, and the body is read only when
         * its segments can be told apart. A body that cannot be read is kept as
         * **unread**, which is not the same as absent: the root still says which
         * stage and step the text is about.
        */
        private fun readings(s: String): List<Reading> {
            val found = mutableListOf<Reading>()
            reading(s, s.length)?.let { found.add(it) }
            var at = s.indexOf(BODY_MARKER)
            while (at >= 0) {
                reading(s, at)?.let { found.add(it) }
                at = s.indexOf(BODY_MARKER, at + 1)
            }
            return found
        }

        /** One reading of the text: the root, and the body when the text carries one. */
        private fun reading(s: String, split: Int): Reading? {
            val root = ROOT_PATTERN.matchEntire(s.substring(0, split)) ?: return null
            val stageIndex = root.groupValues[2].toIntOrNull() ?: return null
            val stepIndex = root.groupValues[3].toIntOrNull() ?: return null
            val branchIndex = root.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull()

            val suffix = s.substring(split)
            if (suffix.isEmpty()) {
                return Reading(root.groupValues[1], stageIndex, stepIndex, branchIndex, emptyList())
            }
            val body = BODY_PATTERN.matchEntire(suffix) ?: return null
            val count = body.groupValues[1].toIntOrNull() ?: return null
            return Reading(
                root.groupValues[1],
                stageIndex,
                stepIndex,
                branchIndex,
                segments(body.groupValues[2], count),
            )
        }

        /**
         * The segments of a body, or `null` when they cannot be told apart.
         *
         * With one segment there is nothing to tell apart: the whole text is
         * the segment. With more, the segments are joined by hyphens, and the
         * join is only unambiguous when the text has exactly one hyphen less
         * than it has segments. A body that can be cut in more than one way is
         * **not read**, because each cut is a different identity.
        */
        private fun segments(text: String, count: Int): List<BlockSegment>? {
            if (count < 1 || text.isBlank()) return null
            if (count == 1) return listOf(BlockSegment(text))
            val pieces = text.split("-")
            if (pieces.size != count) return null
            if (pieces.any { it.isBlank() }) return null
            return pieces.map { BlockSegment(it) }
        }
    }
}

/**
 * The stage and the step an operation id states.
 *
 * A filter over frames needs these two numbers and nothing else, and they are
 * the one part of an operation id that does not depend on how its body reads.
 * They come apart from the identity itself on purpose: an identity that could
 * not be read whole still says which stage and step it is about, and a reader
 * that returns none of them would take that away.
*/
data class OpIdIndices(val stageIndex: Int, val stepIndex: Int)

/**
 * One reading of an operation id.
 *
 * [bodyPath] is `null` when the text carries a body that cannot be read without
 * guessing, which is not the same as an empty body.
*/
private class Reading(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val branchIndex: Int?,
    val bodyPath: List<BlockSegment>?,
)
