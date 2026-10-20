package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed output for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsReadYamlStepDefinition].
 *
 * Mirrors Jenkins' behaviour: when exactly one YAML document is provided the
 * typed result is `single` (a [YamlDocument]); when several are provided the
 * result is `documents` (a `List<YamlDocument>`). Exactly one of the two is
 * populated. The `multipleDocuments` flag exists so a caller can branch
 * without re-parsing.
 *
 * A [YamlDocument] is a closed ADT derived from SnakeYAML's safe-constructor
 * output. Only primitives (text, integer, real, boolean, null), sequences of
 * documents, and mappings from text keys to documents are representable.
 * The closed ADT is what keeps the "no `Any?` smuggling" invariant true:
 * a [YamlDocument] cannot be an arbitrary class instance because the safe
 * constructor would not have produced one.
 */
@Serializable
data class ReadYamlOutput(
    /**
     * Populated when the input contains exactly one YAML document. `null`
     * when [multipleDocuments] is true.
     */
    val single: YamlDocument? = null,
    /**
     * Populated when the input contains multiple YAML documents (SnakeYAML's
     * `loadAll` returned 2+ elements). `null` when [single] is set.
     */
    val documents: List<YamlDocument>? = null,
    /**
     * Convenience flag: `true` ⇔ [documents] is set. Allows callers that do
     * not want to discriminate on null vs empty list to branch cleanly.
     */
    val multipleDocuments: Boolean,
    /** UTF-8 size of the source text, in bytes. */
    val byteSize: Long,
    /**
     * Absolute path that was read, or `null` when the input came from inline
     * text (no on-disk source).
     */
    val absolutePath: String?,
)

/**
 * Closed ADT for YAML values produced by SnakeYAML's safe constructor.
 *
 * Why a closed ADT? Two reasons.
 *
 *  1. The Jenkins reference ([ReadYamlStep.Execution.doRun] —
 *     `https://github.com/jenkinsci/pipeline-utility-steps-plugin/blob/master/src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/conf/ReadYamlStep.java`)
 *     protects against arbitrary class instantiation by routing through
 *     `SafeConstructor`. PipelineK encodes the same invariant as a type.
 *  2. PipelineK's durable boundary serialises outputs through a codec.
 *     A `Map<String, Any?>` would roundtrip through `LinkedHashMap` and
 *     collapse numbers into a single Kotlin `Number` type that is no longer
 *     distinguishable from arbitrary user input. The closed ADT keeps
 *     integer / real / boolean / null distinguishable across runs.
 *
 * The variants cover the YAML 1.1 standard scalar types plus the two
 * container types SnakeYAML's safe constructor can produce. There is no
 * variant for "arbitrary Java class" — by construction.
 */
@Serializable
sealed interface YamlDocument {
    /** YAML scalar: text. */
    @Serializable
    data class Str(val value: String) : YamlDocument

    /**
     * YAML integer scalar. SnakeYAML returns `Integer` or `Long` depending on
     * magnitude; the codec normalises to [Long] for durability.
     */
    @Serializable
    data class Integer(val value: Long) : YamlDocument

    /** YAML real (floating-point) scalar. */
    @Serializable
    data class Real(val value: Double) : YamlDocument

    /** YAML boolean scalar. */
    @Serializable
    data class Bool(val value: Boolean) : YamlDocument

    /** YAML null / `~` / `null` literal. */
    @Serializable
    data object Null : YamlDocument

    /** YAML sequence (block or flow). */
    @Serializable
    data class Seq(val items: List<YamlDocument>) : YamlDocument

    /** YAML mapping. Keys are always [Str] — the safe constructor disallows non-string keys. */
    @Serializable
    data class Map(val entries: List<Entry>) : YamlDocument {
        /** Map entry preserves insertion order (SnakeYAML returns LinkedHashMap). */
        @Serializable
        data class Entry(val key: String, val value: YamlDocument)
    }
}
