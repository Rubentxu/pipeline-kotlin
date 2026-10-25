package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A compilation cache key is a promise that two compilations with the same
 * key are interchangeable. Every test here is an input change that must NOT
 * keep that promise silently: different bytes under the same path, a
 * different compiler, different options, a different order. And the two
 * refusals: an unreadable entry must not produce a key a cache may trust,
 * and a `v2` key must never be readable as a `v3`.
 */
class VerifiedCompilationIdentityTest {

    private val source = "source:" + "0123456789abcdef".repeat(4)
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    private fun entry(path: String = "/jars/lib.jar", sha: String? = digestA, size: Long? = 10L) =
        ClasspathEntry(path, sha, size)

    private fun verify(
        entries: List<ClasspathEntry> = listOf(entry()),
        sourceDigest: String = source,
        compiler: String = "kotlin-2.4.10",
        adapter: String = "host-1",
        flags: List<String> = listOf("-Xreturn-value-checker=check"),
    ): CacheIdentity = verifyCompilationIdentity(sourceDigest, compiler, adapter, flags, entries)

    private fun keyOf(entries: List<ClasspathEntry>): CacheKey = (verify(entries) as CacheIdentity.Verified).key

    @Test
    fun `same inputs reproduce the same key`() {
        assertEquals(keyOf(listOf(entry())), keyOf(listOf(entry())))
        assertEquals(CacheKey.V3, keyOf(listOf(entry())).version)
    }

    @Test
    fun `different bytes under the same path are a different compilation`() {
        val original = listOf(entry(path = "/jars/lib.jar", sha = digestA, size = 100L))
        val replaced = listOf(entry(path = "/jars/lib.jar", sha = digestB, size = 100L))

        assertNotEquals(keyOf(original), keyOf(replaced))
    }

    @Test
    fun `source, compiler, adapter and options are each their own dimension`() {
        val base = keyOf(listOf(entry()))
        assertNotEquals(base, (verify(sourceDigest = source.reversed()) as CacheIdentity.Verified).key)
        assertNotEquals(base, (verify(compiler = "kotlin-2.4.11") as CacheIdentity.Verified).key)
        assertNotEquals(base, (verify(adapter = "host-2") as CacheIdentity.Verified).key)
        assertNotEquals(base, (verify(flags = listOf("-Xother")) as CacheIdentity.Verified).key)
    }

    @Test
    fun `classpath order is preserved because order can decide a winner`() {
        val first = listOf(entry(path = "/jars/a.jar", sha = digestA), entry(path = "/jars/b.jar", sha = digestB))
        val swapped = listOf(entry(path = "/jars/a.jar", sha = digestB), entry(path = "/jars/b.jar", sha = digestA))

        assertNotEquals(keyOf(first), keyOf(swapped))
    }

    @Test
    fun `a repeated entry is a fact about resolution, not noise to collapse`() {
        val once = listOf(entry(path = "/jars/lib.jar", sha = digestA, size = 10L))
        val twice = listOf(
            entry(path = "/jars/lib.jar", sha = digestA, size = 10L),
            entry(path = "/other/lib.jar", sha = digestA, size = 10L),
        )

        assertNotEquals(keyOf(once), keyOf(twice))
    }

    @Test
    fun `an unreadable entry makes the identity unavailable, never verified`() {
        val identity = verify(entries = listOf(entry(sha = null, size = null)))

        assertTrue(identity is CacheIdentity.Unavailable)
        assertTrue(identity.key.version == CacheKey.V3)
        assertTrue((identity as CacheIdentity.Unavailable).reason.contains("/jars/lib.jar"))
    }

    @Test
    fun `an unavailable key never equals a verified key of the same readable dimensions`() {
        val unreadable = verify(entries = listOf(entry(sha = null, size = null))).key
        val verified = keyOf(listOf(entry()))

        assertNotEquals(unreadable, verified)
    }

    @Test
    fun `two unavailable identities with different unreadable paths do not share a key`() {
        val first = verify(entries = listOf(entry(path = "/jars/one.jar", sha = null, size = null))).key
        val second = verify(entries = listOf(entry(path = "/jars/two.jar", sha = null, size = null))).key

        assertNotEquals(first, second)
    }

    @Test
    fun `v3 does not reinterpret v2 keys`() {
        val manifest = CompilationInputManifest(
            sourceDigest = source,
            compilerIdentity = "kotlin-2.4.10",
            adapterIdentity = "host-1",
            compilerFlags = listOf("-Xreturn-value-checker=check"),
            orderedClasspath = listOf(ClasspathArtifact(digestA, 10L)),
        )
        val v3 = CacheKey.Companion.v3.compute(manifest)
        val v2 = CacheKey.Companion.v2.compute(
            CompilationIdentity(
                pipelineSource = source,
                kotlinVersion = "kotlin-2.4.10",
                hostVersion = "host-1",
                approvedClasspath = "/jars/lib.jar",
                compilerOptions = listOf("-Xreturn-value-checker=check"),
            ),
        )

        assertNotEquals(v2.version, v3.version)
        assertNotEquals(v2.value, v3.value)
    }
}
