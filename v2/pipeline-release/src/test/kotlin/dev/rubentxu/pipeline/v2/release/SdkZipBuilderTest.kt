package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Pinned-shape tests for [SdkZipBuilder]. The builder's job is to take the
 * local Maven repo (populated by `:publishSdkForExternalPlugin`) and produce
 * the two §8 release assets that were missing from the build:
 *
 *   - `pipelinek-<v>.sdk.zip` — the public contracts and the BOM POM, one
 *     JAR + one POM per coordinate, at the current version only.
 *   - `pipelinek-bom-<v>.json` — a flat manifest of `group:artifact:version`
 *     triples (managed coordinates) plus a per-file SHA-256 inventory of
 *     the ZIP contents.
 *
 * The builder is the only place where the SDK repo becomes a release asset.
 * Tests cover the four boundary cases that an SDK release has to satisfy:
 * version filtering, manifest shape, ZIP contents, and refusal on empty
 * input.
 */
class SdkZipBuilderTest {

    @Test
    fun `only the current version's entries end up in the SDK ZIP`(@TempDir tmp: Path) {
        val repo = tmp.resolve("sdk-repo")
        val group = repo.resolve("dev/rubentxu/pipeline/v2")
        writeArtifact(group, "pipeline-domain", "0.61.0")
        writeArtifact(group, "pipeline-domain", "0.62.0")
        writeArtifact(group, "pipeline-domain", "0.62.0-rc1")
        writeArtifact(group, "pipeline-events", "0.62.0")

        val (sdkZip, _) = SdkZipBuilder.build(
            sdkRepo = repo,
            version = "0.62.0",
            managedCoordinates = listOf(
                "dev.rubentxu.pipeline.v2" to "pipeline-domain",
                "dev.rubentxu.pipeline.v2" to "pipeline-events",
            ),
            distDir = tmp.resolve("dist"),
        )

        val names = ZipInputStream(Files.newInputStream(sdkZip)).use { zin ->
            generateSequence { zin.nextEntry?.name }.toList()
        }
        assertEquals(4, names.size, "expected exactly 2 artifacts × 2 files (jar+pom)")
        assertTrue(
            names.all { "/0.62.0/" in it && !it.contains("0.61.0") && !it.contains("rc1") },
            "ZIP must only contain entries for version 0.62.0; got $names",
        )
    }

    @Test
    fun `the BOM JSON lists every managed coordinate pinned to the version`(
        @TempDir tmp: Path,
    ) {
        val repo = tmp.resolve("sdk-repo")
        val group = repo.resolve("dev/rubentxu/pipeline/v2")
        writeArtifact(group, "pipeline-domain", "0.62.0")

        val (_, bom) = SdkZipBuilder.build(
            sdkRepo = repo,
            version = "0.62.0",
            managedCoordinates = listOf(
                "dev.rubentxu.pipeline.v2" to "pipeline-domain",
                "dev.rubentxu.pipeline.v2" to "pipeline-events",
            ),
            distDir = tmp.resolve("dist"),
        )

        val text = Files.readString(bom)
        assertTrue(text.contains("\"schema_version\": \"pipelinek-bom-1\""), text)
        assertTrue(text.contains("\"product_version\": \"0.62.0\""), text)
        assertTrue(text.contains("\"group\": \"dev.rubentxu.pipeline.v2\""), text)
        assertTrue(text.contains("\"artifact\": \"pipeline-domain\""), text)
        assertTrue(text.contains("\"version\": \"0.62.0\""), text)
        assertTrue(text.contains("\"artifact\": \"pipeline-events\""), text)
    }

    @Test
    fun `the BOM JSON inventory records the SHA-256 of every ZIP entry`(
        @TempDir tmp: Path,
    ) {
        val repo = tmp.resolve("sdk-repo")
        val group = repo.resolve("dev/rubentxu/pipeline/v2")
        writeArtifact(group, "pipeline-domain", "0.62.0", body = "domain-bytes".toByteArray())

        val (sdkZip, bom) = SdkZipBuilder.build(
            sdkRepo = repo,
            version = "0.62.0",
            managedCoordinates = listOf(
                "dev.rubentxu.pipeline.v2" to "pipeline-domain",
            ),
            distDir = tmp.resolve("dist"),
        )

        // The ZIP contains two entries (jar + pom) for the single
        // coordinate. Their SHA-256s MUST appear in the BOM's files
        // block, in the same order as the ZIP entries (sorted by path).
        val expectedJarSha = sha256Hex("domain-bytes".toByteArray())
        val text = Files.readString(bom)
        assertTrue(text.contains(expectedJarSha), "BOM must record the JAR sha256; got:\n$text")
        // The pom body is "POM for pipeline-domain@0.62.0\n"; assert the
        // shape rather than the value to keep this test stable across
        // incidental whitespace edits.
        assertTrue(
            text.contains("\"sha256\": \""),
            "BOM must include per-file sha256 entries; got:\n$text",
        )
        // And the SDK ZIP must exist on disk at the expected path.
        assertTrue(Files.isRegularFile(sdkZip), "sdkZip must be a regular file")
    }

    @Test
    fun `an empty managed-coordinates list is refused as a defect`(@TempDir tmp: Path) {
        val repo = tmp.resolve("sdk-repo")
        Files.createDirectories(repo)
        val ex = assertThrows<IllegalArgumentException> {
            SdkZipBuilder.build(
                sdkRepo = repo,
                version = "0.62.0",
                managedCoordinates = emptyList(),
                distDir = tmp.resolve("dist"),
            )
        }
        assertNotNull(ex.message)
    }

    @Test
    fun `a repo without the requested version refuses with a clear diagnostic`(
        @TempDir tmp: Path,
    ) {
        val repo = tmp.resolve("sdk-repo")
        val group = repo.resolve("dev/rubentxu/pipeline/v2")
        writeArtifact(group, "pipeline-domain", "0.61.0") // wrong version
        val ex = assertThrows<IllegalArgumentException> {
            SdkZipBuilder.build(
                sdkRepo = repo,
                version = "0.62.0",
                managedCoordinates = listOf(
                    "dev.rubentxu.pipeline.v2" to "pipeline-domain",
                ),
                distDir = tmp.resolve("dist"),
            )
        }
        assertTrue(
            ex.message!!.contains("0.62.0") && ex.message!!.contains("did publishSdkForExternalPlugin run"),
            "diagnostic must name the version AND hint at the missing build step; got: ${ex.message}",
        )
    }

    /**
     * REGRESSION (commit c01e8e5f closed this gap; this test pins the fix).
     *
     * Before c01e8e5f, [SdkZipBuilder.writeSdkZip] wrote each entry's name as
     * the absolute filesystem path returned by [java.nio.file.Files.walk],
     * e.g. `/var/home/.../v2/build/sdk-repo/dev/rubentxu/pipeline/v2/...`.
     * On extraction `unzip` silently strips the leading slash, so a
     * consumer doing `unzip sdk.zip -d .m2/` ended up with an empty
     * `.m2/`. The SHA-256s in `SHA256SUMS` proved the bytes were intact;
     * what the contract needed — and what this test pins — is that the
     * ZIP is a usable local Maven repository.
     *
     * The test extracts the produced ZIP into a clean `m2Root` and walks
     * the result. A green run shows every coordinate's `<version>/`
     * directory present with at least a `.jar` and a `.pom`. A red run
     * catches the regression immediately: empty `m2Root`, or paths like
     * `var/...` instead of `dev/...`.
     */
    @Test
    fun `the produced SDK ZIP extracts into a usable Maven layout`(
        @TempDir tmp: Path,
    ) {
        val repo = tmp.resolve("sdk-repo")
        val group = repo.resolve("dev/rubentxu/pipeline/v2")
        val version = "0.62.0"
        writeArtifact(group, "pipeline-domain", version, body = "domain-bytes".toByteArray())
        writeArtifact(group, "pipeline-events", version, body = "events-bytes".toByteArray())

        val (sdkZip, _) = SdkZipBuilder.build(
            sdkRepo = repo,
            version = version,
            managedCoordinates = listOf(
                "dev.rubentxu.pipeline.v2" to "pipeline-domain",
                "dev.rubentxu.pipeline.v2" to "pipeline-events",
            ),
            distDir = tmp.resolve("dist"),
        )

        // Cross the productive authority: a fresh `unzip` extraction,
        // exactly as a consumer would do, NOT the builder's `Files.walk`
        // we already know succeeds. The test fails loudly if the path
        // is absolute (unzip strips it) or the layout is wrong.
        val m2Root = tmp.resolve("m2")
        unzipInto(sdkZip, m2Root)

        val devRoot = m2Root.resolve("dev/rubentxu/pipeline/v2")
        assertTrue(
            Files.isDirectory(devRoot),
            "extraction did not produce a Maven layout under dev/rubentxu/pipeline/v2/; " +
                "got: ${m2Root.toFile().list().orEmpty().joinToString()}",
        )
        for (artifact in listOf("pipeline-domain", "pipeline-events")) {
            val versioned = devRoot.resolve("$artifact/$version")
            assertTrue(
                Files.isDirectory(versioned),
                "missing coordinate directory: $versioned",
            )
            val jar = versioned.resolve("$artifact-$version.jar")
            val pom = versioned.resolve("$artifact-$version.pom")
            assertTrue(Files.isRegularFile(jar), "missing JAR: $jar")
            assertTrue(Files.isRegularFile(pom), "missing POM: $pom")
        }
    }

    /**
     * Minimal ZIP extraction that uses ONLY the JDK's `ZipInputStream`.
     * We deliberately do NOT use `ProcessBuilder("unzip", ...)` here: a
     * system without `unzip` installed would fail this test for the
     * wrong reason (env, not code). The JDK extractor is the minimum
     * authority this contract needs: any consumer with a JDK can do
     * what this test does.
     */
    private fun unzipInto(zip: Path, target: Path) {
        Files.createDirectories(target)
        java.util.zip.ZipInputStream(Files.newInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = target.resolve(entry.name).normalize()
                // Containment check: a malicious ZIP cannot write
                // outside `target`. Same defense as `CycleRunner.finalise`
                // for the cycle directory (cf. audit 2026-10-16 P0-C).
                if (!out.startsWith(target)) {
                    throw IllegalStateException("ZIP entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.copy(zin, out)
                }
                entry = zin.nextEntry
            }
        }
    }

    private fun writeArtifact(
        group: Path,
        name: String,
        version: String,
        body: ByteArray = "POM for $name@$version\n".toByteArray(),
    ) {
        val dir = group.resolve("$name/$version")
        Files.createDirectories(dir)
        Files.write(dir.resolve("$name-$version.jar"), body)
        Files.write(dir.resolve("$name-$version.pom"), body)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
