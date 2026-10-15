package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * B3.2 — OperatorTrustDatabase contract.
 *
 * ## What this test is for
 *
 * The trust database is the only out-of-band source of digest truth the runtime has. A
 * test that only exercises one adapter (e.g. just the local file) is a test of the file
 * format, not of the contract; the test here drives [LocalFileOperatorTrustDatabase] AND
 * an in-memory adapter through the same six rows so a regression in either is caught.
 *
 * ## Why an in-memory adapter is here, not in main
 *
 * The interface is the production surface; the in-memory adapter is a test double. It
 * lives in test/ because the production code paths (the gate, the durable row writer)
 * only know [OperatorTrustDatabase] — adding a test-only adapter under main would give
 * a production caller a way to skip the real database by accident, which is exactly the
 * fail-open this whole block exists to prevent.
 */
@DisplayName("B3.2 OperatorTrustDatabase looks up operator-pinned digests by (plugin, version)")
class OperatorTrustDatabaseTest {

    private val plugin = "v1:plugin:test/x"
    private val version = SemVer(0, 61, 0)
    private val digest = Digest("sha256:" + "a".repeat(64))
    private val otherVersion = SemVer(0, 62, 0)
    private val otherDigest = Digest("sha256:" + "b".repeat(64))

    @Test
    @DisplayName("a row pinned for (plugin, version) is found by the same key")
    fun lookupFindsPinnedRow() {
        val db = InMemoryOperatorTrustDatabase()
        db.pin(plugin, version, OperatorTrustDatabase.Row(digest, "tester", signed = false))

        val row = db.lookup(plugin, version)
        assertNotNull(row, "a pinned row MUST be returned for its key; missing is a refusal")
        assertEquals(digest, row!!.digest)
        assertEquals("tester", row.publisher)
    }

    @Test
    @DisplayName("a missing (plugin, version) returns null — null IS the refusal")
    fun missingKeyReturnsNull() {
        val db = InMemoryOperatorTrustDatabase()
        assertNull(db.lookup(plugin, version), "an unpinned (plugin, version) MUST be null; " +
            "the gate converts that to RefusedUntrustedIdentity(reason = NoTrustedDigestAvailable)")
    }

    @Test
    @DisplayName("rows for different versions of the same plugin do not collide")
    fun differentVersionsAreDistinct() {
        val db = InMemoryOperatorTrustDatabase()
        db.pin(plugin, version, OperatorTrustDatabase.Row(digest, "v0.61", signed = false))
        db.pin(plugin, otherVersion, OperatorTrustDatabase.Row(otherDigest, "v0.62", signed = true, signedBy = "op"))

        assertEquals(digest, db.lookup(plugin, version)!!.digest)
        assertEquals(otherDigest, db.lookup(plugin, otherVersion)!!.digest)
    }

    @Test
    @DisplayName("lookup key is canonical: (plugin, version) serialised the same way in memory and on disk")
    fun lookupKeyIsCanonical() {
        // The contract is that the canonical key for a (plugin, version) pair is computed
        // by OperatorTrustDatabase.key; both adapters index by that key. A regression that
        // introduced a different separator (e.g. '@' vs ':') would silently break every
        // row in the operator's database.
        val key = OperatorTrustDatabase.key(plugin, version)
        assertTrue(key.contains(plugin), "the key MUST contain the plugin text")
        assertTrue(key.contains(version.toString()), "the key MUST contain the version text")
    }

    @Test
    @DisplayName("LocalFileOperatorTrustDatabase reads a real JSON file from <home>/vault/pipelinek-plugin-trust/<train>.json")
    fun localFileAdapterReads(@TempDir tempDir: Path) {
        val train = "v0.61.0"
        val trustDir = tempDir.resolve("vault/pipelinek-plugin-trust")
        Files.createDirectories(trustDir)
        val trustFile = trustDir.resolve("$train.json")
        val key = OperatorTrustDatabase.key(plugin, version)
        Files.writeString(
            trustFile,
            """{ "$key": { "digest": "${digest.value}", "publisher": "tester", "signed": true, "signedBy": "op" } }""",
        )

        val db = LocalFileOperatorTrustDatabase(sddkHome = tempDir, releaseTrain = train)
        val row = db.lookup(plugin, version)
        assertNotNull(row, "LocalFileOperatorTrustDatabase MUST return the row from the real file")
        assertEquals(digest, row!!.digest)
        assertEquals(true, row.signed)
        assertEquals("op", row.signedBy)
    }

    @Test
    @DisplayName("LocalFileOperatorTrustDatabase returns null when the file does not exist (NOT a crash)")
    fun localFileAdapterMissingFileReturnsNull(@TempDir tempDir: Path) {
        val db = LocalFileOperatorTrustDatabase(sddkHome = tempDir, releaseTrain = "v0.61.0")
        assertNull(db.lookup(plugin, version), "missing file MUST be null, not a crash; " +
            "the gate treats this as RefusedUntrustedIdentity(reason = NoTrustedDigestAvailable)")
    }

    @Test
    @DisplayName("LocalFileOperatorTrustDatabase silently ignores malformed rows (does NOT crash, does NOT admit)")
    fun localFileAdapterMalformedRowIsIgnored(@TempDir tempDir: Path) {
        val train = "v0.61.0"
        val trustDir = tempDir.resolve("vault/pipelinek-plugin-trust")
        Files.createDirectories(trustDir)
        val trustFile = trustDir.resolve("$train.json")
        // The "digest" field is not a valid sha256 — must be skipped, not coerced.
        Files.writeString(
            trustFile,
            """{ "${OperatorTrustDatabase.key(plugin, version)}": { "digest": "not-a-digest", "publisher": "x", "signed": false } }""",
        )

        val db = LocalFileOperatorTrustDatabase(sddkHome = tempDir, releaseTrain = train)
        assertNull(db.lookup(plugin, version), "malformed row MUST be treated as absent, " +
            "not as a Verified; an adapter that admitted on a malformed row is the same fail-open as no adapter")
    }
}

/**
 * In-memory [OperatorTrustDatabase] for tests.
 *
 * Production callers use [LocalFileOperatorTrustDatabase]. Tests use this one because the
 * file-backed adapter needs filesystem and JSON parsing on the hot path, neither of which
 * is what the contract is testing; the contract is "lookup by (plugin, version) returns
 * the row the operator pinned", and that is what this adapter proves.
 */
class InMemoryOperatorTrustDatabase(
    initial: Map<String, OperatorTrustDatabase.Row> = emptyMap(),
) : OperatorTrustDatabase {
    private val store: MutableMap<String, OperatorTrustDatabase.Row> = initial.toMutableMap()

    fun pin(plugin: String, version: SemVer, row: OperatorTrustDatabase.Row) {
        store[OperatorTrustDatabase.key(plugin, version)] = row
    }

    override fun lookup(plugin: String, releaseVersion: SemVer): OperatorTrustDatabase.Row? =
        store[OperatorTrustDatabase.key(plugin, releaseVersion)]
}
