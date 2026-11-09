package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
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

    /**
     * The coordinates the SDK is published under. They are the CURRENT ones
     * (`dev.rubentxu.pipelinek:pipelinek-*`), and not a fixture of an older
     * layout: the BOM JSON used to carry the names of the releases BEFORE the
     * rename written in it, and a fixture that used those same names could not
     * have told the two apart.
     */
    private val coordinates = listOf("dev.rubentxu.pipelinek" to "pipelinek-domain")
    private val bomCoordinate = "dev.rubentxu.pipelinek" to "pipelinek-sdk-bom"

    /** A repository with the layout a Maven repository has, with one version. */
    private fun repository(): Path {
        val base = directory.resolve("sdk-repo")
        val versionDirectory = base.resolve("dev/rubentxu/pipelinek/pipelinek-domain/$version")
        Files.createDirectories(versionDirectory)
        Files.writeString(versionDirectory.resolve("pipelinek-domain-$version.pom"), "<project/>")
        Files.writeString(versionDirectory.resolve("pipelinek-domain-$version.jar"), "the bytes of a jar")
        return base
    }

    @Test
    fun `two builds of the same repository produce the same digest`() {
        val repository = repository()

        val first = SdkZipBuilder.build(
            repository, version, bomCoordinate, coordinates, directory.resolve("first")
        )
        val second = SdkZipBuilder.build(
            repository, version, bomCoordinate, coordinates, directory.resolve("second")
        )

        assertEquals(
            Sha256.ofFile(first.first),
            Sha256.ofFile(second.first),
            "the SDK ZIP is not a function of what it contains",
        )
        assertEquals(
            Sha256.ofFile(first.second),
            Sha256.ofFile(second.second),
            "the BOM JSON is not a function of what it contains",
        )
    }

    @Test
    fun `the BOM JSON declares the coordinate the BOM is published under`() {
        val built = SdkZipBuilder.build(
            repository(), version, bomCoordinate, coordinates, directory.resolve("bom")
        )

        val document = Files.readString(built.second)
        val declared = buildString {
            append("  \"bom\": {\n")
            append("    \"group\": \"${bomCoordinate.first}\",\n")
            append("    \"artifact\": \"${bomCoordinate.second}\",\n")
            append("    \"version\": \"$version\"\n")
            append("  },")
        }
        assertTrue(
            document.contains(declared),
            "the BOM JSON does not declare the coordinate the BOM is published under; " +
                "it declares:\n$document",
        )
    }

    @Test
    fun `every entry of the SDK ZIP carries the fixed time`() {
        val built = SdkZipBuilder.build(
            repository(), version, bomCoordinate, coordinates, directory.resolve("out")
        )

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
