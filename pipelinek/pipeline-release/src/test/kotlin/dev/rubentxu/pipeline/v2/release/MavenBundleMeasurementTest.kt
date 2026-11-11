package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What a bundle holds, and which of its files a consumer resolves.
 *
 * The measurement is over a real directory: the digests come from the bytes
 * on disk, and a row that says "this file is an artifact" says it about a
 * file that is there.
 */
class MavenBundleMeasurementTest {

    @TempDir
    lateinit var bundle: Path

    private val pomBytes = "the pom of the contract".toByteArray()
    private val jarBytes = "the jar of the classes".toByteArray()
    private val moduleBytes = "the module metadata of the variants".toByteArray()

    @Test
    fun `the Gradle module metadata is a kind of its own`() {
        assertEquals(
            MavenArtifactKind.MODULE,
            MavenBundleMeasurement.kindOf(
                "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.module",
            ),
        )
    }

    @Test
    fun `the other kinds a repository holds keep their kind`() {
        assertEquals(
            MavenArtifactKind.POM,
            MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0.pom"),
        )
        assertEquals(
            MavenArtifactKind.JAR,
            MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0.jar"),
        )
        assertEquals(
            MavenArtifactKind.SOURCES,
            MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0-sources.jar"),
        )
        assertEquals(
            MavenArtifactKind.JAVADOC,
            MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0-javadoc.jar"),
        )
    }

    @Test
    fun `a file of no recognised name is not an artifact`() {
        assertNull(MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0.jar.asc"))
        assertNull(MavenBundleMeasurement.kindOf("a/b/0.96.0/b-0.96.0.pom.sha1"))
        assertNull(MavenBundleMeasurement.kindOf("a/b/0.96.0/maven-metadata.xml"))
    }

    @Test
    fun `a bundle declares the module metadata with the digest of its bytes`() {
        write("dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom", pomBytes)
        write("dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar", jarBytes)
        write(
            "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.module",
            moduleBytes,
        )

        val measured = MavenBundleMeasurement.measure(bundle)

        val declared = measured.artifacts.single {
            it.path.endsWith("pipelinek-domain-0.96.0.module")
        }
        assertEquals(MavenArtifactKind.MODULE, declared.kind)
        assertEquals(Sha256.ofBytes(moduleBytes), declared.sha256)
        assertEquals(3, measured.artifacts.size, "the bundle holds three artifacts")
    }

    @Test
    fun `a bundle that carries no module metadata declares no module metadata`() {
        write("dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom", pomBytes)
        write("dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar", jarBytes)

        val measured = MavenBundleMeasurement.measure(bundle)

        assertTrue(
            measured.artifacts.none { it.kind == MavenArtifactKind.MODULE },
            "the bundle declares no module metadata: ${measured.artifacts}",
        )
    }

    @Test
    fun `the module metadata is inside the digest of the whole bundle`() {
        write("dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom", pomBytes)
        write(
            "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.module",
            moduleBytes,
        )
        val first = MavenBundleMeasurement.measure(bundle)

        write(
            "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.module",
            "the module metadata of other variants".toByteArray(),
        )
        val second = MavenBundleMeasurement.measure(bundle)

        assertTrue(
            first.sha256 != second.sha256,
            "the digest of the bundle covers the module metadata",
        )
    }

    /** Write [bytes] at [relative] under the bundle. */
    private fun write(relative: String, bytes: ByteArray) {
        val path = bundle.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}
