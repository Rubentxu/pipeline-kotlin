package dev.rubentxu.pipeline.v2.domain.identity

/**
 * The ONLY authority for constructing [ResourceRef]s (law: typed
 * construction, no ad-hoc concatenation in production code).
 * Deterministic: builders are pure functions of their arguments. Branch names,
 * completion order, timestamps and occurrence UUIDs are never inputs.
 * Segment convention (hierarchical, parent-first):
 * ```
 * pipeline:  <ns=default, pipeline/<definitionId>>
 * run:       <ns=default, run/<runId>>
 * stage:     <ns=default, run/<runId>/stage/<stageIndex>>
 * step:      <ns=default, run/<runId>/stage/<stageIndex>/step/<stepIndex>>
 * operation: <ns=default, run/<runId>/stage/<stageIndex>/step/<stepIndex>/op/<opKey>>
 * ```
 * `runId` and `opKey` are the local durable authorities (RunId / OpId); here they
 * appear only as identity SEGMENTS of the projection.
*/
object ResourceRefs {
    const val NAMESPACE: String = "pipeline"

    fun pipeline(definitionId: String): ResourceRef =
        ResourceRef(ResourceKind.PIPELINE_DEFINITION, listOf(NAMESPACE, "pipeline", definitionId))

    fun run(runId: String): ResourceRef =
        ResourceRef(ResourceKind.RUN, listOf(NAMESPACE, "run", runId))

    fun stage(runId: String, stageIndex: Int): ResourceRef =
        ResourceRef(
            ResourceKind.STAGE,
            listOf(NAMESPACE, "run", runId, "stage", stageIndex.toString()),
        )

    fun step(runId: String, stageIndex: Int, stepIndex: Int): ResourceRef =
        ResourceRef(
            ResourceKind.STEP,
            listOf(NAMESPACE, "run", runId, "stage", stageIndex.toString(), "step", stepIndex.toString()),
        )

    fun operation(runId: String, stageIndex: Int, stepIndex: Int, opKey: String): ResourceRef =
        ResourceRef(
            ResourceKind.OPERATION,
            listOf(
                NAMESPACE, "run", runId,
                "stage", stageIndex.toString(),
                "step", stepIndex.toString(),
                "op", opKey,
            ),
        )

    /**
     * The identity SEGMENT an operation takes in a reference, derived from the operation's
     * canonical text.
     *
     * The durable OpId of a branch step names the branch frames it runs in, and those frames
     * are written with colons — `run-1-s0-0-bp2-b1:branch-0:core.sh`. A reference segment may
     * not carry a colon: the colon is the separator of the canonical text itself, and a
     * segment that carried one could not be read back. The identity is not trimmed to fit —
     * a subject that carries a piece of an operation is a subject of no operation — so the
     * whole text is encoded, character by character, with a scheme that is INJECTIVE and
     * reversible:
     *
     * - `x` encodes as `xx`, so the escape marker can never be confused with a literal;
     * - every character outside `a-z A-Z 0-9 . _ -` encodes as `x` followed by its FOUR-digit
     *   hexadecimal code — `:` becomes `x003a`, a euro sign `x20ac`. Four digits cover the
     *   whole UTF-16 range a `Char` can carry (a character outside the BMP travels as its
     *   surrogate pair, and each surrogate is encoded whole), so the segment is reversible
     *   for ANY text, not only the ASCII ones.
     *
     * Deterministic, and total: a text that is already a valid segment encodes to itself.
     * A consumer that reads an `op/` segment can recover the operation's canonical text with
     * [decodeOperationKey], so the subject of an event and the `opId` the event publishes
     * name the same operation with no second authority between them.
     */
    fun operationKey(opIdText: String): String = buildString {
        for (ch in opIdText) {
            when {
                ch == 'x' -> append("xx")
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-' ->
                    append(ch)
                else -> append("x%04x".format(ch.code))
            }
        }
    }

    /**
     * Reads back what [operationKey] encoded: the operation's canonical text.
     *
     * The inverse exists so that the encoding stays an identity projection and not a one-way
     * hash: a consumer that joins an event's `opId` with the `op/` segment of a subject does
     * it through this pair of functions, both owned by the same authority. A text that was
     * not produced by [operationKey] yields `null` rather than a guess.
     */
    fun decodeOperationKey(key: String): String? {
        val out = StringBuilder(key.length)
        var i = 0
        while (i < key.length) {
            val ch = key[i]
            when {
                ch == 'x' -> {
                    if (i + 1 >= key.length) return null
                    val next = key[i + 1]
                    if (next == 'x') {
                        out.append('x')
                        i += 2
                    } else {
                        if (i + 4 >= key.length) return null
                        val code = key.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 5
                    }
                }
                else -> {
                    out.append(ch)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    // Plugin provider builders

    /**
     * Logical plugin identity (PLUGIN_IDENTITY_MODEL"ResourceRef").
     * Segment convention: `<ns, plugin, <identity>>` (single-segment identity).
     * Use this for the logical identity of a provider; for an immutable
     * artifact, use [pluginRelease].
*/
    fun plugin(namespace: String, identity: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN,
            listOf(namespace, "plugin", identity),
        )

    /**
     * Immutable plugin release identity.
     * Segment convention: `<ns, plugin, <identity>, release, <version>>`.
     * The release digest lives in `PluginReleaseRef`, not in segments
     * (digests are too long and contain forbidden characters).
*/
    fun pluginRelease(namespace: String, identity: String, version: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN_RELEASE,
            listOf(namespace, "plugin", identity, "release", version),
        )

    /**
     * One Step family's addressable identity under a plugin release.
     * Segment convention: `<ns, plugin, <identity>, step, <stepKey>>`.
*/
    fun stepDefinition(namespace: String, identity: String, stepKey: String): ResourceRef =
        ResourceRef(
            ResourceKind.STEP_DEFINITION,
            listOf(namespace, "plugin", identity, "step", stepKey),
        )

    /**
     * A functional family under a plugin publisher.
     * Segment convention: `<ns, plugin-family, <family>>`.
*/
    fun pluginFamily(namespace: String, family: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN_FAMILY,
            listOf(namespace, "plugin-family", family),
        )
}
