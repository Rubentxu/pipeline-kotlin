package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The distribution is READ, and not started.
 *
 * ## The guarantee under trial
 *
 * That the ZIP the installer publishes is the artefact the operator holds:
 * which files it carries, and that the installer of it asks for nothing that
 * would reach outside the machine that installs it.
 *
 * ## Why these rows READ and do not START
 *
 * Because a row that starts the product can go red for a reason the code does
 * not have, and the gate certifies the TREE: the rows that start the
 * installed product live in `InstalledProductBootIntegrationTest`, they are
 * declared in `scripts/check-product-boot-layer.py`, and the gate does not
 * call them. Reading the ZIP costs nothing and cannot be red for the
 * machine's reasons.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The subject is the ZIP the build wrote: what it carries is a fact about the
 * bytes that leave the tree, and a double of the ZIP would answer whatever
 * the double was told.
 */
class DistributionArtifactIntegrationTest {

    @Test
    fun `the distribution carries the launcher, the application and the client that talks to the service`() {
        val entries = entriesOfDistribution()

        assertTrue(entries.contains("bin/pipelinek"), "the distribution carries no launcher: $entries")
        assertTrue(entries.contains("bin/pipelinek.bat"), "the distribution carries no launcher for Windows: $entries")
        assertTrue(
            entries.contains("lib/pipeline-application-${version()}.jar"),
            "the distribution carries no application: $entries",
        )
        assertTrue(
            entries.contains("lib/pipeline-service-client-${version()}.jar"),
            "the distribution carries no client of the service: $entries",
        )
        assertTrue(
            entries.none { it.contains("docker") },
            "the distribution carries something named after a Docker socket: $entries",
        )
    }

    @Test
    fun `the installer does not need a Docker socket to install the distribution`() {
        val installer = repositoryRoot().resolve("scripts").resolve("install-pipelinek.sh")
        assertTrue(Files.isRegularFile(installer), "the installer is not where the build says it is: $installer")

        val text = Files.readString(installer)
        assertFalse(text.contains("docker.sock"), "the installer names a Docker socket")
        assertFalse(
            text.lineSequence().any { it.trim().startsWith("docker ") },
            "the installer invokes the Docker command line",
        )
    }

    /**
     * The entries of the distribution, with the directory of the version taken off.
     *
     * The name of the directory is the version the distribution was built as, and
     * the entries are read WITHOUT it: what is under trial is what the
     * installation carries, and the name of the folder it was unpacked in is
     * not part of that.
     */
    private fun entriesOfDistribution(): List<String> {
        val prefix = "pipelinek-${version()}/"
        val entries = ZipFile(distribution().toFile()).use { archive ->
            archive.entries().toList().map { it.name }
        }
        return entries.map { it.removePrefix(prefix) }
    }

    /** The ZIP the installer publishes, as the build handed it over. */
    private fun distribution(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.distribution.zip")) {
            "the lane was run without the distribution: the build has to hand over the ZIP it measured"
        },
    )

    /** The version the distribution was built as. */
    private fun version(): String = requireNotNull(System.getProperty("pipelinek.distribution.version")) {
        "the lane was run without the version of the distribution"
    }

    /** The root of the repository, as the build handed it over. */
    private fun repositoryRoot(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.repository.root")) {
            "the lane was run without the repository it measures"
        },
    )
}
