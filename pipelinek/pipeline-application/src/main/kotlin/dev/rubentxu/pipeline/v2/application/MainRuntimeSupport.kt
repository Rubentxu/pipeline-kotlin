package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlJournal
import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ConsolePrintingEventSink
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Validates and creates the control root directory.
 * Validation rules:
 * - Path must not contain ".." (parent directory traversal)
 * - Path must not be a system root (/tmp, /home, /var, etc.)
 * - Directory is created if valid
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
 * Returns the merged list of:
 *  - bundled plugins resolved from the runtime classpath (install/lib + tests)
 *    via [BundledPluginClasspathPlan];
 *  - user-supplied `--plugin-jar` entries (`config.pluginJars`);
 *  - the canonical SDK JARs the Kotlin compiler cannot see automatically
 *    (pipeline-domain and pipeline-scripting-api), explicit by name only.
 *  The same list feeds `updateClasspath` in `Kotlin24ScriptingHost.compile`
 *  AND `pluginClassLoaderFor(...)` at runtime. Anything that mutates the
 * List per-call is a defect: the invariant is "ONE classpath feeds BOTH
 *  script compiler AND runtime ServiceLoader discovery — no split"
 *  (see comment above `--plugin-jar` parsing at the top of `main`).
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
 * What a run is allowed to say: the format that encodes it, the view that
 * selects it and the query that filters it.
 *
 * The three travel together because the command line decides them together,
 * and a composition that took them apart could be called with one decision from
 * one place and another from another.
 */
internal data class NarrationPolicy(
    val format: ObservationFormat,
    val view: ObservationView,
    val query: CompiledObservationQuery,
)

/**
 * The store a run narrates itself through, and the ONE place that decides how.
 *
 * Both execution paths call this, so the two cannot disagree about what a run
 * says about itself. The worlds differ in WHERE the events are kept — a SQLite
 * journal or an in-memory store — and in nothing else: the same script, run
 * with a database or without one, tells the operator the same story.
 *
 * ## The order of the two decorators IS the security property
 *
 * Redaction comes first, and the console presents what the store keeps. A
 * console wired ABOVE the redaction renders the event before it has been
 * scrubbed, and that is how a registered secret reaches the terminal: the run
 * refuses to keep it and prints it anyway.
 *
 * ## The human view narrates while the run happens
 *
 * A run that is watched has to be legible while it runs. The narration is
 * printed as the events are appended, so the operator sees which step is
 * running now instead of a transcript that arrives when everything is over.
 * The machine formats keep their document at the end: a JSON array is a
 * document, and a document cannot be streamed.
 *
 * @param emit where the narration goes. The caller owns the destination; this
 * function owns WHAT is narrated and in what shape.
 */
internal fun composeNarratingEventStore(
    raw: EventSink,
    policy: NarrationPolicy,
    secretPatternRegistry: SecretPatternRegistry,
    emit: (String) -> Unit = { line -> System.out.println(line) },
): EventSink {
    val narrating = if (policy.format == ObservationFormat.TEXT) {
        ConsolePrintingEventSink(raw, policy.view, policy.query, emit)
    } else {
        raw
    }
    return RedactingEventSink(narrating, secretPatternRegistry)
}

/**
 * The admission of a run's plugins, with the operator's trust policy consulted and the decision
 * journalled.
 *
 * The three facts are ONE decision, and they were three omissions: the plugin jars the operator
 * named, the trust source they named with `--plugin-trust`, and the control root where the
 * decision is recorded. Naming only the first left a policy that was parsed and never
 * consulted, and a journal whose only writer was a door nobody called — an operator could not
 * tell "the policy approved this plugin" from "the policy was never asked".
 *
 * Both execution paths call THIS, so the two cannot disagree about what was admitted, what the
 * policy said about it, or whether it left a record.
 */
internal fun admitPluginsForRun(config: CliFlags, controlDirRoot: Path): PreResolvedComposition =
    admitThenComposeOrExit(
        config.pluginJars,
        // The policy is OPT-IN: without the file the runtime admits plugins as it always has.
        pluginTrustSource(config.pluginTrust),
        // The row is written before the decision returns, which is what makes the journal an
        // audit trail rather than a log.
        AdmissionControlJournal(controlDirRoot),
    )

/**
 * The product boundary: admit every plugin artifact, then compose, or exit.
 * ## Why the exit lives here and not at the call site
 * `Main` composes from two branches. If each one rendered its own refusal, one could report a
 * refusal while the other composed anyway, and the two messages would drift apart as they were
 * edited. Rendering once, here, makes "a refused plugin stops the run" a property of a single
 * function rather than a convention repeated in two places.
 * ## Why exit 2 and not exit 1
 * 2 is this CLI's established code for "a plugin was refused", already used by the bundled-classpath
 * conflict above, and 3 is reserved for a packaging defect. A refused plugin is an operator-facing
 * refusal with a named artifact; a missing build version is a broken build. Collapsing them would
 * make the two indistinguishable from a log line.
 * ## What the refusal says
 * The typed rejection names WHAT happened, and the detail says WHY in the operator's words: two
 * refusals of the same kind can have different causes, and an operator who is told only the kind
 * has to go and read the code to find out which one they hit.
*/
internal fun admitThenComposeOrExit(
    pluginJars: List<String>,
    trust: PluginTrustSource? = null,
    journal: AdmissionControlJournal? = null,
): PreResolvedComposition =
    when (val outcome = PluginCompositionAdmitter.admitThenCompose(pluginJars, trust, journal)) {
        is CompositionOutcome.Composed -> {
            outcome.composition.also { composition ->
                // The trust decision is reported BEFORE the composition, because admission comes
                // first: an operator reading the report has to see which of their plugins the
                // policy approved, and on what evidence, before the list of what the plugins
                // contribute.
                outcome.admitted.forEach { artifact ->
                    val approval = artifact.trust
                    if (approval != null) {
                        System.err.println(
                            "Plugin ${artifact.plugin.pluginId} admitted under the operator's trust " +
                                "policy: bytes ${approval.digest.value}, publisher ${approval.publisher}, " +
                                "key ${approval.keyId}",
                        )
                    }
                }
                if (outcome.admitted.isNotEmpty()) {
                    composition.reportTo { line -> System.err.println(line) }
                }
            }
        }

        is CompositionOutcome.Refused -> {
            val reason = outcome.rejection.toString()
            val detail = outcome.detail?.let { " — $it" }.orEmpty()
            System.err.println("Plugin admission refused for ${outcome.artifact}: $reason$detail")
            System.exit(2)
            error("unreachable: System.exit does not return")
        }

        is CompositionOutcome.UnattributedProvider -> {
            // No artifact can be named for this one: the provider does not say which artifact
            // provides it. The provider's own name is the only thing the operator can act on.
            System.err.println(
                "Plugin admission refused: provider ${outcome.providerClass} could not be " +
                    "attributed to an artifact (${outcome.reason})",
            )
            System.exit(2)
            error("unreachable: System.exit does not return")
        }

        is CompositionOutcome.RuntimeVersionUnavailable -> {
            System.err.println("FATAL — ${outcome.detail}")
            System.exit(3)
            error("unreachable: System.exit does not return")
        }
    }

/**
 * The operator's trust material, read from the file they named, or `null` when they named none.
 *
 * ## Why the flag is opt-in
 * A trust policy that cannot be turned on is not a policy; a policy that is always on is a
 * behaviour change every installation would inherit. So the operator asks for it by naming their
 * file, and until they do, the runtime admits plugins the way it always has — on the manifest's
 * declaration and the API range.
 *
 * ## Where the publisher keys live
 * Beside the trust file, in `<trust-file-dir>/keys/<keyId>.pub`. The keyring is a DIRECTORY
 * rather than a field of the trust file on purpose: a row that carried its own public key could
 * carry an attacker's key, while a row that can only NAME a key the operator already keeps cannot.
 */
internal fun pluginTrustSource(trustFile: String?): PluginTrustSource? {
    val path = trustFile?.let { Path.of(it) } ?: return null
    val keysDir = path.toAbsolutePath().parent?.resolve("keys") ?: Path.of("keys")
    return PluginTrustSource(
        trustDatabase = LocalFileOperatorTrustDatabase(path),
        keyRing = FilePublisherKeyRing(keysDir),
    )
}
