package dev.rubentxu.pipeline.v2.application

import java.nio.file.Path

/**
 * The definition of a pipeline: what the script says, and the hash that
 * identifies it.
 *
 * ## Why one reading and one hash
 * The durable policy decides, on that hash, whether a persisted run is reused or
 * replaced. Two readings of the same script could produce two hashes, and the
 * disagreement would only surface when a run is resumed — in another world, and
 * long after the run that caused it. So the content and its identity are decided
 * together, here, once.
 */
internal data class DefinitionSource(
    val content: String,
    val definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
)

/**
 * Read the script of a run and identify it.
 *
 * Both worlds do this, and they have to do it the same way: the definition id
 * is what a durable policy matches a persisted run against, and the content is
 * what the compiler lowers.
 */
internal fun readDefinitionSource(scriptPath: Path): DefinitionSource {
    val content = scriptPath.toFile().readText()
    return DefinitionSource(
        content = content,
        definitionId = dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator.definitionId(
            scriptPath.toString(),
            content,
        ),
    )
}
