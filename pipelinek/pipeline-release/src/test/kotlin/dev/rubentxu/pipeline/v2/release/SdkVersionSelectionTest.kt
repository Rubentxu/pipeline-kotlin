package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the SDK ZIP carries the artifacts of ONE version, and only
 * the ones that version published.
 *
 * The capacity this serves: an SDK that a consumer can resolve. The ZIP is
 * what the product publishes for the plugins that build against the public
 * contracts, and a consumer resolves the coordinates the layout names. An
 * entry that merely MENTIONS the version does not resolve to that version's
 * artifact; it resolves to nothing, or — worse — to an artifact of a
 * different version that happens to sit in the same place.
 *
 * These rows are pure: paths in, a selection out. No repository on disk, no
 * build, no clock. The paths are the ones the SDK repository really has, and
 * the question is which of them belong to the version being packaged.
 */
class SdkVersionSelectionTest {

    private fun select(vararg paths: String, version: String = "0.80.0"): List<String> =
        SdkZipBuilder.selectVersionedEntries(paths.toList(), version)

    @Test
    fun `the artifacts of the requested version are selected`() {
        val selected = select(
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar",
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.pom",
            version = "0.80.0",
        )

        assertEquals(
            listOf(
                "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar",
                "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.pom",
            ),
            selected,
            "the artifacts of the version being packaged are the ones the consumer resolves",
        )
    }

    @Test
    fun `the artifacts of another version are left out`() {
        val selected = select(
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.79.0/pipeline-domain-0.79.0.jar",
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar",
            version = "0.80.0",
        )

        assertEquals(
            listOf("dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar"),
            selected,
            "a version the ZIP does not claim to publish must not travel inside it",
        )
    }

    @Test
    fun `a path that mentions the version outside its own folder is left out`() {
        // The repository sat under a directory named after the release, or the
        // checkout did. The version appears in the path, but the artifact
        // belongs to another version, and a rule that reads the path as text
        // would publish it under a version it does not belong to.
        val selected = select(
            "0.80.0/dev/rubentxu/pipeline/v2/pipeline-domain/0.79.0/pipeline-domain-0.79.0.jar",
            version = "0.80.0",
        )

        assertTrue(
            selected.isEmpty(),
            "an artifact whose OWN folder is another version does not belong to this one, " +
                "however the path spells it: got $selected",
        )
    }

    @Test
    fun `a file whose name carries the version is left out`() {
        val selected = select(
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.79.0/pipeline-domain-0.80.0.jar",
            version = "0.80.0",
        )

        assertTrue(
            selected.isEmpty(),
            "the version names the FOLDER an artifact lives in, not the file inside it: got $selected",
        )
    }

    @Test
    fun `an artifact at the root of the repository is left out`() {
        val selected = select(
            "README.md",
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar",
            version = "0.80.0",
        )

        assertEquals(
            listOf("dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar"),
            selected,
            "a file with no version folder belongs to no version",
        )
    }

    @Test
    fun `the selection is sorted, so the ZIP is byte-deterministic`() {
        val selected = select(
            "dev/rubentxu/pipeline/v2/pipeline-events/0.80.0/pipeline-events-0.80.0.jar",
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.80.0/pipeline-domain-0.80.0.jar",
            "dev/rubentxu/pipeline/v2/pipeline-sdk-bom/0.80.0/pipeline-sdk-bom-0.80.0.pom",
            version = "0.80.0",
        )

        assertEquals(
            selected.sorted(),
            selected,
            "the same repository always yields the same ZIP, byte for byte",
        )
    }

    @Test
    fun `a repository with no artifact of the version selects nothing`() {
        val selected = select(
            "dev/rubentxu/pipeline/v2/pipeline-domain/0.79.0/pipeline-domain-0.79.0.jar",
            version = "0.80.0",
        )

        assertTrue(
            selected.isEmpty(),
            "an empty selection is the state the builder refuses: it is the absence of the " +
                "SDK, and the builder says so rather than packaging an empty archive",
        )
    }
}
