package dev.rubentxu.pipeline.v2.artefacts.local

import org.springframework.util.AntPathMatcher
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ant-style glob pattern matcher using Spring's [AntPathMatcher].
 *
 * Matches workspace-relative paths against Ant-style patterns (`, `**`, `?`, `[...]`, `{...}`).
 * Supports multi-pattern expansion, user excludes, and Jenkins-compatible 13-entry default excludes.
 *
 * Design: D7 — wraps Spring `AntPathMatcher` (already on classpath via spring-core).
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
         * INV-L6-ANT-001: this list must remain exactly 13 entries.
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
    }

    private val matcher = AntPathMatcher()

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

        val excludeMatchers = effectiveExcludes.map { ExcludeMatcher(it) }
        val includeMatchers = patternList.map { PatternMatcher(it) }

        val matched = allFiles
            .filter { file ->
                val rel = root.relativize(file)
                val relStr = rel.toString()
                val matchesInclude = includeMatchers.any { it.matches(relStr) }
                val matchesExclude = excludeMatchers.any { it.matches(relStr) }
                matchesInclude && !matchesExclude
            }
            .toSet()

        return matched.sortedBy { it.toString() }
    }

    private inner class PatternMatcher(private val pattern: String) {
        fun matches(path: String): Boolean {
            return matcher.match(pattern, path)
        }
    }

    private inner class ExcludeMatcher(private val pattern: String) {
        fun matches(path: String): Boolean {
            return matcher.match(pattern, path)
        }
    }
}
