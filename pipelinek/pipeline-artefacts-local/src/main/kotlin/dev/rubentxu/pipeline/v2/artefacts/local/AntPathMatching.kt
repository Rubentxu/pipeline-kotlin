package dev.rubentxu.pipeline.v2.artefacts.local

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The Ant path matcher, implemented here.
 *
 * WHY IT LIVES HERE. This used to be Spring's `org.springframework.util.AntPathMatcher`, and the
 * project now carries a rule with no exceptions: **no external library is added for a couple of
 * classes**. A dependency is not free — it is a jar in the distribution, a transitive closure to
 * audit, a licence to keep, and a behaviour to inherit. When the needed behaviour is small,
 * well-understood and ours to own, it is written here.
 *
 * WHAT IT DOES. It matches a path against an Ant-style pattern, and the Ant semantics are the
 * ones a Jenkins user writes: `**` spans any number of path segments, `*` and `?` stay inside one
 * segment, `[...]` is a character class and `{a,b}` is an alternation.
 *
 * THE SHAPE OF THE ALGORITHM is the canonical one — the same shape as Apache Ant's
 * `SelectorUtils.matchPath`: split BOTH sides into segments, match from the front up to the
 * first `**`, match from the back up to the last `**`, and search the middle. That shape is what
 * makes `**` exact by construction: there is no regex anywhere that could swallow a separator by
 * accident, because a separator only ever lives BETWEEN segments.
 *
 * THE PATTERN IS COMPILED ONCE. The segments are compiled when the pattern is compiled, and the
 * compiled form is what is matched against every path. A matcher that recompiles its pattern per
 * path pays for the same work once per path, and the engine this replaces did not do that either
 * — it cached, behind a lock, what this code simply holds.
 *
 * WITHIN a segment the pattern is compiled to a regex, because that is where `*`, `?`, `[...]`
 * and `{...}` live. A segment without a wildcard is compared as text: there is nothing to compile,
 * and comparing two strings is cheaper than running a regex over them.
 *
 * TWO CONSTRUCTS ARE OURS, NOT ANT'S, AND THE OLD ENGINE GOT THEM WRONG:
 *
 * - `{a,b}` is an ALTERNATION of literals. The engine this replaces read a colon-free group as a
 *   wildcard, so `build/**/*.{jar,war}` matched `build/secret.pem`.
 * - `[a-c]` is a CHARACTER CLASS. The engine this replaces read the brackets as literal text, so
 *   `[a-c].txt` matched a file literally named `[a-c].txt` and nothing else.
 *
 * `{name:regex}` survives as the escape hatch for what Ant cannot express. Its syntax was
 * Spring's; the implementation is now ours, and the ambiguity it carries (a group with a colon is
 * a regex, so an alternation of literals that contain a colon is not expressible) is unchanged.
 *
 * THE RULES WORTH NAMING, because they are the ones a reader would otherwise have to guess:
 *
 * - A path that starts with `/` matches only a pattern that starts with `/`, and the other way
 *   round. Without it, a wildcard prefix would also reach an absolute path.
 * - Empty segments do not exist: `a//b` and `a/b` are the same path, and `a/` is `a`. What
 *   survives of the difference between them is the trailing separator, which has to agree
 *   between the pattern and the path.
 * - `*` never crosses a separator, because there are no separators inside a segment.
 */
internal object AntPathMatching {

    /**
     * Compiles a pattern into the form that is matched against many paths.
     *
     * @param pattern the Ant pattern, relative to the same root as the paths it is matched against
     */
    fun compile(pattern: String): CompiledPattern = CompiledPattern(pattern)

    /**
     * Where the two sides stand.
     *
     * The algorithm walks the pattern and the path from both ends at once, so the four positions
     * are carried in one place instead of in a dozen arguments.
     */
    private class Cursor(patternSize: Int, pathSize: Int) {

        var patternStart = 0
        var patternEnd = patternSize - 1
        var pathStart = 0
        var pathEnd = pathSize - 1
    }

    /**
     * A pattern, compiled once.
     *
     * It holds no mutable state and shares nothing with other instances, so the matcher needs
     * neither a cache nor a lock: the caller compiles the pattern it needs and keeps it.
     */
    class CompiledPattern internal constructor(private val pattern: String) {

        private val patternDirs: List<String> = tokenize(pattern)

        /** One compiled form per segment, in the same order as [patternDirs]. */
        private val segments: List<Segment> = patternDirs.map { Segment.compile(it) }

        /** A pattern that starts at the root only matches paths that start at the root. */
        private val patternIsAbsolute: Boolean = pattern.startsWith(SEPARATOR)

        /**
         * True when [path] is matched by this pattern.
         *
         * @param path the path to test, relative to the root
         */
        fun matches(path: String): Boolean {
            if (path.startsWith(SEPARATOR) != patternIsAbsolute) return false
            if (!isPotentialMatch(path)) return false

            val pathDirs = tokenize(path)
            val cursor = Cursor(patternDirs.size, pathDirs.size)

            if (!matchUpToTheFirstStar(cursor, pathDirs)) return false

            if (cursor.pathStart > cursor.pathEnd) {
                // The path is exhausted: only wildcards can still match, and the trailing
                // separator has to agree on both sides.
                if (cursor.patternStart > cursor.patternEnd) {
                    return pattern.endsWith(SEPARATOR) == path.endsWith(SEPARATOR)
                }
                if (isALoneStarAndATrailingSeparator(cursor, path)) return true
                return onlyWildcardsLeft(patternDirs, cursor.patternStart, cursor.patternEnd)
            }
            if (cursor.patternStart > cursor.patternEnd) {
                // The pattern is exhausted and the path is not: no match.
                return false
            }

            if (!matchUpToTheLastStar(cursor, pathDirs, path)) return false
            if (cursor.pathStart > cursor.pathEnd) {
                return onlyWildcardsLeft(patternDirs, cursor.patternStart, cursor.patternEnd)
            }
            if (!matchTheMiddle(cursor, pathDirs)) return false

            return onlyWildcardsLeft(patternDirs, cursor.patternStart, cursor.patternEnd)
        }

        /** The pattern and the path, from the front up to the first `**`. */
        private fun matchUpToTheFirstStar(cursor: Cursor, pathDirs: List<String>): Boolean {
            while (cursor.patternStart <= cursor.patternEnd && cursor.pathStart <= cursor.pathEnd) {
                if (patternDirs[cursor.patternStart] == DOUBLE_WILDCARD) break
                if (!segments[cursor.patternStart].matches(pathDirs[cursor.pathStart])) return false
                cursor.patternStart++
                cursor.pathStart++
            }
            return true
        }

        /** The pattern and the path, from the back up to the last `**`. */
        private fun matchUpToTheLastStar(cursor: Cursor, pathDirs: List<String>, path: String): Boolean {
            while (cursor.patternStart <= cursor.patternEnd && cursor.pathStart <= cursor.pathEnd) {
                if (patternDirs[cursor.patternEnd] == DOUBLE_WILDCARD) break
                if (!segments[cursor.patternEnd].matches(pathDirs[cursor.pathEnd])) return false
                if (cursor.patternEnd == patternDirs.size - 1 && pattern.endsWith(SEPARATOR) != path.endsWith(SEPARATOR)) {
                    return false
                }
                cursor.patternEnd--
                cursor.pathEnd--
            }
            return true
        }

        /** The pattern between two `**`, looked for inside the path. */
        private fun matchTheMiddle(cursor: Cursor, pathDirs: List<String>): Boolean {
            while (cursor.patternStart != cursor.patternEnd && cursor.pathStart <= cursor.pathEnd) {
                val nextStar = nextDoubleWildcard(patternDirs, cursor.patternStart, cursor.patternEnd)
                if (nextStar < 0) return false
                if (nextStar == cursor.patternStart + 1) {
                    // Two `**` in a row: one of them is redundant.
                    cursor.patternStart++
                    continue
                }
                val patternLength = nextStar - cursor.patternStart - 1
                val found = searchMiddle(
                    segments,
                    pathDirs,
                    cursor.patternStart,
                    cursor.pathStart,
                    cursor.pathEnd,
                    patternLength,
                )
                if (found < 0) return false
                cursor.patternStart = nextStar
                cursor.pathStart = found + patternLength
            }
            return true
        }

        /**
         * A `*` left as the last segment of the pattern, and a path that ends with a separator.
         *
         * That is the one case where the path can run out and still match.
         */
        private fun isALoneStarAndATrailingSeparator(cursor: Cursor, path: String): Boolean =
            cursor.patternStart == cursor.patternEnd &&
                patternDirs[cursor.patternStart] == SINGLE_WILDCARD &&
                path.endsWith(SEPARATOR)

        /**
         * True when the path cannot match this pattern, decided without splitting anything.
         *
         * A pattern whose segments are literal text can only match a path that starts with that
         * text, segment by segment. Saying that BEFORE the path is split into segments is what
         * keeps a glob over a big tree from paying for a split it does not need — and it is the
         * check the engine this replaces did, for the same reason.
         *
         * The walk stops at the first segment that is not literal text: from there on the
         * alignment between the pattern and the path is not known, and a wildcard can still
         * swallow the difference.
         */
        private fun isPotentialMatch(path: String): Boolean {
            var pos = 0
            for (i in patternDirs.indices) {
                pos += skipSeparator(path, pos)
                if (!segments[i].isLiteral) return true
                val dir = patternDirs[i]
                if (!path.startsWith(dir, pos)) return false
                pos += dir.length
            }
            return true
        }

        /** The separators at [pos], which are not a segment of their own. */
        private fun skipSeparator(path: String, pos: Int): Int {
            var skipped = 0
            while (path.startsWith(SEPARATOR, pos + skipped)) {
                skipped += SEPARATOR.length
            }
            return skipped
        }
    }

    /**
     * Splits a pattern or a path into segments, dropping the empty ones.
     *
     * An empty segment is not a thing in a path: `a//b` is `a/b`, and `a/` is `a`.
     *
     * The split is done in one pass, into one list: this runs once per path, and a path is
     * asked about every pattern, so a second list to filter the first one out is a cost paid
     * on every pair.
     */
    private fun tokenize(text: String): List<String> {
        val segments = ArrayList<String>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf(SEPARATOR, start)
            if (end < 0) {
                if (start < text.length) segments.add(text.substring(start))
                break
            }
            if (end > start) segments.add(text.substring(start, end))
            start = end + 1
        }
        return segments
    }

    /** Whatever is left of the pattern has to be `**`. */
    private fun onlyWildcardsLeft(patternDirs: List<String>, from: Int, to: Int): Boolean {
        for (i in from..to) {
            if (patternDirs[i] != DOUBLE_WILDCARD) return false
        }
        return true
    }

    /** The next `**` after the given position, or -1 when there is none. */
    private fun nextDoubleWildcard(patternDirs: List<String>, from: Int, to: Int): Int {
        for (i in from + 1..to) {
            if (patternDirs[i] == DOUBLE_WILDCARD) return i
        }
        return -1
    }

    /** Where the pattern between the two `**` is found in the path, or -1 when it is not. */
    private fun searchMiddle(
        segments: List<Segment>,
        pathDirs: List<String>,
        patternStart: Int,
        pathStart: Int,
        pathEnd: Int,
        patternLength: Int,
    ): Int {
        val pathLength = pathEnd - pathStart + 1
        for (i in 0..pathLength - patternLength) {
            var fits = true
            for (j in 0 until patternLength) {
                if (!segments[patternStart + j + 1].matches(pathDirs[pathStart + i + j])) {
                    fits = false
                    break
                }
            }
            if (fits) return pathStart + i
        }
        return -1
    }

    /**
     * One pattern segment, compiled.
     *
     * A segment with no wildcard in it is kept as text and compared as text: there is nothing to
     * compile, and comparing two strings is cheaper than running a regex over them.
     */
    private class Segment private constructor(
        private val literal: String?,
        private val regex: Regex?,
    ) {

        /** True when this segment is plain text: no wildcard, no class, no group. */
        val isLiteral: Boolean get() = literal != null

        /**
         * True when this segment matches the given path segment.
         *
         * @param pathSegment one segment of a path, which by construction holds no separator
         */
        fun matches(pathSegment: String): Boolean = when {
            literal != null -> literal == pathSegment
            regex != null -> regex.matches(GuardedText(pathSegment))
            else -> error("A `**` segment is matched by the algorithm, not by a pattern")
        }

        companion object {

            /**
             * Compiles one pattern segment.
             *
             * A `**` segment is not compiled: it is a wildcard that spans segments, and it is the
             * algorithm that handles it, not a pattern that matches a single segment.
             */
            fun compile(text: String): Segment = when {
                text == DOUBLE_WILDCARD -> Segment(null, null)
                text.none { it in WILDCARD_CHARS } -> Segment(text, null)
                else -> Segment(null, compileRegex(text))
            }

            /**
             * The regex of one segment.
             *
             * The segment is compiled with the same flag the engine this replaces used, so that a
             * `{name:regex}` written by an operator keeps behaving exactly as it did — a `.` in
             * that regex matches a newline there, and it does here too.
             */
            private fun compileRegex(segment: String): Regex = try {
                Regex(segmentPattern(segment), RegexOption.DOT_MATCHES_ALL)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("Invalid pattern segment '$segment': ${e.message}", e)
            }
        }
    }

    /**
     * The regex source of one pattern segment.
     *
     * Literal text is quoted in runs; `*` and `?` become the regex that means "anything" and
     * "one character"; `[...]` becomes a character class; and `{...}` becomes either an
     * alternation of literals or, when it carries a colon, a regex of the caller's own.
     *
     * `*` becomes `.*` and not `[^/]*` because a segment holds no separator: the pattern was
     * split on `/` before it got here, and so was the path. There is nothing for a wildcard to
     * cross, and `.*` is the cheaper of the two for the regex engine.
     */
    private fun segmentPattern(segment: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                out.append(Pattern.quote(literal.toString()))
                literal.setLength(0)
            }
        }
        var i = 0
        while (i < segment.length) {
            when (val c = segment[i]) {
                '*' -> {
                    flush()
                    out.append(".*")
                    i++
                }

                '?' -> {
                    flush()
                    out.append(".")
                    i++
                }

                '[' -> {
                    val close = closingBracket(segment, i)
                    if (close < 0) {
                        literal.append(c)
                        i++
                    } else {
                        flush()
                        out.append(characterClass(segment.substring(i + 1, close)))
                        i = close + 1
                    }
                }

                '{' -> {
                    val close = closingBrace(segment, i)
                    if (close < 0) {
                        literal.append(c)
                        i++
                    } else {
                        flush()
                        out.append(braceGroup(segment.substring(i + 1, close)))
                        i = close + 1
                    }
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
        return out.toString()
    }

    /**
     * The `}` that closes the `{` at [open], or -1 when there is none.
     *
     * Nested braces are counted so that a group inside a group closes at the right place.
     */
    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
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
    private fun closingBracket(text: String, open: Int): Int {
        var from = open + 1
        if (from < text.length && text[from] == ']') from++
        return text.indexOf(']', from)
    }

    /**
     * An Ant brace group, as a regex group.
     *
     * `{a,b}` is an alternation of literals, and each alternative is quoted so that a `*` inside
     * one is a star in the name and not a wildcard. `{name:regex}` is the caller's own regex,
     * and it is compiled here so that a broken one is refused while the pattern is built rather
     * than in the middle of matching files.
     */
    private fun braceGroup(body: String): String {
        val colon = body.indexOf(':')
        if (colon >= 0) {
            val regex = body.substring(colon + 1)
            try {
                Regex(regex, RegexOption.DOT_MATCHES_ALL)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("Invalid regex group '{$body}': ${e.message}", e)
            }
            return "(?:$regex)"
        }
        val alternatives = splitAlternatives(body)
        return alternatives.joinToString("|", prefix = "(?:", postfix = ")") { Pattern.quote(it) }
    }

    /**
     * The alternatives of a brace group.
     *
     * Only top-level commas separate: a comma inside a nested group belongs to that group.
     */
    private fun splitAlternatives(body: String): List<String> {
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
        return alternatives
    }

    /**
     * An Ant character class, as a regex character class.
     *
     * Ant writes negation with `!` where a regex uses `^`, and treats `\`, `]` and `^` as
     * ordinary members where a regex gives them meaning. Those three are escaped so the class
     * means what Ant says it means; `-` is left alone because Ant ranges are regex ranges.
     */
    private fun characterClass(body: String): String {
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
        try {
            Regex(cls)
            return cls
        } catch (e: PatternSyntaxException) {
            // A class that is not a valid regex stays literal: the old behaviour matched a file
            // literally named `[...]`, and that is a better answer than throwing mid-match.
            return Pattern.quote("[$body]")
        }
    }

    /**
     * A string that counts how many times the regex engine reads it.
     *
     * A pattern such as a star followed by many repetitions can make the engine backtrack over
     * the same characters exponentially. Counting the reads turns that into a refusal instead of
     * a hang, and the limit is the one the engine this replaces used.
     */
    private class GuardedText(private val text: String) : CharSequence {

        private var attempts = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (attempts++ >= MAX_ATTEMPTS) {
                error("Too many character access attempts encountered during pattern matching")
            }
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            GuardedText(text.substring(startIndex, endIndex))

        override fun toString(): String = text
    }

    private const val SEPARATOR = "/"
    private const val DOUBLE_WILDCARD = "**"
    private const val SINGLE_WILDCARD = "*"
    private const val WILDCARD_CHARS = "*?[{"
    private const val MAX_ATTEMPTS = 1_000_000
}
