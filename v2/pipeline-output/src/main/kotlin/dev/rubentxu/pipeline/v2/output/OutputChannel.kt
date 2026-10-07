package dev.rubentxu.pipeline.v2.output

/**
 * Which of a child process' two byte streams a transcript came from.
 *
 * ## Why this is a closed set and not a flag
 *
 * A process has exactly two. `merge` is not a third kind of byte — it is the *absence* of this
 * distinction, produced by concatenating what these two produced. Carrying it as an
 * `OutputChannel?` or as a `"stdout" | "stderr" | "all"` string would make "merged" look like a
 * channel that was persisted, and it is not: the Output Plane stores each byte once, under the
 * channel that produced it.
 *
 * ## What it is not
 *
 * It says nothing about ordering between the two channels, and it is not a clock. A frame's
 * [OutputFrame.ordinal] carries the order PipelineK observed; nothing here implies the kernel
 * interleaved the writes in any particular way, and a reader must never reconstruct a merged view
 * by assuming one did.
 *
 * @property token stable, lower-case name used in a stream id and in machine-readable output
 */
enum class OutputChannel(val token: String) {

    /** The child's standard output. */
    STDOUT("stdout"),

    /** The child's standard error. */
    STDERR("stderr"),
    ;

    companion object {
        /**
         * The channel named [token], or `null` when the token names none.
         *
         * `null` rather than a thrown error: this parses identifiers that arrive from a durable
         * store written by an older producer, and a token that no longer maps to a channel is
         * "not a channel-addressed stream", which is a fact a caller can act on.
         */
        fun fromToken(token: String): OutputChannel? = entries.firstOrNull { it.token == token }
    }
}

/**
 * A stream id that says which channel it holds, with the shape that makes that recoverable.
 *
 * ## The shape
 *
 * ```text
 * {runId}/{operationId}/{channel.token}
 * ```
 *
 * ## What the operation id actually is
 *
 * It is an **operation identity**, not a step identity. Production mints it with `OpId.format()` in
 * `pipeline-application`, whose shape is:
 *
 * ```text
 * {runId}-s{stageIndex}-{stepIndex}[-b{branchIndex}][-bp{N}-{childIndex}:{pluginStepId}...]
 * ```
 *
 * So the stdout stream of an `sh` in stage 0, step 0 of run `run-7` is `run-7/run-7-s0-0/stdout` —
 * **three** segments, and the run id appears twice, once as the run and once inside the operation.
 *
 * The two identities are easy to confuse because both are derived from a step, and they are not
 * interchangeable. `build/sh-0` is a **StepId**: the definition-local node id, and the value that
 * reaches the journal as `OperationInput.stepId`. It is not the operation id and never appears as the
 * middle of a stream id. A reader that builds that StepId from a step event and looks for the stream
 * finds nothing, and finds nothing for every step in the run — which reads like an empty transcript
 * rather than like the mistake it is.
 *
 * ## Read it from the ends, never from a segment count
 *
 * The middle is not required to be one segment. A `bodyPath` segment embeds a `PluginStepId`, and
 * `PluginStepId` is only required to be non-blank — so a plugin step id carrying `/` would make a
 * legitimate operation id span several segments. Production's plugin step ids (`core.sh`, `core.dir`)
 * happen to carry none, so real ids are three segments today; "today" is not a property [parse] is
 * entitled to depend on.
 *
 * The two unambiguous ends are what matter:
 *
 * - the **first** segment is the run, which is minted by the run and never contains a separator;
 * - the **last** segment is the channel token, which is a closed set.
 *
 * Everything between them is the operation id, separators and all. That is what makes
 * `{runId}/{operationId}/{channel}` injective rather than merely plausible, and it is what lets
 * [parse] round-trip an operation id it did not mint.
 *
 * ## Why the channel rides on the stream rather than beside the bytes
 *
 * A frame of metadata beside each range would be a second thing to keep in step with the bytes,
 * and keeping it in step is exactly what a crash interrupts. Making the channel part of the
 * identity instead means attribution is carried by **which stream the byte is in** — a fact that
 * survives a crash, a reopen and a cursor hand-off for free, because those already address bytes
 * by stream.
 *
 * The cost is that one operation now owns one stream per channel rather than one stream, which is
 * why [OperationOutputStreams] spells merged consumption out as a reader's job rather than
 * implying a third stream.
 *
 * ## Parsing is a capability of this producer, not of the published id
 *
 * [OutputStreamId] is documented as an opaque handle whose representation is not a contract, and
 * that stays true: [parse] is offered because this producer *chooses* this shape and has to read
 * its own records back (see recovery). It is not a promise that every [OutputStreamId] anywhere has
 * this shape — an id minted by another producer, including the pre-OBS-C2 `.../transcript` shape,
 * is opaque again and [parse] returns `null` for it.
 */
data class OutputStreamAddress(
    val runId: String,
    val operationId: String,
    val channel: OutputChannel,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(operationId.isNotBlank()) { "operationId must not be blank" }
    }

    /** The id this address denotes. */
    val stream: OutputStreamId
        get() = OutputStreamId("$runId/$operationId/${channel.token}")

    companion object {

        /** The address [operationId]'s [channel] stream lives at, within [runId]. */
        fun of(runId: String, operationId: String, channel: OutputChannel): OutputStreamAddress =
            OutputStreamAddress(runId, operationId, channel)

        /**
         * The address [stream] denotes, or `null` when it does not have this shape.
         *
         * Three segments or more, a known channel token at the end, and a non-blank run at the
         * start. Two segments or a trailing token that is not a channel both yield `null`: a partial
         * parse would invent an attribution for a stream whose middle is something else, and the
         * one real case that must not be misread is the pre-OBS-C2 `{runId}/{opId}/transcript`
         * stream, whose trailing `transcript` is not a channel.
         */
        fun parse(stream: OutputStreamId): OutputStreamAddress? {
            val segments = stream.value.split('/')
            if (segments.size < 3) return null
            val channel = OutputChannel.fromToken(segments[segments.size - 1]) ?: return null
            val runId = segments.first()
            val operationId = segments.subList(1, segments.size - 1).joinToString("/")
            if (runId.isBlank() || operationId.isBlank()) return null
            return OutputStreamAddress(runId, operationId, channel)
        }
    }
}

/**
 * Both channel streams of one operation, and what a reader does with them.
 *
 * A "merged" console is not stored. It is **read**, by interleaving the frames of both streams by
 * [OutputFrame.ordinal], because that ordinal is the order PipelineK observed and nothing else. A
 * consumer that wants merged bytes therefore needs [OutputFrameIndex] as well as a read port, and
 * that dependency is the honest shape: the interleaving is a fact that lives in the index, not in
 * either byte stream.
 */
data class OperationOutputStreams(
    val stdout: OutputStreamAddress,
    val stderr: OutputStreamAddress,
) {
    init {
        require(stdout.runId == stderr.runId && stdout.operationId == stderr.operationId) {
            "the two addresses must belong to the same operation: $stdout and $stderr"
        }
    }

    /** Both streams, in a fixed order so a caller never has to invent one. */
    val all: List<OutputStreamAddress> get() = listOf(stdout, stderr)

    /** [all] narrowed to the channels in [channels], preserving the fixed order. */
    fun select(channels: Set<OutputChannel>): List<OutputStreamAddress> =
        all.filter { it.channel in channels }

    companion object {
        /** The pair of streams one operation writes, within [runId]. */
        fun of(runId: String, operationId: String): OperationOutputStreams =
            OperationOutputStreams(
                stdout = OutputStreamAddress.of(runId, operationId, OutputChannel.STDOUT),
                stderr = OutputStreamAddress.of(runId, operationId, OutputChannel.STDERR),
            )
    }
}