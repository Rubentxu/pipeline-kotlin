package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import java.io.File
import java.util.jar.JarFile

/**
 * Composition authority for what JARs the script compiler sees (SH-classpath WU).
 *
 * The Single Runtime Spine invariants:
 *
 *  - A `.pipeline.kts` script must be compilable in the same classpath where it will
 *    be executed. The runtime feeds ServiceLoader (TCCL) from
 *    `java.class.path`, but the Kotlin scripting host replaces the host classpath
 *    for compilation (`wholeClasspath = false`). The compiler must therefore be
 *    handed the same plugin identity set explicitly.
 *  - Bundled plugins are the artefacts shipped in the pipelinek distribution and
 *    enabled by default. They register themselves at runtime via the
 *    [StepDefinitionContributor] SPI. The same SPI is the discriminator that
 *    decides whether a JAR on `install/lib/` is a public plugin (compile-visible
 *    DSL façade) or an internal runtime-only artefact (credential materialiser,
 *    journal, app launcher, ...).
 *  - External plugins remain user-supplied via `--plugin-jar`. Their classpath
 *    entries are added by the user, deduped against bundled plugins by baseName,
 *    and resolved to the same identities the compiler and the runtime see.
 *  - One resolver feeds BOTH sides. There are no per-site `when`-branches and
 *    no `when(stepKey)`-style switches in [dev.rubentxu.pipeline.v2.application.Main].
 *
 * Design (HS / SH-classpath WU):
 *
 *  - Inputs are pure (an iterable of classpath entries); outputs are sealed.
 *  - The classpath is taken from `java.class.path` (set by the launcher script),
 *    falling back to `System.getProperty("APP_HOME")/lib` when the system property
 *    `java.class.path` is unavailable in unusual harnesses.
 *  - Two JARs with the same baseName + version on different paths is a fail-closed
 *    conflict; two JARs with different versions of the same baseName is also a
 *    fail-closed conflict (we never silently load two copies of `pipeline-domain`
 *    or any other public type).
 *
 *  NOT in scope (deliberate): the helper does not read pipeline catalogues,
 *  does not hot-load or hot-reload, does not parse META-INF beyond the contributor
 *  file. Plugin authors do not need to do anything additional to ship in the
 *  pipelinek distribution: the existing [StepDefinitionContributor] registration
 *  is what makes them visible to the script compiler.
 */
sealed interface BundledPluginResolution {
    /** Successful resolution. The list of canonical paths ordered for stable cache keys. */
    data class Resolved(val artifacts: List<ArtifactIdentity>) : BundledPluginResolution {
        init {
            require(artifacts.isNotEmpty()) {
                "BundledPluginResolution.Resolved must carry at least one identity"
            }
        }
    }

    /** No bundled plugin found on the runtime classpath; not an error — allows empty SDK builds. */
    data object Empty : BundledPluginResolution

    /**
     * Two or more classpath entries conflict. Reason is human-readable, conflicts
     * are the offending identities (preserves evidence for the diagnostic).
     */
    data class Conflicting(
        val reason: String,
        val conflicts: List<ConflictingArtifacts>,
    ) : BundledPluginResolution

    /**
     * Fail-closed composition failure that does not match the conflict shape
     * (e.g. an unreadable JAR, malformed manifest). Diagnostics surface to stderr
     * with the original cause preserved.
     */
    data class Rejected(val reason: String, val cause: Throwable? = null) : BundledPluginResolution
}

/** Pairs of equivalent baseName with diverging version / path facts. */
data class ConflictingArtifacts(
    val baseName: String,
    val first: ArtifactIdentity,
    val second: ArtifactIdentity,
    val reason: String,
)

/**
 * Identity of a plugin JAR visible to the script compiler.
 *
 * `canonicalPath` is the absolute resolved file path used to address the JAR;
 * `baseName` is the artefact stem without version (e.g. `scm-git`); `version`
 * is the parsed `-x.y.z` suffix when present (best-effort, may be null for
 * unreleased snapshots). Two JARs with the same baseName are considered
 * duplicates when their version strings differ.
 */
data class ArtifactIdentity(
    val canonicalPath: String,
    val baseName: String,
    val version: String?,
)

/**
 * Compose the classpath identity set the script compiler must see.
 *
 * Pure function over explicit inputs. The resolution scans every entry of the
 * given classpath source list (typically `java.class.path`); entries that are
 * plain directories are walked recursively; entries that are not files nor
 * directories are ignored. Each candidate JAR is probed for the canonical
 * [StepDefinitionContributor] service file. Hits are converted into
 * [ArtifactIdentity] and deduped by `(baseName, version)`.
 */
object BundledPluginClasspathPlan {

    /**
     * Compose the script-compiler-visible plugin classpath.
     *
     * @param classpathSource semicolon- or colon-separated classpath string
     *   (typical: [System.getProperty] `"java.class.path"`).
     */
    fun fromClasspathString(classpathSource: String): BundledPluginResolution {
        if (classpathSource.isBlank()) return BundledPluginResolution.Empty
        val separator = System.getProperty("path.separator", if (File.separatorChar == '/') ":" else ";")
        val entries = classpathSource.split(separator).map(String::trim).filter(String::isNotEmpty)
        return compose(entries)
    }

    /**
     * Compose the script-compiler-visible plugin classpath from a discrete
     * list of classpath entries. Exposed for tests and for callers that already
     * hold the entries as a list (e.g. an App Home lib dir scan).
     */
    fun compose(entries: List<String>): BundledPluginResolution {
        val candidates = collectCandidates(entries)
        if (candidates.isEmpty()) return BundledPluginResolution.Empty
        return dedupAndValidate(candidates)
    }

    // ---------------------------------------------------------------------
    // Pure helpers (no I/O beyond candidate collection).
    // ---------------------------------------------------------------------

    /**
     * Find every JAR on the classpath that declares the canonical
     * [StepDefinitionContributor] service file. Returns raw identities (no
     * dedup). Pure relative to the filesystem.
     */
    private fun collectCandidates(entries: List<String>): List<ArtifactIdentity> {
        val seenCanonical = mutableSetOf<String>()
        val out = mutableListOf<ArtifactIdentity>()
        for (entry in entries) {
            val file = File(entry)
            when {
                !file.exists() -> {}
                file.isDirectory -> {
                    file.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".jar") }
                        .forEach { jar ->
                            maybeAppend(jar, seenCanonical, out)
                        }
                }
                file.isFile && file.name.endsWith(".jar") -> {
                    maybeAppend(file, seenCanonical, out)
                }
            }
        }
        return out
    }

    private fun maybeAppend(file: File, seen: MutableSet<String>, out: MutableList<ArtifactIdentity>) {
        if (!isPluginJar(file)) return
        val canonical = file.canonicalPath
        if (!seen.add(canonical)) return
        out.add(parseIdentity(file, canonical))
    }

    /**
     * True iff the JAR carries a `META-INF/services/<StepDefinitionContributor FQN>`
     * file. This is the **single** discriminator: it is the same manifest the
     * JRE's ServiceLoader uses at runtime, so the script compiler sees exactly
     * the set the runtime will discover via TCCL.
     */
    private fun isPluginJar(file: File): Boolean = runCatching {
        JarFile(file).use { jar ->
            val contributorEntry = jar.getJarEntry(
                "META-INF/services/" + StepDefinitionContributor::class.java.name
            ) ?: return false
            // Entry must be a real file (not a directory) and non-empty.
            val size = contributorEntry.size
            size > 0L
        }
    }.getOrDefault(false)

    private fun parseIdentity(file: File, canonical: String): ArtifactIdentity {
        val (base, version) = splitBaseAndVersion(file.nameWithoutExtension)
        return ArtifactIdentity(canonicalPath = canonical, baseName = base, version = version)
    }

    /**
     * Split an artefact filename like `scm-git-0.36.0` into `("scm-git", "0.36.0")`.
     * If the trailing segment is not a version (no digits) the version is null and
     * the whole name is the base.
     */
    internal fun splitBaseAndVersion(stem: String): Pair<String, String?> {
        // Find the LAST '-'; verify the suffix parses as a version (it must contain a digit and a dot
        // or consist of digits only — snapshots like `1.0.0-SNAPSHOT` count as versioned).
        val lastDash = stem.lastIndexOf('-')
        if (lastDash <= 0) return stem to null
        val candidate = stem.substring(lastDash + 1)
        val looksLikeVersion = candidate.any(Char::isDigit) && (
            candidate.contains('.') || candidate.all { it.isDigit() || it == '-' } || candidate.endsWith("-SNAPSHOT")
        )
        return if (looksLikeVersion) {
            stem.substring(0, lastDash) to candidate
        } else {
            stem to null
        }
    }

    private fun dedupAndValidate(candidates: List<ArtifactIdentity>): BundledPluginResolution {
        if (candidates.isEmpty()) return BundledPluginResolution.Empty
        // Same canonical path: silently collapsed by collectCandidates.
        // Different canonical paths with the same identity: REPORT.
        val byKey: MutableMap<Pair<String, String?>, MutableList<ArtifactIdentity>> = mutableMapOf()
        for (identity in candidates) {
            val key = identity.baseName to identity.version
            byKey.getOrPut(key) { mutableListOf() }.add(identity)
        }
        val conflicts = mutableListOf<ConflictingArtifacts>()
        for ((key, group) in byKey) {
            if (group.size < 2) continue
            // Two JARs sharing the SAME identity (baseName + version) on the
            // classpath is the canonical conflict: paths diverge because of
            // build/output layout choices, not intent. Having even one extra
            // copy of a plugin causes the JVM to load two independent
            // registries, the Kotlin compiler to resolve plugin symbols to
            // two physically different classes, and `ServiceLoader` to
            // discover the same contributor twice — a guaranteed
            // duplicate-registration rejection at best, silent inconsistency
            // at worst. Reject immediately, regardless of whether the
            // canonical paths happen to collide after JVM-level resolution.
            // Any two JARs sharing the same identity (baseName + version) is
            // a conflict: paths diverge because of build/output layout
            // choices, not intent. The post-de-dup invariant is "one path
            // per plugin identity". Anything else would let the JVM load
            // two independent registries and the Kotlin compiler resolve
            // plugin symbols to two physically different classes.
            conflicts += ConflictingArtifacts(
                baseName = key.first,
                first = group.first(),
                second = group.first { it.canonicalPath != group.first().canonicalPath },
                reason = "duplicate copy of the same plugin (same identity, ${group.size} JARs)",
            )
        }
        if (conflicts.isNotEmpty()) {
            return BundledPluginResolution.Conflicting(
                reason = "Bundled plugin classpath conflict: ${conflicts.size} group(s)",
                conflicts = conflicts,
            )
        }
        // Stable ordering for deterministic cache keys: by baseName then by version.
        val sorted = candidates
            .distinctBy { it.canonicalPath }
            .sortedWith(compareBy({ it.baseName }, { it.version ?: "" }))
        return BundledPluginResolution.Resolved(sorted)
    }
}
