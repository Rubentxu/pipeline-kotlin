package dev.rubentxu.pipeline.v2.sdk.runtime.durable

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
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
object EnvModel {

    /**
     * Applies environment variable transformations.
     *
     * Given the user-provided env map, this function:
     * 1. Copies all entries to the output map
     * 2. If JAVA_HOME is set, prepends `${JAVA_HOME}/bin` to PATH
     * 3. If M2_HOME is set, prepends `${M2_HOME}/bin` to PATH
     *
     * The PATH prepend order is: JAVA_HOME/bin first, then M2_HOME/bin,
     * matching V1 legacy semantics from `core/.../Shell.kt:80-95`.
     *
     * @param env The user-provided environment variables.
     * @return The transformed environment with PATH adjustments.
     */
    fun apply(env: Map<String, String>): Map<String, String> {
        val out = env.toMutableMap()

        // Capture original PATH before any modifications so each prepend starts from the base.
        // When the user env carries no PATH, seed from the current process environment
        // (Jenkins withEnv semantics: PATH+X prepends onto the EXISTING PATH). Without this
        // seed, putAll would REPLACE the ProcessBuilder PATH with only JAVA_HOME/bin and
        // setsid/bash would become unresolvable (observed: setsid "failed to execute bash").
        val originalPath = out["PATH"] ?: System.getenv("PATH") ?: ""

        // JAVA_HOME/bin prepend to PATH
        env["JAVA_HOME"]?.let { javaHome ->
            out["PATH"] = "${javaHome}/bin${if (originalPath.isNotEmpty()) ":$originalPath" else ""}"
        }

        // M2_HOME/bin prepend to PATH (starts from original PATH, not from JAVA_HOME-modified PATH)
        env["M2_HOME"]?.let { m2Home ->
            val basePath = out["PATH"] ?: originalPath
            out["PATH"] = "${m2Home}/bin${if (basePath.isNotEmpty()) ":$basePath" else ""}"
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
