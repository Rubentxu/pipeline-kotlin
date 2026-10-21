package dev.rubentxu.pipeline.v2.artefacts.local

import java.nio.file.Files
import java.nio.file.Path

/**
 * Ant-style glob pattern matcher.
 *
 * Matches workspace-relative paths against Ant-style patterns. The dialect is ANT and it is
 * implemented here, in [AntPathMatching]: the constructs Ant and the engine this project used
 * to borrow disagreed on, and the project no longer borrows an engine.
 *
 * The Ant constructs this class honours, and what each one means here:
 *
 * - `**` — any number of path segments, including none.
 * - `?` — exactly one character.
 * - `[...]` — a character class: `[a-c]` matches `a`, `b` or `c`; `[!abc]` matches anything
 *   else. Ant semantics: the engine this project used to borrow read the brackets as literal
 *   text, so a character range matched nothing at all.
 * - `{...}` — alternation: `{jar,war}` matches `jar` or `war`, and nothing else. Ant semantics.
 *   The engine this project used to borrow read a colon-free group as a wildcard and matched
 *   EVERYTHING, which turned a pattern that names two extensions into "every file under the
 *   directory".
 * - `{name:regex}` — the escape hatch for what Ant cannot express, kept verbatim. This is the
 *   one construct that is NOT Ant.
 *
 * Supports multi-pattern expansion, user excludes, and Jenkins-compatible 13-entry default
 * excludes.
 *
 * Design: D7 — the dialect is implemented in this project, in [AntPathMatching]; this class
 * holds the patterns and walks the tree.
 * Invariant: INV-L6-ANT-001 (default excludes verbatim), INV-L6-ANT-002 (traversal safety).
 */
// `patternList` is internal, not public: it is the decision `match()` acts on, and a unit test
// cannot call `match()` at all because it walks a real directory. Exposing the list is what makes
// "a pattern containing a comma stays one pattern" a testable fact instead of a comment -- the
// matching itself stays E2E.
class AntStyleGlob private constructor(internal val patternList: List<String>) {

    /**
     * One pattern. A comma inside it is part of the pattern, not a separator: `{jar,war}` is
     * Ant alternation and `data/a,b.txt` is a legal POSIX filename.
     */
    constructor(pattern: String) : this(listOf(pattern)) {
        require(pattern.isNotEmpty()) { "At least one pattern must be provided" }
    }

    /** Several patterns, each preserved whole. */
    constructor(vararg patterns: String) : this(patterns.toList()) {
        require(patterns.isNotEmpty()) { "At least one pattern must be provided" }
    }


    companion object {
        /**
         * Jenkins 13-entry default excludes (verbatim from Jenkins `archiveArtifacts` page).
         * this list must remain exactly 13 entries.
         */
        val DEFAULT_EXCLUDES: List<String> = listOf(
            "**/.git/**",
            "**/.svn/**",
            "**/.bzr/**",
            "**/.hg/**",
            "**/CVS/**",
            "**/.DS_Store",
            "**/.gitignore",
            "**/.gitattributes",
            "**/.hgignore",
            "**/.hgsub",
            "**/.hgtags",
            "**/.bzrignore",
            "**/.bzr-tags",
        )

        /**
         * Factory for List<String> - joins to comma-separated string.
         */
        /**
         * Factory for [List] inputs.
         *
         * This used to flatten the list into one comma-separated string and re-split it inside the
         * constructor, so a single element containing a comma came back as two broken fragments.
         * That made the documented `{...}` alternation unmatchable -- `build/**/*.{jar,war}` became
         * `build/**/*.{jar` plus `war}` -- and a caller passing a pattern rather than a list, which
         * is what all four production call sites do, silently lost any pattern with a comma in it.
         *
         * The list is now carried as a list. There is no separator to collide with.
         */
        fun fromList(patterns: List<String>): AntStyleGlob {
            require(patterns.isNotEmpty()) { "At least one pattern must be provided" }
            return AntStyleGlob(patterns.toList())
        }

        /**
         * Splits a comma-separated list of Ant patterns WITHOUT splitting inside a group.
         *
         * The convention "a list of patterns in one String, separated by commas" collides with
         * Ant's own use of the comma: `build/**/*.{jar,war}` is ONE pattern whose alternation
         * holds a comma. Splitting on every comma shredded it into `build/**/*.{jar` and `war}`,
         * and both fragments match nothing -- the caller then reported "matched no files", or, with
         * `allowEmptyArchive`, archived an empty tar and reported success.
         *
         * So a comma is a separator only at the top level: not inside `{...}`, not inside `[...]`.
         * The rule lives here, next to the dialect it belongs to, so every caller of a
         * list-in-a-String field reads it the same way instead of carrying its own copy.
         *
         * @param list the comma-separated list
         * @return the patterns, trimmed, with the empty ones dropped
         */
        fun splitPatternList(list: String): List<String> {
            val patterns = mutableListOf<String>()
            val current = StringBuilder()
            var braces = 0
            var brackets = 0
            for (c in list) {
                when {
                    c == '{' -> {
                        braces++
                        current.append(c)
                    }

                    c == '}' -> {
                        if (braces > 0) braces--
                        current.append(c)
                    }

                    c == '[' -> {
                        brackets++
                        current.append(c)
                    }

                    c == ']' -> {
                        if (brackets > 0) brackets--
                        current.append(c)
                    }

                    c == ',' && braces == 0 && brackets == 0 -> {
                        patterns += current.toString()
                        current.setLength(0)
                    }

                    else -> current.append(c)
                }
            }
            patterns += current.toString()
            return patterns.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /**
     * Matches files under [root] against this glob's patterns.
     *
     * @param root Root directory to search under (walk is bounded to this root)
     * @param excludes Additional exclude patterns (applied after default excludes if enabled)
     * @param defaultExcludes Whether to apply Jenkins 13-entry default excludes
     * @return Sorted, deduplicated list of matching file paths under [root]
     */
    fun match(
        root: Path,
        excludes: List<String> = emptyList(),
        defaultExcludes: Boolean = true,
    ): List<Path> {
        val effectiveExcludes = if (defaultExcludes) excludes + DEFAULT_EXCLUDES else excludes

        val allFiles = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }

        // Each pattern is compiled ONCE, into the dialect the engine speaks, and then asked
        // about every file. Compiling per file would re-parse the same pattern for every path
        // under the root.
        val excludePatterns = effectiveExcludes.map { AntGlobPattern(it) }
        val includePatterns = patternList.map { AntGlobPattern(it) }

        val matched = allFiles
            .filter { file ->
                val rel = root.relativize(file)
                val relStr = rel.toString()
                val matchesInclude = includePatterns.any { it.matches(relStr) }
                val matchesExclude = excludePatterns.any { it.matches(relStr) }
                matchesInclude && !matchesExclude
            }
            .toSet()

        return matched.sortedBy { it.toString() }
    }
}
