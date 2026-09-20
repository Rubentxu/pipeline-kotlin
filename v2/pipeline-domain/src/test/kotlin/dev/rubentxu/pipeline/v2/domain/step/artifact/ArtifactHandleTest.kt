package dev.rubentxu.pipeline.v2.domain.step.artifact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the artifact bridge ADTs and SPI.
 * No filesystem; no production wiring.
 */
class ArtifactHandleTest {

    private fun file(relPath: String, sha: String = "a".repeat(64), size: Long = 100): ArchivedFileEntry =
        ArchivedFileEntry(
            relPath = relPath,
            sha256 = sha,
            sizeBytes = size,
            absolutePath = "/run/artifacts/$relPath",
        )

    // ---------- ArtifactHandle invariants ----------

    @Test
    fun `ArtifactHandle rejects empty name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactHandle(name = "", files = listOf(file("a.jar")))
        }
    }

    @Test
    fun `ArtifactHandle rejects blank name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactHandle(name = "   ", files = listOf(file("a.jar")))
        }
    }

    @Test
    fun `ArtifactHandle accepts empty files list when allowEmptyArchive semantics apply`() {
        val h = ArtifactHandle(name = "empty-artifact", files = emptyList())
        assertEquals(0, h.files.size)
        assertNull(h.primaryFile())
    }

    @Test
    fun `ArtifactHandle primaryFile returns first file`() {
        val h = ArtifactHandle(
            name = "app",
            files = listOf(file("a.jar"), file("b.jar")),
        )
        assertEquals("a.jar", h.primaryFile()?.relPath)
    }

    @Test
    fun `ArtifactHandle aggregateSha256 is deterministic and order-independent`() {
        val a = ArtifactHandle(
            name = "app",
            files = listOf(file("b.jar", sha = "b".repeat(64)), file("a.jar", sha = "a".repeat(64))),
        )
        val b = ArtifactHandle(
            name = "app",
            files = listOf(file("a.jar", sha = "a".repeat(64)), file("b.jar", sha = "b".repeat(64))),
        )
        assertEquals(a.aggregateSha256(), b.aggregateSha256())
    }

    @Test
    fun `ArtifactHandle aggregateSha256 is a 64-hex-char SHA-256`() {
        val h = ArtifactHandle(name = "x", files = listOf(file("x.jar")))
        val agg = h.aggregateSha256()
        assertEquals(64, agg.length)
        assertTrue(agg.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---------- ArchivedFileEntry invariants ----------

    @Test
    fun `ArchivedFileEntry rejects empty relPath`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedFileEntry(relPath = "", sha256 = "a".repeat(64), sizeBytes = 0, absolutePath = "/x")
        }
    }

    @Test
    fun `ArchivedFileEntry rejects sha256 of wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedFileEntry(relPath = "x", sha256 = "short", sizeBytes = 0, absolutePath = "/x")
        }
    }

    @Test
    fun `ArchivedFileEntry rejects negative sizeBytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedFileEntry(relPath = "x", sha256 = "a".repeat(64), sizeBytes = -1, absolutePath = "/x")
        }
    }

    @Test
    fun `ArchivedFileEntry rejects empty absolutePath`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedFileEntry(relPath = "x", sha256 = "a".repeat(64), sizeBytes = 0, absolutePath = "")
        }
    }

    // ---------- ArtifactName / ArtifactQueryInput invariants ----------

    @Test
    fun `ArtifactName rejects empty and blank`() {
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("   ") }
    }

    @Test
    fun `ArtifactName rejects newlines`() {
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("a\rb") }
    }

    @Test
    fun `ArtifactQueryInput rejects empty and blank`() {
        assertThrows(IllegalArgumentException::class.java) { ArtifactQueryInput("") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactQueryInput("   ") }
    }
}

/**
 * In-memory reference implementation of [ArtifactIndexCapability]
 * used by the tests to exercise the SPI without depending on the
 * production adapter. The production adapter (in
 * `pipeline-application`) lives alongside `core.archiveArtifacts` and
 * has the same contract.
 */
class InMemoryArtifactIndex : ArtifactIndexCapability {
    private val byName = LinkedHashMap<String, ArtifactHandle>()

    override fun record(handle: ArtifactHandle) {
        if (byName.containsKey(handle.name)) {
            throw DuplicateArtifactNameException(handle.name)
        }
        byName[handle.name] = handle
    }

    override fun query(name: String): ArtifactHandle? = byName[name]

    override fun all(): List<ArtifactHandle> = byName.values.toList().sortedBy { it.name }
}

/**
 * SPI-level tests: record / query / all + duplicate detection.
 */
class ArtifactIndexCapabilityTest {

    private fun file(relPath: String, sha: String = "a".repeat(64)): ArchivedFileEntry =
        ArchivedFileEntry(relPath, sha, 1, "/run/artifacts/$relPath")

    @Test
    fun `record and query round-trip`() {
        val idx = InMemoryArtifactIndex()
        val handle = ArtifactHandle(name = "app", files = listOf(file("a.jar")))
        idx.record(handle)
        assertEquals(handle, idx.query("app"))
        assertNull(idx.query("missing"))
    }

    @Test
    fun `record rejects duplicate name`() {
        val idx = InMemoryArtifactIndex()
        idx.record(ArtifactHandle(name = "app", files = listOf(file("a.jar"))))
        assertThrows(DuplicateArtifactNameException::class.java) {
            idx.record(ArtifactHandle(name = "app", files = listOf(file("b.jar"))))
        }
    }

    @Test
    fun `all returns handles sorted by name for determinism`() {
        val idx = InMemoryArtifactIndex()
        idx.record(ArtifactHandle(name = "zeta", files = emptyList()))
        idx.record(ArtifactHandle(name = "alpha", files = emptyList()))
        idx.record(ArtifactHandle(name = "mu", files = emptyList()))
        val names = idx.all().map { it.name }
        assertEquals(listOf("alpha", "mu", "zeta"), names)
    }

    @Test
    fun `query is null before record and after remove-by-overwrite-fails`() {
        val idx = InMemoryArtifactIndex()
        assertNull(idx.query("never-recorded"))
        idx.record(ArtifactHandle(name = "x", files = emptyList()))
        // Duplicate detection means there is no "remove"; the entry stays.
        assertNotNull(idx.query("x"))
    }
}
