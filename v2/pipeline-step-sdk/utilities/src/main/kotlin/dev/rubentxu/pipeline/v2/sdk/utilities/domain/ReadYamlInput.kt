package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsReadYamlStepDefinition].
 *
 * The [source] sealed hierarchy encodes Jenkins' `file XOR text` invariant
 * directly in the type system: an invocation that supplies BOTH (or neither)
 * cannot be constructed without the codec rejecting it at decode time. This
 * is the typed form of Jenkins' runtime check in
 * [AbstractFileOrTextStep](https://github.com/jenkinsci/pipeline-utility-steps-plugin/blob/master/src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/AbstractFileOrTextStep.java).
 *
 * The two safety knobs surfaced by Jenkins (`codePointLimit`,
 * `maxAliasesForCollections`) are optional. Defaults are documented on the
 * Step handler itself and follow the Jenkins-reference decision recorded in
 * `docs/v2/07-uat/S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md`:
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
 * Jenkins' `AbstractFileOrTextStep` enforces `file XOR text` at runtime;
 * this hierarchy enforces it at compile time. The codec rejects dual sources
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
