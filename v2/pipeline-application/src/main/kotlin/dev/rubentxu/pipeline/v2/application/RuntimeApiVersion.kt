package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.SemVer

/**
 * S6-COMPOSITION — the ONE place that knows which PipelineK version is running.
 *
 * ## Why this had to be extracted rather than written again
 *
 * Admission compares each plugin's declared `apiRange` against the running version. Before this
 * type there was no production source for that value at all: every one of the eight existing
 * `PluginAdmissionGate` callers passed a literal `SemVer(0, 47, 0)` from a test. A gate that has
 * never seen a real version is not a gate, so wiring pass 1 forced the question.
 *
 * Reusing [readImplementationVersion] rather than reading the resource a second time is the point:
 * the `version` subcommand already reports this value, and a second reader would be a second
 * answer to "what version is this", free to disagree with the first.
 *
 * ## Why a RESOURCE and not `Package.getImplementationVersion()`
 *
 * The first implementation of this type read `object {}.javaClass.getPackage().implementationVersion`,
 * and that was wrong — not because fail-closed is wrong, but because the source was. That call
 * answers only when the code was loaded from a JAR carrying a manifest. Thirty-four UAT and corpus
 * harnesses launch the CLI as `java -cp <test classpath>`, where this module is a CLASSES
 * DIRECTORY and the answer is `null`; admission then refused every such run with
 * `FATAL - jar manifest is missing Implementation-Version` and exit 3. Measured, not hypothesised:
 * 143 test failures across 29 classes, every one of them a harness that had been passing.
 *
 * The rule the first version got right is kept: a build with no version cannot decide plugin
 * compatibility, and must refuse rather than guess. Only the SOURCE changes. The value now comes
 * from a resource generated from `project.version`, which exists in a jar AND in a classes
 * directory. The jar manifest attribute is still populated for `pipeline-release` and for external
 * inspection, and `RuntimeApiVersionDriftTest` fails if the two ever diverge — a second source that
 * can disagree is worse than either source alone.
 *
 * ## Fail-closed, and why a fallback would be worse
 *
 * A missing or unparseable version is a packaging defect. Reporting a version, or admitting plugins
 * against one, that did not come from the build would mean deciding plugin compatibility against a
 * number nobody chose. Both the CLI subcommand and admission therefore refuse, and they refuse the
 * same way, from the same value.
 */
object RuntimeApiVersion {

    /**
     * @throws IllegalStateException when the running build carries no usable version. That is a
     *   build defect, not a runtime condition to be tolerated.
     */
    fun current(): SemVer {
        val raw = readImplementationVersion()
        if (raw.isNullOrBlank()) {
            throw IllegalStateException(
                "the generated version resource is missing or empty at $RESOURCE. Refusing to decide " +
                    "plugin compatibility against a version that did not come from the build. Rebuild " +
                    "via Gradle so the resource is generated from project.version.",
            )
        }
        return parse(raw.trim())
            ?: throw IllegalStateException(
                "version '$raw' read from $RESOURCE is not MAJOR.MINOR.PATCH. The SDK admits plugin " +
                    "apiRanges against a three-component SemVer and nothing else, so a version it " +
                    "cannot read is a version it cannot safely compare.",
            )
    }

    /**
     * The version this build was made from, or null when the resource is absent.
     *
     * Non-throwing for the `version` subcommand, which reports rather than admits and owns its own
     * fail-closed message.
     */
    fun readImplementationVersion(): String? =
        RuntimeApiVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            stream.bufferedReader().readLines()
                .firstOrNull { it.startsWith("version=") }
                ?.removePrefix("version=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    /** `MAJOR.MINOR.PATCH`, or null for anything else. Total: no exception, no partial parse. */
    private fun parse(raw: String): SemVer? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return SemVer(numbers[0], numbers[1], numbers[2])
    }

    private const val RESOURCE = "/dev/rubentxu/pipeline/v2/application/pipelinek-version.properties"
}
