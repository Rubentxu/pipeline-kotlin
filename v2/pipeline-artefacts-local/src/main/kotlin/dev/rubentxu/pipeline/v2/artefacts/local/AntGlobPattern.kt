package dev.rubentxu.pipeline.v2.artefacts.local

import org.springframework.util.AntPathMatcher
import java.util.regex.PatternSyntaxException

/**
 * One Ant glob pattern, compiled into the dialect the engine actually speaks.
 *
 * WHY THIS EXISTS. [AntStyleGlob] promised Ant semantics and ran Spring's dialect, and the two
 * disagree on exactly the two constructs an Ant user writes most:
 *
 * - `build/**/*.{jar,war}` — Ant alternation means "a jar OR a war". Spring reads `{...}`
 *   without a colon as a wildcard, so the pattern matched EVERYTHING under the directory. An
 *   operator who wrote that pattern to archive two extensions got every file, including the ones
 *   sitting next to them. Measured, not assumed: `build/**/*.{jar,war}` matched
 *   `build/secret.pem`.
 * - `[a-c].txt` — Ant means "a, b or c". Spring reads the brackets as literal text, so the
 *   pattern matched a file literally named `[a-c].txt` and nothing else. An operator who wrote a
 *   character range got an empty archive, which `allowEmptyArchive` reports as a success.
 *
 * So the pattern is TRANSLATED, not reinterpreted: Ant is the contract, Spring is the engine, and
 * the translation is where the two are reconciled. Translating beats rewriting a matcher — the
 * engine keeps doing the hard work (`**`, `?`, path separators, case sensitivity) and only the
 * two constructs that disagree are touched.
 *
 * THE TRANSLATION. Ant alternation becomes Spring's `{name:regex}` form, with the alternatives
 * quoted as literals and parenthesised so the alternation cannot escape the group:
 *
 *     {jar,war}  ->  {__ant0:(\Qjar\E|\Qwar\E)}
 *
 * The quoting is what makes the alternatives LITERAL: a `*` inside an alternative is a star in the
 * filename, not a wildcard. Without the quoting the group would hold a regex, and `*.txt` would
 * not even compile.
 *
 * The parentheses make the alternation explicit in the pattern instead of leaving it to how the
 * engine nests group content. Measured on Spring 6.2.19: the engine already nests it, so the
 * parentheses are belt-and-braces rather than the load-bearing part -- they cost nothing and they
 * make the translated pattern say what it means to anyone reading it.
 *
 * Ant character classes become the same Spring form with a regex class inside:
 *
 *     [a-c].txt  ->  {__ant0:([a-c])}
 *
 * `{name:regex}` is left VERBATIM. That form is not Ant — it is Spring's own dialect, and it is
 * the escape hatch for the cases Ant cannot express. Leaving it verbatim keeps the patterns that
 * already relied on it working, and it is the only place where a pattern is NOT Ant.
 *
 * ONE AMBIGUITY, STATED RATHER THAN HIDDEN. A brace group that contains a colon is read as
 * Spring's `{name:regex}`, because that is how the engine splits it. So the Ant alternation of
 * literals that themselves contain a colon (`{a:b}`) is not expressible in this dialect: it is
 * read as the regex `b`. The alternative rule -- "a group with a comma is always an alternation" --
 * was rejected because it breaks every regex that contains a comma, and a comma inside a regex
 * (`{1,3}`) is far more common than a colon inside a filename.
 *
 * PURE BY CONSTRUCTION. No filesystem, no clock, no I/O: this is the seam AGENTS.md section 2
 * allows a unit test to touch. The file WALK stays in [AntStyleGlob.match], where it belongs.
 */
internal class AntGlobPattern(source: String) {

    /** The pattern as the operator wrote it. Never rewritten: it is what error messages quote. */
    val source: String = source

    /** The same pattern expressed in the dialect the engine speaks. */
    val translated: String = translate(source)

    /**
     * True when [path] — a workspace-relative path — is matched by this pattern.
     *
     * @param path the path to test, relative to the root the glob walks
     */
    fun matches(path: String): Boolean = MATCHER.match(translated, path)

    private companion object {

        /**
         * One matcher for the whole process.
         *
         * Its two caches are `ConcurrentHashMap`s and it holds no other mutable state, so sharing
         * it is safe; measured under 8 threads x 20k matches with no inconsistent verdict. The
         * previous owner built one matcher per glob instance, which is a cache per glob for no
         * benefit.
         */
        val MATCHER = AntPathMatcher()

        /**
         * Translates one Ant pattern into the dialect [MATCHER] understands.
         *
         * Every branch either rewrites a construct the two dialects disagree on, or copies the
         * text through untouched. It never throws: a pattern that cannot be expressed is kept
         * literal rather than turned into an exception at match time, because a glob that refuses
         * to run is a worse answer than one that matches exactly what it says.
         */
        fun translate(source: String): String {
            val out = StringBuilder(source.length + 16)
            var index = 0
            var group = 0
            while (index < source.length) {
                val c = source[index]
                when {
                    c == '{' -> {
                        val close = closingBrace(source, index)
                        if (close < 0) {
                            out.append(c)
                            index++
                        } else {
                            val body = source.substring(index + 1, close)
                            // A colon means Spring's own `{name:regex}` dialect, which Ant does
                            // not have. It is the documented escape hatch and stays verbatim.
                            val springDialect = ':' in body
                            out.append(
                                if (springDialect) {
                                    source.substring(index, close + 1)
                                } else {
                                    "{__ant$group:(${alternation(body)})}"
                                },
                            )
                            if (!springDialect) group++
                            index = close + 1
                        }
                    }

                    c == '[' -> {
                        val close = closingBracket(source, index)
                        if (close < 0) {
                            out.append(c)
                            index++
                        } else {
                            out.append("{__ant$group:(${characterClass(source.substring(index + 1, close))})}")
                            group++
                            index = close + 1
                        }
                    }

                    else -> {
                        out.append(c)
                        index++
                    }
                }
            }
            return out.toString()
        }

        /**
         * The `}` that closes the `{` at [open], or -1 when there is none.
         *
         * Nested braces are counted so that a group inside a group closes at the right place.
         */
        fun closingBrace(source: String, open: Int): Int {
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        /**
         * The `]` that closes the `[` at [open], or -1 when there is none.
         *
         * A `]` immediately after `[` is a literal member of the class, not the end of it.
         */
        fun closingBracket(source: String, open: Int): Int {
            var from = open + 1
            if (from < source.length && source[from] == ']') from++
            return source.indexOf(']', from)
        }

        /**
         * The alternatives of an Ant brace group, as a parenthesised alternation of literals.
         *
         * Only top-level commas separate: a comma inside a nested group belongs to that group.
         */
        fun alternation(body: String): String {
            val alternatives = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in body) {
                when {
                    c == '{' -> {
                        depth++
                        current.append(c)
                    }

                    c == '}' -> {
                        depth--
                        current.append(c)
                    }

                    c == ',' && depth == 0 -> {
                        alternatives += current.toString()
                        current.setLength(0)
                    }

                    else -> current.append(c)
                }
            }
            alternatives += current.toString()
            return alternatives.joinToString("|") { quoteLiteral(it) }
        }

        /**
         * An Ant character class, as a regex character class.
         *
         * Ant treats `!` as negation where a regex uses `^`, and treats `\`, `]` and `^` as
         * ordinary members where a regex gives them meaning. Those three are escaped so the class
         * means what Ant says it means; `-` is left alone because Ant ranges are regex ranges.
         */
        fun characterClass(body: String): String {
            val negated = body.startsWith("!")
            val members = if (negated) body.substring(1) else body
            val escaped = StringBuilder()
            for (c in members) {
                when (c) {
                    '\\' -> escaped.append("\\\\")
                    ']' -> escaped.append("\\]")
                    '^' -> escaped.append("\\^")
                    else -> escaped.append(c)
                }
            }
            val cls = if (negated && escaped.isNotEmpty()) "[^$escaped]" else "[$escaped]"
            // A class that is not a valid regex is kept literal: the old behaviour matched a file
            // literally named `[...]`, and that is a better answer than throwing at match time.
            return if (isValidClass(cls)) cls else quoteLiteral("[$body]")
        }

        /** True when [cls] compiles as a regex character class. */
        fun isValidClass(cls: String): Boolean =
            try {
                Regex(cls)
                true
            } catch (e: PatternSyntaxException) {
                false
            }

        /**
         * A literal, quoted for a regex.
         *
         * `\Q...\E` quotes everything between the markers, so an alternative is matched as the
         * exact text it was written as. A literal `\E` inside the text would close the quote
         * early, so it is written as an escaped backslash between two quote sections.
         */
        fun quoteLiteral(text: String): String =
            "\\Q" + text.replace("\\E", "\\E\\\\E\\Q") + "\\E"
    }
}
