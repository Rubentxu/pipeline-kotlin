package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * C5: Validates and creates the control root directory.
 *
 * Validation rules:
 * - Path must not contain ".." (parent directory traversal)
 * - Path must not be a system root (/tmp, /home, /var, etc.)
 * - Directory is created if valid
 *
 * @param path The path string to validate
 * @return The validated Path
 * @throws IllegalArgumentException if validation fails
 */
fun validateControlRoot(path: String): Path {
    require(!path.contains("..")) {
        "control-root path must not contain '..' (parent directory traversal not allowed)"
    }

    val validatedPath = Paths.get(path).toAbsolutePath()

    // Reject system roots (exact match only, not subdirectories)
    val systemRoots = setOf("/", "/tmp", "/home", "/var", "/etc", "/usr", "/bin", "/sbin", "/proc", "/sys")
    require(!systemRoots.contains(validatedPath.toString())) {
        "control-root path must not be a system root: $validatedPath"
    }

    // Create directories if they don't exist
    Files.createDirectories(validatedPath)

    return validatedPath
}

/**
 * Builds a [ClassLoader] that prefers [Thread.currentThread]'s context loader
 * for normal classes and falls back to the user-supplied `--plugin-jar` entries
 * for plugin JARs the application classpath does not include.
 */
internal fun pluginClassLoaderFor(jars: List<String>): ClassLoader =
    java.net.URLClassLoader(
        jars.map { java.io.File(it).toURI().toURL() }.toTypedArray(),
        Thread.currentThread().contextClassLoader,
    )

/**
 * SH-classpath WU: single composition authority for what JARs the
 * script-compile and runtime-discovery classloaders must see.
 *
 * Returns the merged list of:
 *  - bundled plugins resolved from the runtime classpath (install/lib + tests)
 *    via [BundledPluginClasspathPlan];
 *  - user-supplied `--plugin-jar` entries (`config.pluginJars`);
 *  - the canonical SDK JARs the Kotlin compiler cannot see automatically
 *    (pipeline-domain and pipeline-scripting-api), explicit by name only.
 *
 *  The same list feeds `updateClasspath` in `Kotlin24ScriptingHost.compile`
 *  AND `pluginClassLoaderFor(...)` at runtime. Anything that mutates the
 *  list per-call is a defect: the S3 invariant is "ONE classpath feeds BOTH
 *  script compiler AND runtime ServiceLoader discovery — no split"
 *  (see comment above `--plugin-jar` parsing at the top of `main`).
 *
 *  Failures are emitted to stderr as diagnostics and surface through the
 *  calling site's exit code (typically 2). No silent fallbacks.
 */
internal fun computeScriptClasspath(pluginJars: List<String>): List<String> {
    val bundledPlugins = computeBundledPlugins()
    return buildList {
        // SDK public types the Kotlin compiler must see but `wholeClasspath=false`
        // never adds automatically. Order is stable so the cache key stays stable.
        ScriptDefinition.domainJar()?.let(::add)
        ScriptDefinition.dslApiJar()?.let(::add)
        addAll(bundledPlugins)
        addAll(pluginJars)
    }
}

/**
 * Resolves the bundled plugin JARs from the runtime classpath via
 * [BundledPluginClasspathPlan]. Failures (conflicts / rejections) terminate
 * the process with exit code 2 so the failure mode is observable to callers.
 */
internal fun computeBundledPlugins(): List<String> {
    val classpath = System.getProperty("java.class.path", "")
    return when (val resolution = BundledPluginClasspathPlan.fromClasspathString(classpath)) {
        is BundledPluginResolution.Resolved -> resolution.artifacts.map { it.canonicalPath }
        BundledPluginResolution.Empty -> emptyList()
        is BundledPluginResolution.Conflicting -> {
            System.err.println(
                "Bundled plugin classpath conflict: ${resolution.conflicts.size} group(s): " +
                    resolution.conflicts.joinToString("; ") {
                        "${it.baseName} (${it.reason}): ${it.first.canonicalPath} vs ${it.second.canonicalPath}"
                    }
            )
            System.exit(2)
            emptyList() // unreachable
        }
        is BundledPluginResolution.Rejected -> {
            System.err.println("Bundled plugin classpath rejected: ${resolution.reason}")
            if (resolution.cause != null) resolution.cause.printStackTrace(System.err)
            System.exit(2)
            emptyList() // unreachable
        }
    }
}

/**
 * S6-COMPOSITION — the product boundary: admit every plugin artifact, then compose, or exit.
 *
 * ## Why the exit lives here and not at the call site
 *
 * `Main` composes from two branches. If each one rendered its own refusal, one could report a
 * refusal while the other composed anyway, and the two messages would drift apart as they were
 * edited. Rendering once, here, makes "a refused plugin stops the run" a property of a single
 * function rather than a convention repeated in two places.
 *
 * ## Why exit 2 and not exit 1
 *
 * 2 is this CLI's established code for "a plugin was refused", already used by the bundled-classpath
 * conflict above, and 3 is reserved for a packaging defect. A refused plugin is an operator-facing
 * refusal with a named artifact; a missing build version is a broken build. Collapsing them would
 * make the two indistinguishable from a log line.
 */
internal fun admitThenComposeOrExit(pluginJars: List<String>): PreResolvedComposition =
    when (val outcome = PluginCompositionAdmitter.admitThenCompose(pluginJars)) {
        is CompositionOutcome.Composed -> {
            outcome.composition.also { composition ->
                if (outcome.admitted.isNotEmpty()) {
                    composition.reportTo { line -> System.err.println(line) }
                }
            }
        }

        is CompositionOutcome.Refused -> {
            System.err.println("Plugin admission refused for ${outcome.artifact}: ${outcome.rejection}")
            System.exit(2)
            error("unreachable: System.exit does not return")
        }

        is CompositionOutcome.RuntimeVersionUnavailable -> {
            System.err.println("FATAL — ${outcome.detail}")
            System.exit(3)
            error("unreachable: System.exit does not return")
        }
    }
