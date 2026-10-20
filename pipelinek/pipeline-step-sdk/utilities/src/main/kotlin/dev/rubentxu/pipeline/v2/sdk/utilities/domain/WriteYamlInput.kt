package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsWriteYamlStepDefinition].
 *
 * Three orthogonal sealed hierarchies encode Jenkins' `data XOR datas`,
 * `file XOR returnText`, and the `data kind` (single document vs collection
 * of documents) invariants directly in the type system:
 *
 *  - [destination] is `ToFile(path, overwrite)` XOR `ToText`.
 *  - [payload] is `Single(value)` XOR `Multiple(documents)`.
 *  - The two are independent: you may write a single value to a file, a
 *    single value to text, multiple documents to a file, or multiple
 *    documents to text.
 *
 * Reference: pipeline-utility-steps-plugin `WriteYamlStep.java` — same
 * mutual exclusion is enforced at runtime in `start(...)`. The typed
 * hierarchy turns that runtime check into a compile-time guarantee.
 */
@Serializable
data class WriteYamlInput(
    /** Where the YAML output goes (file XOR text). */
    val destination: WriteYamlDestination,
    /** What gets serialised (single document XOR multiple documents). */
    val payload: WriteYamlPayload,
    /** Override the default UTF-8 charset. Reserved for future use. */
    val charset: String = "UTF-8",
)

/** Where the YAML output lands. */
@Serializable
sealed interface WriteYamlDestination {
    /**
     * Write to a workspace-relative or absolute file path.
     *
     * Jenkins default `overwrite = false`; we keep the same default so an
     * accidental overwrite requires explicit consent.
     */
    @Serializable
    data class ToFile(val path: String, val overwrite: Boolean = false) : WriteYamlDestination

    /** Return the YAML as a string instead of writing a file. */
    @Serializable
    data object ToText : WriteYamlDestination
}

/** The data being serialised. */
@Serializable
sealed interface WriteYamlPayload {
    /** A single YAML document. The value is the closed [YamlDocument] ADT. */
    @Serializable
    data class Single(val value: YamlDocument) : WriteYamlPayload

    /**
     * Multiple YAML documents emitted as a single text/file using SnakeYAML's
     * `dumpAll`. Jenkins calls this `datas`.
     */
    @Serializable
    data class Multiple(val documents: List<YamlDocument>) : WriteYamlPayload
}
