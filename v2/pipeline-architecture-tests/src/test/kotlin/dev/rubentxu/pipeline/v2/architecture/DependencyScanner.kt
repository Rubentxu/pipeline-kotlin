package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * A declared external dependency, classified by HOW the build file spelled it.
 *
 * The classification exists because "I read the coordinate and it was fine" and
 * "I could not read the coordinate" are different claims, and collapsing them
 * turns a scanner into a rubber stamp. [UnresolvedDependency] keeps them apart.
 */
sealed interface DeclaredDependency {

    /** `project(":x")` — an inner-project dependency. Direction is FArchRP030's job, not this scanner's. */
    data class ProjectDependency(val path: String) : DeclaredDependency

    /** `libs.<accessor>`, resolved through the version catalog to a real coordinate. */
    data class CatalogDependency(val accessor: String, val alias: String, val coordinate: MavenCoordinate) : DeclaredDependency

    /** `"group:artifact[:version]"` spelled inline. */
    data class InlineDependency(val coordinate: MavenCoordinate) : DeclaredDependency

    /**
     * `files(...)` / `fileTree(...)` — a path on this machine, not an external
     * coordinate. Neither policy has an opinion about it, and reporting it would
     * bury a real finding under every locally-built test plugin JAR.
     */
    data class FileDependency(val raw: String) : DeclaredDependency

    /**
     * Neither form: a variable, a function call, or a catalog alias this scanner
     * could not resolve. Reported rather than skipped — an unread coordinate is
     * not a safe one.
     */
    data class UnresolvedDependency(val raw: String) : DeclaredDependency
}

/** A `group:artifact` pair, plus the version when the build file pinned one. */
data class MavenCoordinate(val group: String, val artifact: String, val version: String? = null) {

    /**
     * The identifier segments of group and artifact, lower-cased.
     *
     * Matching happens on segments, not substrings, so `hikari` does not fire on an
     * artifact that merely contains those letters, and `kubernetes` does fire on
     * `io.kubernetes:client-java`.
     *
     * Lower-casing is not cosmetic: Maven coordinates are conventionally camelCase
     * — the connection pool is `com.zaxxer:HikariCP`, not `HikariCp` — so a
     * case-sensitive comparison would miss exactly the libraries worth naming.
     */
    fun segments(): Set<String> =
        "$group.$artifact".lowercase().split('.', '-', '_').filter { it.isNotBlank() }.toSet()

    override fun toString(): String = if (version == null) "$group:$artifact" else "$group:$artifact:$version"
}

/** One `configuration(dependency)` line, already classified. */
data class DependencyDeclaration(
    val configuration: String,
    val dependency: DeclaredDependency,
    val file: Path,
    val line: Int,
    val raw: String,
)

/**
 * The `module = "group:artifact"` entries of a Gradle version catalog, keyed by alias.
 *
 * Gradle derives an accessor from an alias by replacing `-` with `.`, so
 * `libs.kotlinx.serialization.json` addresses the alias `kotlinx-serialization-json`.
 * Keys are normalised (`_` folded to `-`, case-insensitive) because the catalog is
 * allowed to spell aliases either way and a lookup miss would otherwise be reported
 * as a false violation.
 */
class VersionCatalog private constructor(private val aliases: Map<String, MavenCoordinate>) {

    fun resolve(alias: String): MavenCoordinate? = aliases[normaliseAlias(alias)]

    fun size(): Int = aliases.size

    companion object {

        private const val CATALOG_ACCESSOR_PREFIX = "libs."

        private val LIBRARY_ENTRY = Pattern.compile("""^\s*([A-Za-z0-9_.-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)\"""")

        internal fun normaliseAlias(alias: String): String = alias.lowercase().replace('_', '-')

        /**
         * Reads the `[libraries]` table. Entries spelled with `group =` / `name =`
         * instead of `module =` are not parsed, and resolving their accessor yields
         * null, which the scanner reports as unresolved rather than as safe.
         */
        fun parse(toml: String): VersionCatalog {
            val aliases = LinkedHashMap<String, MavenCoordinate>()
            var inLibraries = false
            for (line in toml.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[")) {
                    inLibraries = trimmed == "[libraries]"
                    continue
                }
                if (!inLibraries || trimmed.startsWith("#")) continue
                val matcher = LIBRARY_ENTRY.matcher(line)
                if (!matcher.find()) continue
                val coordinate = parseCoordinate(matcher.group(2)) ?: continue
                aliases[normaliseAlias(matcher.group(1))] = coordinate
            }
            return VersionCatalog(aliases)
        }

        fun load(catalogFile: Path): VersionCatalog =
            if (Files.exists(catalogFile)) parse(String(Files.readAllBytes(catalogFile), Charsets.UTF_8)) else VersionCatalog(emptyMap())

        /**
         * The accessor path (`libs.a.b.c`) becomes an alias by joining on `-`.
         * `libs.bundles.x` addresses the `[bundles]` table, which this scanner does
         * not resolve.
         */
        fun aliasFor(accessor: String): String? {
            val path = accessor.removePrefix(CATALOG_ACCESSOR_PREFIX)
            if (path == accessor || path.isBlank()) return null
            val parts = path.split('.')
            if (parts.first() == "bundles") return null
            return normaliseAlias(parts.joinToString("-"))
        }

        /** `"group:artifact"` or `"group:artifact:version"`. Anything else is unreadable. */
        internal fun parseCoordinate(raw: String): MavenCoordinate? {
            val parts = raw.split(':')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            val version = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            return MavenCoordinate(parts[0], parts[1], version)
        }
    }
}

/**
 * Reads declared dependencies out of a Kotlin build script and reports the ones that
 * name a forbidden framework.
 *
 * ## What this replaces, and why it had to be replaced
 *
 * The previous implementation matched `implementation("literal")` only — a quoted
 * coordinate — and compared the resulting `group:artifact` against a set of bare
 * tokens. Against the real `pipeline-domain/build.gradle.kts` it therefore matched
 * **nothing**: the six declarations there are two `libs.*` accessors, a
 * `testImplementation("…")`, a `testRuntimeOnly("…")` and one `api(libs.*)`, and
 * `testImplementation` was not even reachable because the pattern had no word
 * boundary and only matched the lowercase spelling.
 *
 * The test asserting on it passed because there was nothing to find, not because
 * the domain was clean. This scanner resolves catalog accessors, reaches every
 * dependency configuration, and reports what it cannot read.
 */
object DependencyScanner {

    private const val DECLARATION_PREFIX = """\b([A-Za-z][A-Za-z0-9]*)\s*\(\s*"""
    private const val DECLARATION_SUFFIX = """\s*\)"""

    /**
     * Every Gradle dependency configuration, plus its `test`, `androidTest` and
     * `testFixtures` variants. A dependency in a test configuration is still a
     * dependency of the module, and the domain must stay framework-free in both.
     */
    private val CONFIGURATION = Pattern.compile(
        "^(?:test|androidTest|testFixtures)?(?:implementation|api|compileOnlyApi|compileOnly|runtimeOnly|annotationProcessor|kapt)$",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * `configuration(coordinate)`. The alternatives are ordered so the specific
     * forms win: a bare identifier last, because it would otherwise swallow
     * `libs.foo.bar` and `project(":x")` without classifying them.
     */
    private val DECLARATION = Pattern.compile(
        DECLARATION_PREFIX +
            """(project\s*\([^)]*\)|files?\s*\([^)]*\)|fileTree\s*\([^)]*\)|""" +
            """libs\.[A-Za-z0-9_.]+|"[^"]*"|'[^']*'|[A-Za-z_][A-Za-z0-9_.]*)""" +
            DECLARATION_SUFFIX,
    )

    private val PROJECT_PATH = Pattern.compile("""project\s*\(\s*["']([^"']+)["']\s*\)""")

    private val FILE_TREE = Pattern.compile("""^(?:files?|fileTree)\s*\(""")

    /**
     * Reads every dependency declaration from [buildFile], resolving catalog
     * accessors against [catalog].
     *
     * Not scoped to the `dependencies { }` block: the configuration-name filter is
     * what keeps unrelated calls out, and brace tracking over a Kotlin DSL is more
     * likely to miscount a brace inside a string than to admit a stray `api(…)`.
     */
    fun declarations(buildFile: Path, catalog: VersionCatalog): List<DependencyDeclaration> {
        if (!Files.exists(buildFile)) return emptyList()
        val declarations = mutableListOf<DependencyDeclaration>()
        Files.readAllLines(buildFile).forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
            val matcher = DECLARATION.matcher(line)
            while (matcher.find()) {
                val configuration = matcher.group(1)
                if (!CONFIGURATION.matcher(configuration).matches()) continue
                val raw = matcher.group(2)
                declarations.add(
                    DependencyDeclaration(configuration, classify(raw, catalog), buildFile, index + 1, line)
                )
            }
        }
        return declarations
    }

    private fun classify(raw: String, catalog: VersionCatalog): DeclaredDependency {
        PROJECT_PATH.matcher(raw).let { if (it.find()) return DeclaredDependency.ProjectDependency(it.group(1)) }
        if (FILE_TREE.matcher(raw).find()) return DeclaredDependency.FileDependency(raw)
        if (raw.startsWith("\"") || raw.startsWith("'")) {
            val coordinate = VersionCatalog.parseCoordinate(raw.trim('"', '\'')) ?: return DeclaredDependency.UnresolvedDependency(raw)
            return DeclaredDependency.InlineDependency(coordinate)
        }
        if (raw.startsWith("libs.")) {
            val alias = VersionCatalog.aliasFor(raw) ?: return DeclaredDependency.UnresolvedDependency(raw)
            val coordinate = catalog.resolve(alias) ?: return DeclaredDependency.UnresolvedDependency(raw)
            return DeclaredDependency.CatalogDependency(raw, alias, coordinate)
        }
        return DeclaredDependency.UnresolvedDependency(raw)
    }

    /**
     * The declarations whose coordinate names one of [forbiddenTokens].
     *
     * A coordinate is forbidden when a token matches one of its **segments** whole.
     * Substring matching would fire on unrelated names that happen to share letters.
     */
    fun findForbiddenDependencies(
        buildFile: Path,
        catalog: VersionCatalog,
        forbiddenTokens: Collection<String>,
    ): List<Finding> = findings(buildFile, catalog) { declaration, dependency ->
        val coordinate = dependency.orNull() ?: return@findings null
        forbiddenTokens.firstOrNull { it in coordinate.segments() }
    }

    /**
     * The declarations whose `group:artifact` is not in [allowedCoordinates].
     *
     * The mirror policy of [findForbiddenDependencies]: FArch001 names what the
     * domain may not reach for, FArch002 names what the application is allowed to.
     * Both read the same classification, because the earlier shared function took
     * a bare `Set<String>` and let each caller decide what it meant, so a denylist
     * of tokens and an allowlist of coordinates were the same argument type.
     */
    fun findDisallowedCoordinates(
        buildFile: Path,
        catalog: VersionCatalog,
        allowedCoordinates: Set<String>,
    ): List<Finding> = findings(buildFile, catalog) { _, dependency ->
        val coordinate = dependency.orNull() ?: return@findings null
        "${coordinate.group}:${coordinate.artifact}".takeUnless { it in allowedCoordinates }
    }

    /**
     * Shared traversal. [tokenFor] returns the token to report, or null to accept.
     * Inner-project and local-file dependencies are never reported by either
     * policy: direction is FArchRP030's concern and a path is not a coordinate.
     */
    private inline fun findings(
        buildFile: Path,
        catalog: VersionCatalog,
        tokenFor: (DependencyDeclaration, DeclaredDependency) -> String?,
    ): List<Finding> {
        val found = mutableListOf<Finding>()
        for (declaration in declarations(buildFile, catalog)) {
            val dependency = declaration.dependency
            when (dependency) {
                is DeclaredDependency.ProjectDependency -> continue
                is DeclaredDependency.FileDependency -> continue
                is DeclaredDependency.UnresolvedDependency ->
                    found.add(Finding(declaration.file, declaration.line, "unresolved:${dependency.raw}", declaration.raw))
                is DeclaredDependency.CatalogDependency -> tokenFor(declaration, dependency)?.let {
                    found.add(Finding(declaration.file, declaration.line, it, declaration.raw))
                }
                is DeclaredDependency.InlineDependency -> tokenFor(declaration, dependency)?.let {
                    found.add(Finding(declaration.file, declaration.line, it, declaration.raw))
                }
            }
        }
        return found
    }

    /** The coordinate behind a dependency, or null when there is none to check. */
    private fun DeclaredDependency.orNull(): MavenCoordinate? = when (this) {
        is DeclaredDependency.CatalogDependency -> coordinate
        is DeclaredDependency.InlineDependency -> coordinate
        is DeclaredDependency.ProjectDependency -> null
        is DeclaredDependency.FileDependency -> null
        is DeclaredDependency.UnresolvedDependency -> null
    }
}
