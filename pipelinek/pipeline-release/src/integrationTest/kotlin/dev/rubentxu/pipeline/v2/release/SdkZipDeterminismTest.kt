package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The SDK ZIP is a function of what it contains and not of when it was
 * built.
 *
 * ## The guarantee
 * Two builds of the same repository produce two ZIPs with the same digest.
 * The digest of a published asset is what the handoff names, what the
 * certification covers and what the release's digest authority publishes;
 * a digest that moves with the clock cannot be checked by anybody after the
 * next rebuild.
 *
 * ## The defect this test was written for
 * Measured on this tree, before the fix: two builds of the SAME source
 * produced SDK ZIPs whose 85 entries had identical names and identical
 * bytes, and whose digests differed. The only difference between them was
 * the time stamped on each entry, because the writer left the entry's time
 * unset and the ZIP writer stamps the current time on an entry that does not
 * carry one.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The property is about BYTES on disk: a ZIP is a file, and its digest is
 * the digest of a file. The unit lane of this repository does not touch the
 * filesystem, so it cannot see it.
 *
 * ## What is faked
 * The repository the ZIP is built from is a directory the test owns, with
 * two files in the layout a Maven repository has. Nothing else is faked: the
 * builder, the writer and the ZIP format are the real ones, and the clock is
 * not consulted at all.
 */
class SdkZipDeterminismTest {

    /** The directory the repository and the two ZIPs are written into. */
    @TempDir
    lateinit var directory: Path

    private val version = "0.93.0"
    private val coordinates = listOf("dev.rubentxu.pipeline.v2" to "pipeline-domain")

    /** A repository with the layout a Maven repository has, with one version. */
    private fun repository(): Path {
        val base = directory.resolve("sdk-repo")
        val versionDirectory = base.resolve("dev/rubentxu/pipeline/v2/pipeline-domain/$version")
        Files.createDirectories(versionDirectory)
        Files.writeString(versionDirectory.resolve("pipeline-domain-$version.pom"), "<project/>")
        Files.writeString(versionDirectory.resolve("pipeline-domain-$version.jar"), "the bytes of a jar")
        return base
    }

    @Test
    fun `two builds of the same repository produce the same digest`() {
        val repository = repository()

        val first = SdkZipBuilder.build(repository, version, coordinates, directory.resolve("first"))
        val second = SdkZipBuilder.build(repository, version, coordinates, directory.resolve("second"))

        assertEquals(
            sha256Of(first.first),
            sha256Of(second.first),
            "the SDK ZIP is not a function of what it contains",
        )
        assertEquals(
            sha256Of(first.second),
            sha256Of(second.second),
            "the BOM JSON is not a function of what it contains",
        )
    }

    @Test
    fun `every entry of the SDK ZIP carries the fixed time`() {
        val built = SdkZipBuilder.build(repository(), version, coordinates, directory.resolve("out"))

        ZipFile(built.first.toFile()).use { archive ->
            val entries = archive.entries().toList()
            assertTrue(entries.isNotEmpty(), "the ZIP holds no entry")
            entries.forEach { entry ->
                assertEquals(
                    SdkZipBuilder.ZIP_ENTRY_TIME,
                    entry.time,
                    "the entry ${entry.name} carries the time of the build",
                )
            }
        }
    }
}
