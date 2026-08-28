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
class AntStyleGlob private constructor(private val pattern: String) {

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
        fun fromList(patterns: List<String>): AntStyleGlob {
            return AntStyleGlob(patterns.joinToString(","))
        }
    }

    // Constructor for vararg patterns
    constructor(vararg patterns: String) : this(patterns.joinToString(","))

    private val matcher = AntPathMatcher()

    // Internal list storage - split comma-separated pattern back to list
    private val patternList: List<String> = if (pattern.contains(",")) {
        pattern.split(",").filter { it.isNotEmpty() }
    } else {
        listOf(pattern)
    }

    init {
        require(pattern.isNotEmpty()) { "At least one pattern must be provided" }
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
