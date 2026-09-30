package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S2-A — the `when` predicate wire format.
 *
 * This file is the PURE half of S2-A: a sealed predicate ADT, its total
 * encoder, and its total decoder. The bundled directive's NAME and its
 * [DirectiveDefinition] live in `pipeline-application` beside the other
 * `core.*` definitions, because the S1 kernel fitness rule rejects namespaced
 * literals here — a concrete name is a composition fact, not part of the seam.
 * What stays here is exactly what the kernel is allowed to own: the structural
 * vocabulary a gate is written in.
 *
 * The decoder is deliberately strict. A gate that is silently discarded is a
 * fail-OPEN defect, so every unparseable input is a typed
 * [DirectiveDecodeResult.Malformed] rather than a dropped or partial predicate.
 *
 * ## The encoding
 *
 * Arguments are encoded as a STRUCTURAL, single-line, self-delimiting format
 * rather than a readable expression language.
 *
 * This was a real defect, not a stylistic choice. The first version serialized a
 * compound predicate to a readable multi-line block and re-parsed it with a
 * regex, which silently failed on a nested combinator: the DSL encoded
 * `all { ... any { ... } ... }`, the decoder returned `Malformed`, and the gate
 * was discarded. A test caught it (S2A-DSL-005), but a format that can
 * silently lose a predicate is not acceptable for a gate, where a discarded
 * predicate means fail-OPEN.
 *
 * So the encoding is prefix notation with LENGTH-PREFIXED strings:
 *
 * ```
 *   T                          always true
 *   F                          always false
 *   E <len>:<name> <len>:<val> exact match on a resolved variable
 *   P <len>:<name>             variable set and non-empty
 *   A <n> <child>...            conjunction of n children
 *   O <n> <child>...            disjunction of n children
 *   N <child>                   negation
 * ```
 *
 * Properties this buys, all of them structural rather than hoped-for:
 *   - ONE line, so no multi-line bookkeeping and no brace matching;
 *   - EXPLICIT arity, so a truncated payload is a typed Malformed, never a
 *     partially built predicate;
 *   - LENGTH-PREFIXED strings, so any character is legal inside a name or a
 *     value (spaces, newlines, braces, quotes) with no escaping rules to get
 *     wrong and therefore no input that can corrupt the parse;
 *   - TOTAL, and it never throws on user input.
 *
 * Human readability was traded away deliberately: the DSL is where a human
 * reads the predicate, and a dropped gate is far worse than a terse one.
 */

/**
 * Inverse of [WhenPredicateCodec.decode].
 *
 * The DSL needs it because the builder must produce the SAME text the runtime
 * will read back. A divergent pair would let a predicate change meaning
 * between authoring and execution, which is the same silent-lie class the ADT
 * was introduced to remove. Round-tripping is covered by a test over every
 * predicate case.
 */
object WhenPredicateEncoder {

    fun encode(predicate: WhenPredicate): String = when (predicate) {
        WhenPredicate.AlwaysTrue -> "T"
        WhenPredicate.AlwaysFalse -> "F"
        is WhenPredicate.VariableEquals -> "E ${lengthPrefixed(predicate.name)} ${lengthPrefixed(predicate.expected)}"
        is WhenPredicate.VariablePresent -> "P ${lengthPrefixed(predicate.name)}"
        is WhenPredicate.AllOf -> "A ${predicate.children.size} " +
            predicate.children.joinToString(" ") { encode(it) }
        is WhenPredicate.AnyOf -> "O ${predicate.children.size} " +
            predicate.children.joinToString(" ") { encode(it) }
        is WhenPredicate.Not -> "N ${encode(predicate.child)}"
    }

    /**
     * `<charCount>:<text>`.
     *
     * The count is CHARACTERS, not UTF-8 bytes: the decoder counts characters
     * with the same rule, so the two can never disagree about where a value
     * ends. A length prefix makes every character legal inside a value, so
     * there is no escape sequence to get wrong.
     */
    private fun lengthPrefixed(text: String): String = "${text.length}:$text"
}

/**
 * The `when` gate argument codec — the exact inverse of [WhenPredicateEncoder].
 *
 * Pure and total: it either yields a [WhenPredicate] or a typed
 * [DirectiveDecodeResult.Malformed] naming what it could not read. It never
 * throws on user input and never returns a partially built predicate, because a
 * half-built gate would be evaluated as if it were whole.
 *
 * Implemented as a real cursor-based reader rather than a regex, so nesting is
 * consumed by STRUCTURE and a truncated or surplus payload is a typed failure
 * instead of a silent misread.
 */
object WhenPredicateCodec {

    fun decode(encodedArguments: String): DirectiveDecodeResult<WhenPredicate> {
        if (encodedArguments.isBlank()) {
            return DirectiveDecodeResult.Malformed(
                "the when gate received no predicate; expected an encoded predicate such as 'T', " +
                    "'E 6:TARGET 4:prod' or 'A 2 ...'",
            )
        }
        val reader = Cursor(encodedArguments)
        val decoded = reader.readNode(origin = "<root>")
        if (decoded !is DirectiveDecodeResult.Decoded) return decoded
        reader.skipSpaces()
        if (!reader.exhausted()) {
            return DirectiveDecodeResult.Malformed(
                "the when gate has ${reader.remaining()} unexpected trailing token(s) after a " +
                    "complete " +
                    "predicate; the argument must encode exactly one predicate",
            )
        }
        return decoded
    }

    /** A cursor over the encoded string. The only place that touches the wire format. */
    private class Cursor(val source: String) {
        var at: Int = 0

        fun exhausted(): Boolean = at >= source.length

        fun remaining(): Int = source.length - at

        fun skipSpaces() {
            while (at < source.length && source[at] == ' ') at++
        }

        fun peek(): String? {
            skipSpaces()
            return if (at < source.length) source[at].toString() else null
        }

        /** Reads the next space-delimited token verbatim. */
        fun nextToken(): String? {
            skipSpaces()
            if (at >= source.length) return null
            val start = at
            while (at < source.length && source[at] != ' ') at++
            return source.substring(start, at)
        }

        fun malformed(reason: String): DirectiveDecodeResult.Malformed =
            DirectiveDecodeResult.Malformed("the when gate: $reason (at offset $at)")

        /**
         * Reads one mandatory `<length>:<text>` token.
         *
         * The length prefix means the value is read as EXACTLY that many
         * characters from the position after the colon, NOT as a
         * space-delimited token. This distinction is load-bearing: a branch name
         * like `feature/my branch` contains a space, and stopping at the space
         * would truncate it to `feature/my` — a silently different value that
         * then compares unequal and flips the gate. The prefix is what makes
         * every character, including spaces, unambiguous.
         *
         * Returns a PAIRED value: the text, or the reason it is absent, so a
         * caller cannot obtain a string while silently dropping the reason it
         * was missing. Modelled as a local ADT rather than a nullable string
         * so the "absent" case carries its own reason instead of collapsing to
         * null.
         */
        fun readString(): ReadString {
            skipSpaces()
            // Read the numeric length prefix up to the colon.
            val colon = source.indexOf(':', at)
            if (colon < 0) {
                at = source.length
                return ReadString.Absent("expected '<length>:<text>' but no ':' was found")
            }
            val declared = source.substring(at, colon)
            val length = declared.toIntOrNull()
                ?: run {
                    at = source.length
                    return ReadString.Absent("'$declared' is not a valid length")
                }
            if (length < 0) {
                at = source.length
                return ReadString.Absent("negative length $length")
            }
            val start = colon + 1
            val end = start + length
            if (end > source.length) {
                at = source.length
                return ReadString.Absent(
                    "declared length $length but only ${source.length - start} character(s) remain"
                )
            }
            at = end
            return ReadString.Present(source.substring(start, end))
        }

        /** Paired result of a length-prefixed read. */
        private sealed interface ReadString {
            data class Present(val text: String) : ReadString
            data class Absent(val reason: String) : ReadString
        }

        fun readNode(origin: String): DirectiveDecodeResult<WhenPredicate> =
            when (val kind = peek()) {
                null -> malformed("expected a predicate but the argument ended")
                "T" -> { at++; DirectiveDecodeResult.Decoded(WhenPredicate.AlwaysTrue) }
                "F" -> { at++; DirectiveDecodeResult.Decoded(WhenPredicate.AlwaysFalse) }
                "E" -> { at++; readVariableEquals(origin) }
                "P" -> { at++; readVariablePresent(origin) }
                "A" -> { at++; readCompound(origin, isConjunction = true) }
                "O" -> { at++; readCompound(origin, isConjunction = false) }
                "N" -> { at++; readNegation(origin) }
                else -> malformed(
                    "unrecognised predicate tag '$kind' in $origin; expected one of T, F, E, P, A, O, N"
                )
            }

        private fun readVariableEquals(origin: String): DirectiveDecodeResult<WhenPredicate> {
            val name = when (val read = readString()) {
                is ReadString.Absent -> return malformed(read.reason)
                is ReadString.Present -> read.text
            }
            val expected = when (val read = readString()) {
                is ReadString.Absent -> return malformed(read.reason)
                is ReadString.Present -> read.text
            }
            if (name.isEmpty()) return malformed("env in $origin requires a variable name")
            return DirectiveDecodeResult.Decoded(WhenPredicate.VariableEquals(name, expected))
        }

        private fun readVariablePresent(origin: String): DirectiveDecodeResult<WhenPredicate> {
            val name = when (val read = readString()) {
                is ReadString.Absent -> return malformed(read.reason)
                is ReadString.Present -> read.text
            }
            if (name.isEmpty()) return malformed("env in $origin requires a variable name")
            return DirectiveDecodeResult.Decoded(WhenPredicate.VariablePresent(name))
        }

        private fun readCompound(
            origin: String,
            isConjunction: Boolean,
        ): DirectiveDecodeResult<WhenPredicate> {
            val arityToken = nextToken()
                ?: return malformed("expected a child count after '${if (isConjunction) "A" else "O"}' in $origin")
            val arity = arityToken.toIntOrNull()
                ?: return malformed("'$arityToken' is not a valid child count in $origin")
            if (arity < 0) return malformed("negative child count $arity in $origin")
            val children = ArrayList<WhenPredicate>(arity)
            repeat(arity) { index ->
                when (val child = readNode("$origin[${index + 1}/$arity]")) {
                    is DirectiveDecodeResult.Decoded -> children += child.input
                    is DirectiveDecodeResult.Malformed -> return child
                }
            }
            // An empty conjunction is vacuously true and an empty disjunction
            // vacuously false; both are representable and decided by the
            // evaluator, never guessed here.
            return DirectiveDecodeResult.Decoded(
                if (isConjunction) WhenPredicate.AllOf(children) else WhenPredicate.AnyOf(children)
            )
        }

        private fun readNegation(origin: String): DirectiveDecodeResult<WhenPredicate> {
            val child = readNode("$origin.inner")
            if (child !is DirectiveDecodeResult.Decoded) return child
            return DirectiveDecodeResult.Decoded(WhenPredicate.Not(child.input))
        }
    }
}
