package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The interpreter at the consumer boundary of the Maven channel.
 *
 * The pure decider ([evaluateMavenResolution]) is the authority on whether a
 * repository serves what the publication manifest declares. This object is
 * the effectful interpreter that turns that verdict into an outcome a door
 * can fail on: it reads the manifest, asks the repository for every artifact
 * the manifest declares, prints the operator diagnostic and returns a value.
 *
 * Keeping the pass/fail decision out of here is deliberate. A script that
 * re-derived the rules could disagree with the model, and the model is what
 * the tests pin. This only measures and reports.
 *
 * A document that cannot be read is not a document that declares nothing: it
 * is a document nobody can account for, and the refusal says so by name. The
 * same is true of a repository that answers something that is neither a file
 * nor its absence: a channel that could not be read has not been measured,
 * and it is not a channel that holds nothing.
 */
object MavenResolutionAdmission {

    /**
     * Read the manifest, ask the repository for every artifact it declares and
     * decide whether the repository serves them.
     */
    fun admit(manifestPath: Path, reader: MavenRepositoryReader): MavenResolutionOutcome {
        val manifestText = readText(manifestPath)
            ?: return MavenResolutionOutcome.Refused(
                "the manifest at $manifestPath does not exist or cannot be read. A manifest " +
                    "nobody can account for does not say what the repository must serve.",
            )
        val manifest = try {
            MavenPublicationCodec.decodeManifest(manifestText)
        } catch (e: Exception) {
            return MavenResolutionOutcome.Refused(
                "the manifest at $manifestPath is not a manifest this build can act on: " +
                    "${e.message ?: e::class.java.simpleName}",
            )
        }

        val served = mutableListOf<MavenArtifactDigest>()
        for (artifact in manifest.artifacts) {
            when (val file = reader.read(artifact.path)) {
                is MavenServedFile.Absent -> Unit

                is MavenServedFile.Present -> served += artifact.copy(sha256 = file.sha256)

                is MavenServedFile.Unreadable -> return MavenResolutionOutcome.Refused(
                    "the repository could not be read for ${artifact.path}: ${file.reason}. " +
                        "The state of the version was not measured, and a repository that " +
                        "cannot be read is not a repository without the version.",
                )
            }
        }

        val resolved = if (served.isEmpty()) {
            MavenResolvedVersion.Absent
        } else {
            MavenResolvedVersion.Resolved(served)
        }

        return when (val verdict = evaluateMavenResolution(manifest, resolved)) {
            is MavenResolutionVerdict.Verified ->
                MavenResolutionOutcome.Verified(verdict.render())

            is MavenResolutionVerdict.Rejected ->
                MavenResolutionOutcome.Rejected(verdict.render())
        }
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: IOException) {
        null
    }
}

/**
 * Outcome of the resolution check at the boundary.
 */
sealed interface MavenResolutionOutcome {

    /**
     * The repository serves what the manifest declares. [summary] is the
     * verdict's own diagnostic, so the report shows what the check concluded
     * rather than a flattened "OK".
     */
    data class Verified(val summary: String) : MavenResolutionOutcome

    /** The repository does NOT serve what the manifest declares. */
    data class Rejected(val summary: String) : MavenResolutionOutcome

    /** The state could not be measured, so no verdict was reached. */
    data class Refused(val reason: String) : MavenResolutionOutcome
}
