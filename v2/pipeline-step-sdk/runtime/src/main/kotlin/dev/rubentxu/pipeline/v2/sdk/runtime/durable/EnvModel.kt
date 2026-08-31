package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * Environment variable model for shell step execution.
 *
 * Handles the PATH/JAVA_HOME/M2_HOME legacy semantics from V1:
 * - JAVA_HOME/bin is prepended to PATH if JAVA_HOME is set
 * - M2_HOME/bin is prepended to PATH if M2_HOME is set
 *
 * This preserves V1 semantics where Maven/Gradle wrappers would work
 * with the expected PATH ordering.
 *
 * ## ML-R4 Typed Env Channel
 *
 * The [apply] function is overloaded to handle both:
 * - Legacy `Map<String, String>` (back-compat via [ShOptions.from])
 * - Typed `Map<String, SecretHandle>` for ML-R4 secret redaction
 *
 * When using the typed form, masked entries (PATH manipulation) bypass PATH prepend
 * and are propagated as-is to the output; non-masked entries are transformed.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
object EnvModel {

    /**
     * Regex for PATH+= prepend syntax (Jenkins verbatim).
     * Matches keys like "PATH+MAVEN" where the VALUE is the directory to prepend.
     * Example: key="PATH+MAVEN", value="/opt/maven/bin" → prepends /opt/maven/bin to PATH.
     */
    private val PATH_PLUS_REGEX = Regex("""^PATH\+([A-Za-z0-9_]+)$""")

    /**
     * Applies environment variable transformations for typed SecretHandle env.
     *
     * Given the user-provided typed env map, this function:
     * 1. Copies all non-masked entries to the output map
     * 2. Handles PATH+= prepend syntax (PATH+=/dir prepends to existing PATH)
     * 3. If JAVA_HOME is set, prepends `${JAVA_HOME}/bin` to PATH
     * 4. If M2_HOME is set, prepends `${M2_HOME}/bin` to PATH
     *
     * Masked entries bypass PATH prepend and are propagated as-is to the output.
     * PATH prepend applies only to non-masked entries.
     *
     * @param env The typed user-provided environment variables.
     * @return The transformed environment with PATH adjustments.
     */
    @JvmName("applyTyped")
    fun apply(env: Map<String, SecretHandle>): Map<String, SecretHandle> {
        // For the typed form, we need to:
        // 1. Propagate masked non-PATH+= entries as-is (not secret values)
        // 2. Apply PATH prepend logic using the materialized path values
        // 3. Wrap transformed plain values back in SecretHandle.plain()

        // Track whether PATH was originally masked (before any loop could add it)
        val originalPathInInput = env.containsKey("PATH")
        val originalPathHandle = env["PATH"]
        val originalPathMasked = originalPathInInput && originalPathHandle?.isMasked == true
        val originalPath = originalPathHandle?.materialize() ?: System.getenv("PATH") ?: ""

        val out = mutableMapOf<String, SecretHandle>()

        // Collect PATH+= prepends to apply after processing all entries
        val pathPlusDirs = mutableListOf<String>()

        // Copy all entries: non-masked PATH+= captured for prepend,
        // non-masked non-PATH+= copied directly, masked non-PATH+= propagated as-is
        for ((key, handle) in env) {
            if (!handle.isMasked) {
                val match = PATH_PLUS_REGEX.matchEntire(key)
                if (match != null) {
                    // PATH+= prepend syntax: capture the directory to prepend
                    val dir = match.groupValues[1]
                    pathPlusDirs.add(dir)
                } else {
                    out[key] = handle
                }
            } else {
                // Masked non-PATH+= entry — propagate as-is (not a secret value)
                out[key] = handle
            }
        }

        // Apply PATH+= prepends in order (deduplicated, preserving order)
        var currentPath = originalPath
        for (dir in pathPlusDirs) {
            if (dir.isNotEmpty()) {
                currentPath = "$dir${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
            }
        }

        // JAVA_HOME/bin prepend to PATH (materialize even if masked — only used for path string)
        val javaHomeHandle = env["JAVA_HOME"]
        if (javaHomeHandle != null) {
            val javaHome = javaHomeHandle.materialize()
            currentPath = "${javaHome}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        // M2_HOME/bin prepend to PATH (starts from original PATH, not from JAVA_HOME-modified PATH)
        val m2HomeHandle = env["M2_HOME"]
        if (m2HomeHandle != null) {
            val m2Home = m2HomeHandle.materialize()
            // M2_HOME prepends to whatever PATH is currently (after JAVA_HOME)
            currentPath = "${m2Home}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        // Add computed PATH only if: (a) PATH was not masked in input, OR (b) there are prepends
        // If PATH was masked in input, it was already propagated as-is above; do not overwrite
        if (currentPath.isNotEmpty() && !originalPathMasked) {
            out["PATH"] = SecretHandle.plain(currentPath)
        }

        return out
    }

    /**
     * Applies environment variable transformations for legacy String env.
     *
     * This is a convenience wrapper that delegates to the typed form
     * via [ShOptions.from] for back-compat with legacy callers.
     *
     * Handles PATH+= prepend syntax (Jenkins verbatim):
     * - PATH+= value prepends to existing PATH
     * - Multiple PATH+= entries prepend in order (first declared = outermost)
     *
     * @param env The legacy user-provided environment variables.
     * @return The transformed environment with PATH adjustments.
     */
    @JvmName("applyLegacy")
    fun apply(env: Map<String, String>): Map<String, String> {
        // T-02 regression fix: apply(emptyMap()) must return empty map.
        // The empty input contract is that no environment variables are inherited —
        // not even PATH from the controller's system. Early return before any
        // PATH fallback logic so the controller's PATH never leaks into step envs.
        if (env.isEmpty()) {
            return emptyMap()
        }

        val out = env.toMutableMap()

        // Capture original PATH before any modifications so each prepend starts from the base.
        // When the user env carries no PATH, seed from the current process environment
        // (Jenkins withEnv semantics: PATH+X prepends onto the EXISTING PATH). Without this
        // seed, putAll would REPLACE the ProcessBuilder PATH with only JAVA_HOME/bin and
        // setsid/bash would become unresolvable (observed: setsid "failed to execute bash").
        val originalPath = out["PATH"] ?: System.getenv("PATH") ?: ""

        // Collect PATH+= prepends in the order they appear
        val pathPlusDirs = mutableListOf<String>()

        // Process entries: handle PATH+= syntax and remove duplicates
        val keysToRemove = mutableListOf<String>()
        for ((key, value) in env) {
            val match = PATH_PLUS_REGEX.matchEntire(key)
            if (match != null) {
                pathPlusDirs.add(value)
                keysToRemove.add(key)
            }
        }

        // Remove PATH+= keys from output (we handle them separately)
        for (key in keysToRemove) {
            out.remove(key)
        }

        // T-02 regression: when no PATH+= entries are present, preserve original behavior
        // (JAVA_HOME/M2_HOME prepend onto originalPath without deduplication).
        // Only deduplicate when PATH+= is actually used, per the PATH+= prepend semantics.
        var currentPath = if (pathPlusDirs.isEmpty()) {
            originalPath
        } else {
            // Apply PATH+= prepends in order: first declared = outermost (appears first in final PATH)
            // Deduplicate globally: first occurrence wins (pathPlusDirs first, then original PATH)
            val allDirs = pathPlusDirs.filter { it.isNotEmpty() } + originalPath.split(":").filter { it.isNotEmpty() }
            val seenDirs = mutableSetOf<String>()
            val uniqueOrderedDirs = allDirs.filter { dir ->
                if (dir !in seenDirs) {
                    seenDirs.add(dir)
                    true
                } else {
                    false
                }
            }
            uniqueOrderedDirs.joinToString(":")
        }

        // JAVA_HOME/bin prepend to PATH
        env["JAVA_HOME"]?.let { javaHome ->
            currentPath = "${javaHome}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        // M2_HOME/bin prepend to PATH (starts from original PATH, not from JAVA_HOME-modified PATH)
        env["M2_HOME"]?.let { m2Home ->
            currentPath = "${m2Home}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        if (currentPath.isNotEmpty()) {
            out["PATH"] = currentPath
        }

        return out
    }

    /**
     * Checks if an environment variable value is safe for pb.environment().
     *
     * JDK's ProcessBuilder.environment() rejects NUL characters (\u0000).
     * Values containing =, $, newlines, quotes, backticks, unicode, etc.
     * are all safe and will be passed verbatim.
     *
     * @param value The environment variable value to check.
     * @return true if the value is safe for ProcessBuilder.
     */
    fun isValueSafeForProcessBuilder(value: String): Boolean {
        // ProcessBuilder.environment() rejects NUL
        return !value.contains('\u0000')
    }
}

// ===== Sandbox extension functions =====

/**
 * Deny-list of environment variable keys that are stripped from the sandboxed env.
 *
 * These keys are removed unless they appear in [allowExtra].
 * Covers: LD_PRELOAD, LD_LIBRARY_PATH, BASH_ENV, ENV, SHELLOPTS,
 * BASH_FUNC_*, IFS, PYTHONPATH, NODE_OPTIONS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS.
 */
private val DENY_LIST: Set<String> = setOf(
    "LD_PRELOAD",
    "LD_LIBRARY_PATH",
    "BASH_ENV",
    "ENV",
    "SHELLOPTS",
    "IFS",
    "PYTHONPATH",
    "NODE_OPTIONS",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
)

/** Keys in the deny-list that use a glob/prefix match (BASH_FUNC_*). */
private val DENY_LIST_GLOB = setOf("BASH_FUNC_")

/**
 * Applies the env deny-list: strips deny-listed keys unless in [allowExtra].
 *
 * Called for [SandboxProfile.LOCAL] only. [SandboxProfile.NONE] skips this entirely.
 *
 * @param env The base environment map (typically pb.environment() snapshot).
 * @param allowExtra Set of keys to retain even if deny-listed.
 * @return A new map with deny-listed keys removed (unless in allowExtra).
 */
fun Map<String, String>.applyDenyList(allowExtra: Set<String>): Map<String, String> {
    return this.filter { (key, _) ->
        if (allowExtra.contains(key)) {
            true // explicitly allowed — retain
        } else if (key in DENY_LIST) {
            false // deny-listed — remove
        } else if (DENY_LIST_GLOB.any { key.startsWith(it) }) {
            false // BASH_FUNC_* glob — remove
        } else {
            true // not deny-listed — retain
        }
    }
}

/**
 * Default PATH keep-set: prefixes that survive PATH normalisation.
 * Add entries via [PIPELINE_SANDBOX_PATH_KEEP] env var / sysprop.
 */
private val DEFAULT_PATH_KEEP: Set<String> = setOf(
    "/usr",
    "/bin",
    "/sbin",
    "/opt",
)

/**
 * Normalises PATH: keeps only entries whose absolute prefix is in [pathKeep]
 * plus the default keep-set, then prepends `${javaHome}/bin` and `${m2Home}/bin`.
 *
 * Prepend order: JAVA_HOME/bin first, then M2_HOME/bin (V1 legacy — prepend always wins).
 * Prefix matching: `/tmp/rogue/x` is dropped if `/tmp/rogue` is NOT in keep-set;
 * `/opt/x/y` is retained because `/opt` IS in the default keep-set.
 *
 * @param pathKeep Additional prefixes to retain (from PIPELINE_SANDBOX_PATH_KEEP).
 * @param javaHome JAVA_HOME path (if set, `${javaHome}/bin` is prepended to PATH).
 * @param m2Home M2_HOME path (if set, `${m2Home}/bin` is prepended after JAVA_HOME).
 * @return A new map with normalised PATH.
 */
fun Map<String, String>.normalizePath(
    pathKeep: Set<String>,
    javaHome: String?,
    m2Home: String?,
): Map<String, String> {
    val effectiveKeep = DEFAULT_PATH_KEEP + pathKeep

    // Split existing PATH
    val existingPath = this["PATH"] ?: ""
    val pathEntries = if (existingPath.isNotEmpty()) {
        existingPath.split(":").filter { it.isNotEmpty() }
    } else {
        emptyList()
    }

    // Filter: retain entries whose immediate parent directory is in keep-set.
    // Top-level entries like /bin or /opt (no '/' after position 0) are retained if they themselves are in keep-set.
    // e.g., /usr/local is kept (parent /usr in keep-set), but /usr/local/bin is NOT
    // (parent /usr/local is not in keep-set; /usr/local/bin is a grandchild)
    val retainedEntries = pathEntries.filter { entry ->
        val parentDir = if (entry.indexOf("/", 1) == -1) {
            // No '/' after position 0 → top-level entry like /bin or /opt
            entry
        } else {
            entry.substringBeforeLast('/')
        }
        effectiveKeep.contains(parentDir)
    }

    // Rebuild PATH: prepends first, then retained entries
    val normalisedEntries = mutableListOf<String>()

    // JAVA_HOME/bin prepend first
    javaHome?.let { normalisedEntries.add("$it/bin") }

    // M2_HOME/bin prepend second
    m2Home?.let { normalisedEntries.add("$it/bin") }

    // Add retained PATH entries (deduplicated, preserving order)
    for (entry in retainedEntries) {
        if (entry !in normalisedEntries) {
            normalisedEntries.add(entry)
        }
    }

    val newPath = if (normalisedEntries.isEmpty()) "" else normalisedEntries.joinToString(":")

    return if (newPath == existingPath && pathKeep.isEmpty()) {
        this // No change — return same map instance
    } else {
        this.toMutableMap().apply {
            put("PATH", newPath)
        }
    }
}
