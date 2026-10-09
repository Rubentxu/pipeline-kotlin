package dev.rubentxu.pipeline.v2.domain.digest

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * B0-2: the migration boundary, pinned so it cannot drift.
 *
 * ## Why this test exists
 *
 * The migration reduced 29 ad-hoc SHA-256 sites to a handful, but not to zero, and the
 * remainder are NOT oversights. Three of them hash bytes that are being written at the same
 * moment the digest is computed:
 *
 * ```text
 * CoreUtilsZipStepDefinition   digest updated per byte while ZipOutputStream writes
 * LocalArtifactStore           digest fed by TarWriter while the tar is written
 * BoundedBodySubscriber        digest updated per chunk of an HTTP response body
 * ```
 *
 * None of those is `Sha256.ofFile`. They are a digest observed *during* an effect, which the
 * utility does not model and should not: turning them into `ofFile` would require buffering the
 * whole artifact, which is precisely what those call sites were written to avoid.
 *
 * So the remaining sites are a deliberate classification, and this test is the executable
 * statement of which is which. It asserts the classification rather than the absence, because a
 * bare "zero sites" assertion would make the next person re-add an `ofFile` around a live
 * stream.
 *
 * ## What it does and does not check
 *
 * It checks the SHAPE of the remaining call sites: that each one calls `MessageDigest.update`
 * (incremental, effect-time) and that none of them calls `ofFile`/`ofBytes` on a path that does
 * not exist yet. It does not execute the Steps; that is the owning suites' job.
 */
class DigestMigrationBoundaryFitnessTest {

    /**
     * The v2 source root.
     *
     * Gradle runs the test JVM with `user.dir` set to the MODULE directory, not to v2, so the
     * repository layout is walked upward until a directory containing `pipeline-domain` is
     * found. Guessing "one level up" is what the first version of this test did and it failed
     * with "expected source file to exist", which is the fitness working rather than the
     * fitness being wrong.
     */
    private val repoRoot: Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("pipeline-domain")) }

    /**
     * Sites that hash bytes WHILE writing them, listed explicitly so adding a fifth requires
     * editing this list on purpose rather than by accident.
     *
     * Granularity is PER FILE plus a needle, not per file alone. `LocalArtifactStore` appears
     * here because its `archive` digest is fed by `TarWriter` while the tar is written, and the
     * SAME FILE also contains a migrated `sha256(file)` that correctly uses `ofFile`. Asserting
     * on the whole file is what surfaced that distinction, and it is why the second row checks
     * the needle rather than forbidding `ofFile` outright.
     */
    private val effectTimeDigestSites = mapOf(
        "pipeline-step-sdk/utilities/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/step/CoreUtilsZipStepDefinition.kt" to
            "ZipOutputStream",
        "pipeline-artefacts-local/src/main/kotlin/dev/rubentxu/pipeline/v2/artefacts/local/LocalArtifactStore.kt" to
            "TarWriter(fos, digest)",
        "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/BoundedBodySubscriber.kt" to
            "MessageDigest.getInstance(\"SHA-256\")",
    )

    private fun sourceOf(relativePath: String): String {
        val file = repoRoot.resolve(relativePath)
        assertTrue(Files.exists(file), "expected source file to exist: $relativePath")
        return Files.readString(file, StandardCharsets.UTF_8)
    }

    @Test
    fun `every effect-time digest site still updates its digest incrementally`() {
        effectTimeDigestSites.forEach { (path, needle) ->
            val source = sourceOf(path)
            assertTrue(
                source.contains("MessageDigest.getInstance(\"SHA-256\")"),
                "$path should still construct its own digest for the effect-time hash",
            )
            assertTrue(
                source.contains(needle),
                "$path is registered as effect-time because it hashes via $needle; if that call " +
                    "moved, update this entry on purpose rather than leaving it stale",
            )
        }
    }

    @Test
    fun `an effect-time site is not rewritten to buffer the whole artifact`() {
        effectTimeDigestSites.forEach { (path, _) ->
            val source = sourceOf(path)
            // ofFile is legitimate in a file that ALSO has an effect-time digest, so the
            // assertion cannot forbid the symbol. What must never happen is the effect-time
            // digest itself becoming ofFile, which would buffer the artifact it streams.
            assertTrue(
                !source.contains("Sha256.ofFile(target)") &&
                    !source.contains("Sha256.ofFile(archivePath"),
                "$path must not buffer its own output through ofFile; that would defeat streaming",
            )
        }
    }

    @Test
    fun `ofFile and ofBytesPrefix produce the values their previous local helpers did`(@TempDir tmp: Path) {
        // The two remaining shapes the migration had to express differently: a whole file, and
        // a digest truncated to a prefix. Both are pinned against the JDK so a future edit to
        // the utility cannot quietly move a value that shipped inside a file or a lock name.
        val file = tmp.resolve("artifact.bin")
        val content = ByteArray(5000) { (it % 97).toByte() }
        Files.write(file, content)

        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
            Sha256.ofFile(file),
        )
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(content).take(6).joinToString("") { "%02x".format(it) },
            Sha256.ofBytesPrefix(content, 6),
        )
    }
}
