package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.script.experimental.api.CompiledScript

/**
 * The compilation identity the cache is addressed by, observed where it is
 * produced.
 *
 * The cache's promise is one sentence: the same key means the same compilation.
 * It is a promise about **bytes** — two compilations are interchangeable only
 * if they saw the same jars — and this file is where that promise is read.
 *
 * The subject is the host's own identity computation, not a restatement of
 * it: [Kotlin24ScriptingHost.cacheIdentityFor] is the production function,
 * called by the production path, and these rows read what it returns. The
 * manifest it returns is the list of classpath **contents** the key was
 * computed from, so a row can say which bytes entered the key and in what
 * order — the thing a digest alone cannot say.
 *
 * Three of the rows read the identity directly; two of them go through
 * [ScriptingHost.compile] and watch what the cache is asked for, because a
 * computation nobody consults is not a promise anybody keeps.
 */
class CompilationIdentityTest {

    @TempDir
    lateinit var tempDir: Path

    private fun host(): Kotlin24ScriptingHost = Kotlin24ScriptingHost()

    /** A real jar, so a compilation with it on the classpath behaves like any other. */
    private fun jarAt(path: Path, content: String): Path {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(ZipEntry("marker.txt"))
            jar.write(content.toByteArray())
            jar.closeEntry()
        }
        return path
    }

    /** The same bytes under another name: a copy, never a second jar. */
    private fun copyOf(source: Path, target: Path): Path {
        Files.createDirectories(target.parent)
        Files.copy(source, target)
        return target
    }

    /**
     * One file of the JVM's own classpath, as the host sees it: canonical, and
     * a regular file, because the host keeps only those.
     */
    private fun aJvmClasspathFile(): File =
        (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it).canonicalFile }
            .first { it.isFile }

    private fun digestOf(path: Path): String = Sha256.ofFile(path)

    @Test
    fun `the identity covers the JVM's own classpath and then the per-call entries`() {
        val jar = jarAt(tempDir.resolve("per-call.jar"), "per-call")

        val identity = host().cacheIdentityFor(SCRIPT, listOf(jar.toFile()))
        val manifest = (identity as CacheIdentity.Verified).manifest

        val jvmIndex = manifest.orderedClasspath.indexOfFirst { it.sha256 == digestOf(aJvmClasspathFile().toPath()) }
        val perCallIndex = manifest.orderedClasspath.indexOfFirst { it.sha256 == digestOf(jar) }

        assertTrue(jvmIndex >= 0, "the JVM's own classpath is not part of the identity")
        assertTrue(perCallIndex >= 0, "the per-call classpath is not part of the identity")
        assertTrue(jvmIndex < perCallIndex, "the per-call entries must come after the JVM's own")
    }

    @Test
    fun `the key follows the bytes of the classpath and not its path`() {
        val host = host()
        val alpha = jarAt(tempDir.resolve("alpha.jar"), "alpha")
        val beta = jarAt(tempDir.resolve("beta.jar"), "beta")
        val moved = copyOf(alpha, tempDir.resolve("moved/alpha.jar"))

        val alphaKey = host.cacheIdentityFor(SCRIPT, listOf(alpha.toFile())).key
        val betaKey = host.cacheIdentityFor(SCRIPT, listOf(beta.toFile())).key
        val movedKey = host.cacheIdentityFor(SCRIPT, listOf(moved.toFile())).key

        assertNotEquals(alphaKey, betaKey, "different bytes must be a different compilation")
        assertEquals(alphaKey, movedKey, "the same bytes under another path must be the same compilation")
    }

    @Test
    fun `a classpath entry whose bytes cannot be read leaves the identity unverified`() {
        val classes = Files.createDirectories(tempDir.resolve("classes"))

        val identity = host().cacheIdentityFor(SCRIPT, listOf(classes.toFile()))

        assertTrue(identity is CacheIdentity.Unavailable)
        assertTrue(
            (identity as CacheIdentity.Unavailable).reason.contains("classes"),
            "the refusal must name the entry it could not read",
        )
    }

    @Test
    fun `the cache is addressed by the key the identity computed`() {
        val cache = RecordingCache()
        val host = Kotlin24ScriptingHost(cache = cache)
        val jar = jarAt(tempDir.resolve("per-call.jar"), "per-call")

        val result = host.compile(ScriptDefinition.inline(SCRIPT, classpath = listOf(jar.toString())))
        val expected = host.cacheIdentityFor(SCRIPT, listOf(jar.toFile())).key

        assertEquals(listOf(expected), cache.loaded, "the cache was addressed by another key")
        assertEquals(listOf(expected), cache.stored, "the artifact was stored under another key")
        assertEquals(expected, result.cacheKey, "the host reported another key")
    }

    @Test
    fun `a classpath whose bytes cannot be read never consults the cache`() {
        val cache = RecordingCache()
        val host = Kotlin24ScriptingHost(cache = cache)
        val classes = Files.createDirectories(tempDir.resolve("classes"))

        val result = host.compile(ScriptDefinition.inline(SCRIPT, classpath = listOf(classes.toString())))

        assertTrue(cache.loaded.isEmpty(), "the cache was consulted without a verified identity")
        assertTrue(cache.stored.isEmpty(), "the cache was written without a verified identity")
        assertTrue(result is ScriptCompilationResult.Success, "the compilation must not fail because the cache did")
    }

    /**
     * A cache that remembers what it was asked for. It never returns an
     * artifact, so every compilation is a cold one; what the rows need is the
     * key the cache was **addressed** by, which is the decision under test.
     */
    private class RecordingCache : CompiledScriptCache {
        val loaded = mutableListOf<CacheKey>()
        val stored = mutableListOf<CacheKey>()

        override fun load(key: CacheKey): CacheLookup {
            loaded += key
            return CacheLookup.Miss(CacheMissReason.ABSENT)
        }

        override fun store(key: CacheKey, script: CompiledScript) {
            stored += key
        }
    }

    private companion object {
        const val SCRIPT = "1 + 1"
    }
}
