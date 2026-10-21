package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsReadYamlStepDefinition].
 *
 * The [source] sealed hierarchy encodes the `file XOR text` invariant directly
 * in the type system: an invocation that supplies BOTH (or neither) cannot be
 * constructed, and the codec rejects such a record at decode time. A runtime
 * check is not needed to reach the same conclusion.
 *
 * The two safety knobs (`codePointLimit`, `maxAliasesForCollections`) are
 * optional. Their defaults are documented on the Step handler itself:
 *
 *  - `codePointLimit = 8 MiB`
 *  - `maxAliasesForCollections = 64`
 *
 * No `Any?` smuggling: every field has a typed Kotlin type.
 */
@Serializable
data class ReadYamlInput(
    /** Exactly one of [ReadYamlSource.FromFile] / [ReadYamlSource.FromText]. */
    val source: ReadYamlSource,
    /**
     * Hard cap on input size in characters. `null` → Step uses the default
     * (8 MiB). Values <= 0 are rejected at decode time as a USER-class failure.
     */
    val codePointLimit: Int? = null,
    /**
     * Hard cap on recursive collection aliases. `null` → Step uses the default
     * (64). Values <= 0 are rejected at decode time.
     */
    val maxAliasesForCollections: Int? = null,
)

/**
 * Sealed source for a YAML read.
 *
 * This hierarchy enforces `file XOR text` at compile time rather than at
 * runtime. The codec rejects dual sources
 * (i.e. a record that somehow contained both fields) — that case cannot
 * occur through the typed API.
 */
@Serializable
sealed interface ReadYamlSource {
    /** Read YAML from a workspace-relative or absolute file path. */
    @Serializable
    data class FromFile(val path: String) : ReadYamlSource

    /** Read YAML from an inline string. */
    @Serializable
    data class FromText(val text: String) : ReadYamlSource
}
