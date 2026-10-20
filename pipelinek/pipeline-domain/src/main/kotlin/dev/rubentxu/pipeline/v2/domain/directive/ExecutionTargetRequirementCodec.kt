package dev.rubentxu.pipeline.v2.domain.directive

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * S3.1 — the `agent` requirement wire format.
 *
 * PURE half of S3.1: a total encoder and a total decoder over
 * [ExecutionTargetRequirement]. Same discipline as [WhenPredicateCodec], for
 * the same reason — a malformed payload must be a typed failure, never a
 * silently dropped constraint, because a dropped constraint on an execution
 * target means the stage runs somewhere the author did not ask for.
 *
 * ## The encoding
 *
 * Single line, prefix notation, length-prefixed strings, explicit arity:
 *
 * ```
 *   A                                  LocalAny
 *   L <n> <len>:<label>...              LocalLabels, n labels
 *   C <n> <len>:<capability>...         CapabilitySet, n capability keys
 *   R <len>:<selector>                  Remote
 * ```
 *
 * The properties this buys are structural, not hoped for:
 *
 *  - **One line.** No brace matching, no multi-line bookkeeping.
 *  - **Explicit arity.** A truncated payload is [DirectiveDecodeResult.Malformed],
 *    never a partially built requirement that happens to look valid.
 *  - **Length-prefixed strings.** Any character is legal inside a label or a
 *    capability key — spaces, dots, dashes, colons — with no escaping rules to
 *    get wrong, and therefore no input that can corrupt the parse. A capability
 *    key like `http.transport` and a label like `linux && docker` both survive
 *    a round trip untouched.
 *  - **Total, and it never throws on user input.** Every failure is a value.
 *
 *    That sentence was an aspiration until S3-R1, and three payload-derived
 *    numbers broke it. Each is now refused before it can act, and the reasons
 *    are recorded here rather than only in the test names:
 *
 *    ```
 *    a declared arity of 0
 *        -> LocalLabels/CapabilitySet would violate their own non-empty
 *           invariant; refused as Malformed instead of built and caught
 *
 *    a declared arity larger than the remaining bytes
 *        -> refused by ByteCursor.canSatisfyArity BEFORE any ArrayList is sized,
 *           because "L 2147483647 " is five characters and used to ask the JVM
 *           for room for ~2^31 references
 *
 *    a declared string length near Int.MAX_VALUE
 *        -> the bounds check subtracts rather than adds, so it cannot wrap
 *    ```
 *
 *    The claim is falsifiable rather than aspirational: `S3R1AgentCodecTotalityTest`
 *    holds a hostile-payload sweep plus a counter-test, and the engine guard in
 *    `BeforeStageDirectiveEngine` is a second, independent line of defence for
 *    the day a future case reintroduces a throwable.
 *
 * Human readability was traded away deliberately, for the same reason
 * [WhenPredicateCodec] did: the DSL is where a human reads the declaration, and
 * a silently weakened constraint is far worse than a terse one.
 */
object ExecutionTargetRequirementCodec {

    private const val LOCAL_ANY = "A"
    private const val LOCAL_LABELS = "L"
    private const val CAPABILITY_SET = "C"
    private const val REMOTE = "R"

    /**
     * The smallest arity a constrained requirement may declare.
     *
     * One, not zero, and the reason is semantic rather than syntactic: an empty
     * label set or capability set means "no constraint", which is what
     * [ExecutionTargetRequirement.LocalAny] already says. Allowing `L 0` would
     * give one meaning two encodings, and the two encodings decode to different
     * requirements, so a replay comparison could report drift where the program
     * is identical - or the reverse.
     */
    private const val MIN_ARITY = 1

    /**
     * Encode a requirement. TOTAL: every case of the sealed hierarchy produces
     * a decodable string, which is what lets the `when` be exhaustive rather
     * than carrying a fallback branch that could hide a future case.
     */
    fun encode(requirement: ExecutionTargetRequirement): String = when (requirement) {
        is ExecutionTargetRequirement.LocalAny -> LOCAL_ANY
        is ExecutionTargetRequirement.LocalLabels -> buildList {
            val ordered = requirement.labels.sortedBy { it.value }
            add(LOCAL_LABELS)
            add(ordered.size.toString())
            ordered.forEach { add(lengthPrefixed(it.value)) }
        }.joinToString(" ")

        is ExecutionTargetRequirement.CapabilitySet -> buildList {
            val ordered = requirement.required.sortedBy { it.key }
            add(CAPABILITY_SET)
            add(ordered.size.toString())
            ordered.forEach { add(lengthPrefixed(it.key)) }
        }.joinToString(" ")

        is ExecutionTargetRequirement.Remote ->
            "$REMOTE ${lengthPrefixed(requirement.selector.value)}"
    }

    /**
     * Decode a requirement. TOTAL: returns [DirectiveDecodeResult.Malformed]
     * for every malformed input and never throws, so a corrupt payload is a
     * value the engine can deny on rather than an exception crossing the seam.
     *
     * Total here means TOTAL on the encoded argument, and it is a statement
     * about behaviour under hostile input rather than about the happy path. The
     * failures are validated at the boundary and returned as values; this
     * function does not catch exceptions, because a decoder that had to catch
     * its own preconditions would be evidence that it was not validating them.
     */
    fun decode(encoded: String): DirectiveDecodeResult<ExecutionTargetRequirement> {
        // A cursor over BYTES, not over whitespace-separated tokens.
        //
        // The first version of this decoder split on spaces and THEN applied
        // the length prefix, which quietly defeated the point of length
        // prefixing: a label like "linux && docker" encodes to
        // "13:linux && docker", the split tore it into "13:linux", "&&" and
        // "docker", and the label came back malformed. Length prefixing exists
        // precisely so content may contain spaces, so the reader must advance
        // by the declared length and never by searching for a delimiter.
        // Working in bytes also keeps the declared unit and the unit the
        // replay comparator uses identical.
        val cursor = ByteCursor(encoded)
        return when (val tag = cursor.readTag()) {
            null -> malformed("empty encoding")
            LOCAL_ANY ->
                cursor.closed("a complete requirement", ExecutionTargetRequirement.LocalAny)

            LOCAL_LABELS -> readLabels(cursor)
            CAPABILITY_SET -> readCapabilities(cursor)
            REMOTE -> readRemote(cursor)
            else -> malformed("unknown requirement tag '$tag'")
        }
    }

    // ------------------------------------------------------------------
    // Per-case readers
    // ------------------------------------------------------------------

    private fun readLabels(cursor: ByteCursor): DirectiveDecodeResult<ExecutionTargetRequirement> {
        val count = cursor.readCount() ?: return malformed("LocalLabels arity missing")
        // A zero arity is refused HERE, as a value, rather than being built and
        // caught later. `LocalLabels` requires a non-empty set, and an empty
        // label set is indistinguishable from `LocalAny`, so the payload is
        // lying about which case it means - exactly what Malformed is for.
        if (count < MIN_ARITY) return malformed("LocalLabels arity must be at least 1; use LocalAny for an unconstrained target")
        if (!cursor.canSatisfyArity(count)) return malformed("LocalLabels declares $count labels but the payload is too short to hold them")
        val labels = cursor.readStrings(count) ?: return malformed("LocalLabels payload truncated")
        if (labels.any { it.isBlank() }) return malformed("LocalLabels carries a blank label")
        return cursor.closed(
            "a label set",
            ExecutionTargetRequirement.LocalLabels(labels.map(::AgentLabel).toSet()),
        )
    }

    private fun readCapabilities(cursor: ByteCursor): DirectiveDecodeResult<ExecutionTargetRequirement> {
        val count = cursor.readCount() ?: return malformed("CapabilitySet arity missing")
        if (count < MIN_ARITY) return malformed("CapabilitySet arity must be at least 1; use LocalAny for an unconstrained target")
        if (!cursor.canSatisfyArity(count)) return malformed("CapabilitySet declares $count capabilities but the payload is too short to hold them")
        val keys = cursor.readStrings(count) ?: return malformed("CapabilitySet payload truncated")
        if (keys.any { it.isBlank() }) return malformed("CapabilitySet carries a blank capability key")
        return cursor.closed(
            "a capability set",
            ExecutionTargetRequirement.CapabilitySet(keys.map(::StepCapability).toSet()),
        )
    }

    private fun readRemote(cursor: ByteCursor): DirectiveDecodeResult<ExecutionTargetRequirement> {
        val selector = cursor.readString() ?: return malformed("Remote selector missing")
        if (selector.isBlank()) return malformed("Remote selector is blank")
        return cursor.closed(
            "a remote selector",
            ExecutionTargetRequirement.Remote(RemoteSelector(selector)),
        )
    }

    // ------------------------------------------------------------------
    // Encoding primitives
    // ------------------------------------------------------------------

    /**
     * A string is encoded as `<byteLength>:<content>`.
     *
     * Byte length rather than character count on purpose: the payload crosses a
     * JVM boundary and is compared byte-for-byte during replay, so the length
     * has to be the same unit the comparator uses or a multi-byte label shifts
     * every following offset.
     */
    private fun lengthPrefixed(value: String): String =
        "${value.toByteArray(Charsets.UTF_8).size}:$value"

    /**
     * Forward-only cursor over the encoded bytes.
     *
     * Every string read is bounded by a length the encoding itself declared, so
     * there is no scan for a delimiter that content could contain. A
     * mis-declared length is a typed failure, never a silently shortened string.
     */
    private class ByteCursor(private val source: String) {
        private val bytes = source.toByteArray(Charsets.UTF_8)
        private var index = 0

        fun atEnd(): Boolean = index >= bytes.size

        /** Bytes not yet consumed. Never negative, so it is safe to subtract. */
        fun remaining(): Int = bytes.size - index

        private fun peek(): Int? = bytes.getOrNull(index)?.toInt()?.and(0xFF)

        /**
         * Can this payload POSSIBLY hold [count] further entries?
         *
         * Every entry costs at least one byte, so a count larger than what is
         * left is a claim the payload has already disproved. Checking that
         * before allocating is what keeps a declared arity from driving a
         * reservation: `L 2147483647 ` is five characters and used to ask the
         * JVM for room for ~2^31 references.
         *
         * Necessary, not sufficient: passing this does not mean the entries are
         * well formed, it only means the bytes to hold them plausibly exist. The
         * readers still verify each one, and a count that survives this check is
         * bounded by the size of the input rather than by a claim inside it.
         */
        fun canSatisfyArity(count: Int): Boolean = count >= 0 && count <= remaining()

        /**
         * Close the parse: nothing may follow a complete requirement.
         *
         * Trailing bytes mean the payload is not what it claims to be, and
         * decoding it anyway would let two different encodings resolve to the
         * same requirement - the kind of silent equivalence that makes a replay
         * comparison report no drift when the program actually changed.
         */
        fun closed(
            what: String,
            value: ExecutionTargetRequirement,
        ): DirectiveDecodeResult<ExecutionTargetRequirement> = if (atEnd()) {
            DirectiveDecodeResult.Decoded(value)
        } else {
            malformed("trailing bytes after $what")
        }

        /** A tag is one or more ASCII letters, then a space or the end. */
        fun readTag(): String? {
            if (atEnd()) return null
            val start = index
            while (!atEnd() && peek()!!.isAsciiLetter()) index++
            if (index == start) return null
            val tag = String(bytes, start, index - start, Charsets.UTF_8)
            if (!atEnd() && !consumeSpace()) return null
            return tag
        }

        /** A run of decimal digits, then a space or the end. */
        fun readCount(): Int? {
            if (atEnd()) return null
            val start = index
            while (!atEnd() && peek()!!.isAsciiDigit()) index++
            if (index == start) return null
            val digits = String(bytes, start, index - start, Charsets.UTF_8)
            val parsed = digits.toIntOrNull() ?: return null
            if (parsed < 0) return null
            if (!atEnd() && !consumeSpace()) return null
            return parsed
        }

        /**
         * `<decimal>:<exactly that many bytes>`.
         *
         * The declared length is authoritative: the reader takes exactly those
         * bytes, so content may contain spaces, colons and anything else. An
         * under- or over-declared length fails here rather than being repaired.
         */
        fun readString(): String? {
            // Entries are space-separated, but CONTENT may itself contain
            // spaces, so the separator is consumed here rather than by
            // splitting. The space that follows [readCount] or [readTag] has
            // already been consumed, so at most one is present.
            if (peek() == ' '.code) index++
            val digitsStart = index
            while (!atEnd() && peek()!!.isAsciiDigit()) index++
            if (index == digitsStart) return null
            val declared = String(bytes, digitsStart, index - digitsStart, Charsets.UTF_8)
                .toIntOrNull() ?: return null
            if (declared < 0) return null
            if (atEnd() || peek()!! != ':'.code) return null
            index++
            // SUBTRACT, never add. `declared` is only bounded by toIntOrNull, so
            // `index + declared` wraps negative at Int.MAX_VALUE, the bounds
            // check passes, and the cursor hands String a range that does not
            // exist. `remaining()` is non-negative by construction, so the same
            // comparison cannot overflow.
            if (declared > remaining()) return null
            val value = String(bytes, index, declared, Charsets.UTF_8)
            index += declared
            return value
        }

        /**
         * Read exactly [count] entries, or nothing at all.
         *
         * The preallocation is safe only because callers prove the arity
         * satisfiable first ([canSatisfyArity]); this function is private to the
         * cursor and must not be called with a bare payload-derived count. The
         * reservation is therefore bounded by the size of the input rather than
         * by a number the input merely claims.
         */
        fun readStrings(count: Int): List<String>? {
            val out = ArrayList<String>(count)
            repeat(count) {
                out.add(readString() ?: return null)
            }
            return out
        }

        private fun consumeSpace(): Boolean {
            if (peek() != ' '.code) return false
            index++
            return true
        }

        /**
         * Tags are emitted uppercase (`A`, `L`, `C`, `R`). The reader accepts
         * either case: being stricter than the writer would only mean a
         * hand-written payload in the other case decodes to Malformed, which
         * is fine, but accepting both keeps the rule about LETTERS rather than
         * about the specific glyphs this encoder happens to emit.
         */
        private fun Int.isAsciiLetter(): Boolean =
            this in 'a'.code..'z'.code || this in 'A'.code..'Z'.code

        private fun Int.isAsciiDigit(): Boolean = this in '0'.code..'9'.code
    }

    private fun malformed(reason: String): DirectiveDecodeResult.Malformed =
        DirectiveDecodeResult.Malformed("agent requirement: $reason")
}
