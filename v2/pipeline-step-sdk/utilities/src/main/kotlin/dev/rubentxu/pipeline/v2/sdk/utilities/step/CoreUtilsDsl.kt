package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlDestination
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlPayload
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesPattern
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipSources
import kotlinx.serialization.json.JsonElement

/**
 * Ergonomic Kotlin DSL façade for the LFC-2E2 utilities OFFICIAL_PLUGIN.
 *
 * Mirrors the scm-git pattern: narrow extension functions that build the
 * typed value, encode it via the plugin's own codec, and lower to the
 * generic [StageScope.registryStep] primitive that core provides.
 *
 * The extensions MUST NOT resolve the runtime registry, MUST NOT execute
 * the handler, and MUST NOT inspect any global mutable state.
 */

/** Public step-key accessors. */
fun coreUtilsReadJsonStepKey(): PluginStepId = CoreUtilsReadJsonKey.VALUE
fun coreUtilsWriteJsonStepKey(): PluginStepId = CoreUtilsWriteJsonKey.VALUE
fun coreUtilsSha256StepKey(): PluginStepId = CoreUtilsSha256Key.VALUE
fun coreUtilsReadYamlStepKey(): PluginStepId = CoreUtilsReadYamlKey.VALUE
fun coreUtilsWriteYamlStepKey(): PluginStepId = CoreUtilsWriteYamlKey.VALUE
fun coreUtilsFindFilesStepKey(): PluginStepId = CoreUtilsFindFilesKey.VALUE
fun coreUtilsZipStepKey(): PluginStepId = CoreUtilsZipKey.VALUE

/**
 * `core-utils.readJson` DSL façade.
 *
 * Reads a UTF-8 JSON file from disk and returns a typed value.
 *
 * @param path workspace-relative or absolute path to the JSON file.
 * @param prettyPrint when true (default), pretty-printed JSON is accepted and
 *   preserved as-is in [ReadJsonOutput.rawText].
 * @param returnRawText when true, the parsed `JsonElement` is null and only
 *   the raw text is returned. Defaults to false (typed parse).
 */
fun StageScope.readJson(
    path: String,
    prettyPrint: Boolean = true,
    returnRawText: Boolean = false,
) {
    val input = ReadJsonInput(
        path = path,
        prettyPrint = prettyPrint,
        returnRawText = returnRawText,
    )
    val encoded: EncodedStepValue = CoreUtilsReadJsonInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsReadJsonStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.writeJson` DSL façade.
 *
 * Writes a typed JSON value to disk. Parent directories are created if
 * missing. The file content is deterministic given identical inputs.
 *
 * @param path workspace-relative or absolute target file path.
 * @param value the typed JSON value to serialise.
 * @param prettyPrint when true (default), the file is written with 2-space
 *   indentation; otherwise compact JSON.
 */
fun StageScope.writeJson(
    path: String,
    value: JsonElement,
    prettyPrint: Boolean = true,
) {
    val input = WriteJsonInput(
        path = path,
        value = value,
        prettyPrint = prettyPrint,
        useRawText = false,
    )
    val encoded: EncodedStepValue = CoreUtilsWriteJsonInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsWriteJsonStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.writeJson` raw-text DSL façade.
 *
 * Writes a pre-serialised JSON document to disk verbatim. Use only when the
 * caller already holds a JSON string and does not want it re-formatted.
 */
fun StageScope.writeJsonRaw(
    path: String,
    rawText: String,
) {
    val input = WriteJsonInput(
        path = path,
        value = null,
        rawText = rawText,
        prettyPrint = true,
        useRawText = true,
    )
    val encoded: EncodedStepValue = CoreUtilsWriteJsonInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsWriteJsonStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.sha256` DSL façade.
 *
 * Streams the file content through `MessageDigest` and returns a lowercase
 * hex digest.
 *
 * @param path workspace-relative or absolute target file path.
 * @param algorithm the message-digest algorithm to use; allowed values are
 *   `SHA-256` (default) and `SHA-1`. Anything else is rejected at runtime
 *   with a typed USER-class failure.
 */
fun StageScope.sha256(
    path: String,
    algorithm: String = "SHA-256",
) {
    val input = Sha256Input(
        path = path,
        algorithm = algorithm,
    )
    val encoded: EncodedStepValue = CoreUtilsSha256InputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsSha256StepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.readYaml` DSL façade (file source).
 *
 * Reads a UTF-8 YAML file from disk and returns a typed [dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput].
 *
 * @param path workspace-relative or absolute path to the YAML file.
 * @param codePointLimit override for the input size cap. `null` uses the
 *   Step default (8 MiB). Values <= 0 are rejected at runtime as a typed
 *   USER-class failure.
 * @param maxAliasesForCollections override for the recursive collection alias
 *   cap. `null` uses the Step default (64).
 */
fun StageScope.readYaml(
    path: String,
    codePointLimit: Int? = null,
    maxAliasesForCollections: Int? = null,
) {
    val input = ReadYamlInput(
        source = ReadYamlSource.FromFile(path = path),
        codePointLimit = codePointLimit,
        maxAliasesForCollections = maxAliasesForCollections,
    )
    val encoded: EncodedStepValue = CoreUtilsReadYamlInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsReadYamlStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.readYaml` DSL façade (inline text source).
 *
 * Parses an inline YAML string instead of reading from disk. The
 * `file XOR text` invariant is enforced by the typed source: this function
 * builds [ReadYamlSource.FromText], the `readYaml(path: ...)` overload
 * builds [ReadYamlSource.FromFile], and both are mutually exclusive.
 */
fun StageScope.readYamlText(
    text: String,
    codePointLimit: Int? = null,
    maxAliasesForCollections: Int? = null,
) {
    val input = ReadYamlInput(
        source = ReadYamlSource.FromText(text = text),
        codePointLimit = codePointLimit,
        maxAliasesForCollections = maxAliasesForCollections,
    )
    val encoded: EncodedStepValue = CoreUtilsReadYamlInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsReadYamlStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.writeYaml` DSL façade (file destination, single document).
 *
 * Writes a typed [YamlDocument] to disk as a UTF-8 YAML file. Refuses to
 * overwrite an existing file unless [overwrite] is explicitly `true` (Jenkins'
 * default behaviour). Parent directories are created automatically.
 */
fun StageScope.writeYaml(
    path: String,
    value: YamlDocument,
    overwrite: Boolean = false,
) {
    val input = WriteYamlInput(
        destination = WriteYamlDestination.ToFile(path = path, overwrite = overwrite),
        payload = WriteYamlPayload.Single(value = value),
    )
    val encoded: EncodedStepValue = CoreUtilsWriteYamlInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsWriteYamlStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.writeYaml` DSL façade (file destination, multiple documents).
 *
 * Writes a sequence of typed YAML documents as one file using SnakeYAML's
 * `dumpAll` (each document separated by `---`).
 */
fun StageScope.writeYamlMultiple(
    path: String,
    documents: List<YamlDocument>,
    overwrite: Boolean = false,
) {
    require(documents.isNotEmpty()) {
        "core-utils.writeYamlMultiple: documents list must not be empty"
    }
    val input = WriteYamlInput(
        destination = WriteYamlDestination.ToFile(path = path, overwrite = overwrite),
        payload = WriteYamlPayload.Multiple(documents = documents),
    )
    val encoded: EncodedStepValue = CoreUtilsWriteYamlInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsWriteYamlStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.zip` DSL façade (archive a directory subtree).
 *
 * Creates a zip file at [path] (workspace-relative or absolute). Refuses
 * to overwrite an existing archive unless [overwrite] is true (Jenkins
 * default). Symlinks inside [directory] are followed and archived as
 * regular files.
 */
fun StageScope.zipDir(
    path: String,
    directory: String,
    overwrite: Boolean = false,
) {
    val input = ZipInput(
        path = path,
        overwrite = overwrite,
        sources = ZipSources.FromDirectory(directory),
    )
    val encoded: EncodedStepValue = CoreUtilsZipInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsZipStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.zip` DSL façade (archive a glob of files).
 *
 * Globs are interpreted with the same Java NIO + Jenkins-compat two-
 * stars-slash fallback that `findFiles` uses.
 */
fun StageScope.zipGlob(
    path: String,
    glob: String,
    overwrite: Boolean = false,
) {
    val input = ZipInput(
        path = path,
        overwrite = overwrite,
        sources = ZipSources.FromGlob(glob),
    )
    val encoded: EncodedStepValue = CoreUtilsZipInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsZipStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.zip` DSL façade (archive a literal list of paths).
 */
fun StageScope.zipFiles(
    path: String,
    paths: List<String>,
    overwrite: Boolean = false,
) {
    require(paths.isNotEmpty()) {
        "core-utils.zipFiles: paths list must not be empty"
    }
    val input = ZipInput(
        path = path,
        overwrite = overwrite,
        sources = ZipSources.FromFiles(paths),
    )
    val encoded: EncodedStepValue = CoreUtilsZipInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsZipStepKey(),
        encodedInput = encoded,
    )
}

/**
 * `core-utils.findFiles` DSL façade (file-system scanner).
 *
 * Enumerates entries under a workspace-relative directory, optionally
 * filtered by a glob and an exclusion glob. An empty glob lists direct
 * children (Jenkins `findFiles()` default). The handler never follows
 * symlinks — they appear in the listing as themselves.
 *
 * @param base workspace-relative or absolute path to scan.
 * @param glob optional glob pattern. Java NIO glob syntax.
 *   Use `*.txt` for direct children, or `two stars slash star.txt` for
 *   recursive matching.
 * @param excludes optional glob pattern to drop matches against.
 */
fun StageScope.findFiles(
    base: String,
    glob: String? = null,
    excludes: String? = null,
) {
    val pattern: FindFilesPattern = if (glob == null) {
        FindFilesPattern.None
    } else {
        FindFilesPattern.Glob(glob = glob, excludes = excludes)
    }
    val input = FindFilesInput(base = base, pattern = pattern)
    val encoded: EncodedStepValue = CoreUtilsFindFilesInputCodec.encode(input)
    registryStep(
        stepKey = coreUtilsFindFilesStepKey(),
        encodedInput = encoded,
    )
}
