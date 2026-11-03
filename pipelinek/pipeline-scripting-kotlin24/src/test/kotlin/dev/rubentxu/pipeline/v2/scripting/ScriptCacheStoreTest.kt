package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The rules that decide where the compiled-script cache lives and whether it
 * may hold artifacts.
 *
 * Two guarantees are under trial and BOTH are pure: the location is a
 * function of an environment map, and the privacy of a directory is a
 * function of facts about it. Nothing here touches a filesystem or the real
 * environment, so every row is a decision a reader can check by reading.
 *
 * The second guarantee is the one that carries the weight. A compiled script
 * carries the literals of the script that produced it, and those literals
 * may be anybody's business, so a store another user can read is a store
 * that is not used at all. The rule therefore says NO on every doubt: a
 * directory whose owner cannot be established is not ours, and a directory
 * whose permissions cannot be read is one that others may reach.
 */
class ScriptCacheStoreTest {

    private fun privateFacts(
        exists: Boolean = true,
        isDirectory: Boolean = true,
        isSymbolicLink: Boolean = false,
        ownedByCurrentUser: Boolean = true,
        readableByOthers: Boolean = false,
    ) = StoreDirectoryFacts(
        exists = exists,
        isDirectory = isDirectory,
        isSymbolicLink = isSymbolicLink,
        ownedByCurrentUser = ownedByCurrentUser,
        readableByOthers = readableByOthers,
    )

    // --- Where the cache lives ------------------------------------------------

    @Test
    fun `the default location is the cache home of the XDG specification`() {
        val root = ScriptCachePolicy.locate(mapOf("XDG_CACHE_HOME" to "/tmp/cache-home"))

        assertEquals(Path.of("/tmp/cache-home/pipelinek/script-cache"), root)
    }

    @Test
    fun `the default location falls back to the cache of the user's home`() {
        val root = ScriptCachePolicy.locate(mapOf("HOME" to "/home/someone"))

        assertEquals(Path.of("/home/someone/.cache/pipelinek/script-cache"), root)
    }

    @Test
    fun `a relative cache home is ignored, because a cache that lands wherever the process started is not a cache the user can find`() {
        val root = ScriptCachePolicy.locate(mapOf("XDG_CACHE_HOME" to "relative/cache", "HOME" to "/home/someone"))

        assertEquals(Path.of("/home/someone/.cache/pipelinek/script-cache"), root)
    }

    @Test
    fun `a home the environment does not carry is answered by the caller`() {
        val root = ScriptCachePolicy.locate(emptyMap(), fallbackHome = "/home/from-the-jvm")

        assertEquals(Path.of("/home/from-the-jvm/.cache/pipelinek/script-cache"), root)
    }

    @Test
    fun `without a home there is no cache, and no invented place to put it`() {
        val root = ScriptCachePolicy.locate(emptyMap())

        assertNull(root)
    }

    @Test
    fun `the explicit location wins over the default`() {
        val root = ScriptCachePolicy.locate(
            mapOf(
                CompiledScriptCache.DIRECTORY_ENV_VAR to "/tmp/explicit",
                "XDG_CACHE_HOME" to "/tmp/cache-home",
            ),
        )

        assertEquals(Path.of("/tmp/explicit"), root)
    }

    @Test
    fun `the word off turns the cache off, in any case`() {
        assertNull(ScriptCachePolicy.locate(mapOf(CompiledScriptCache.DIRECTORY_ENV_VAR to "off")))
        assertNull(ScriptCachePolicy.locate(mapOf(CompiledScriptCache.DIRECTORY_ENV_VAR to "OFF")))
    }

    @Test
    fun `a blank value says nothing, so the default answers`() {
        val root = ScriptCachePolicy.locate(
            mapOf(
                CompiledScriptCache.DIRECTORY_ENV_VAR to "   ",
                "XDG_CACHE_HOME" to "/tmp/cache-home",
            ),
        )

        assertEquals(Path.of("/tmp/cache-home/pipelinek/script-cache"), root)
    }

    // --- Whether the store may be used ----------------------------------------

    @Test
    fun `a directory only its owner may reach may hold artifacts`() {
        assertTrue(ScriptCachePolicy.mayHoldArtifacts(privateFacts()))
    }

    @Test
    fun `a directory that does not exist may not hold artifacts`() {
        assertFalse(ScriptCachePolicy.mayHoldArtifacts(privateFacts(exists = false)))
    }

    @Test
    fun `a link may not hold artifacts, because the permissions examined are not the permissions of the place it points at`() {
        assertFalse(ScriptCachePolicy.mayHoldArtifacts(privateFacts(isSymbolicLink = true)))
    }

    @Test
    fun `something that is not a directory may not hold artifacts`() {
        assertFalse(ScriptCachePolicy.mayHoldArtifacts(privateFacts(isDirectory = false)))
    }

    @Test
    fun `a directory of another user may not hold artifacts`() {
        assertFalse(ScriptCachePolicy.mayHoldArtifacts(privateFacts(ownedByCurrentUser = false)))
    }

    @Test
    fun `a directory the group or the others may reach may not hold artifacts`() {
        assertFalse(ScriptCachePolicy.mayHoldArtifacts(privateFacts(readableByOthers = true)))
    }

    // --- The cause of a refusal -----------------------------------------------

    @Test
    fun `every refusal says which condition failed`() {
        assertEquals(
            "the directory does not exist yet",
            ScriptCachePolicy.reasonFor(privateFacts(exists = false)),
        )
        assertEquals(
            "the path is a symbolic link, not the directory itself",
            ScriptCachePolicy.reasonFor(privateFacts(isSymbolicLink = true)),
        )
        assertEquals(
            "the path is not a directory",
            ScriptCachePolicy.reasonFor(privateFacts(isDirectory = false)),
        )
        assertEquals(
            "the directory belongs to another user",
            ScriptCachePolicy.reasonFor(privateFacts(ownedByCurrentUser = false)),
        )
        assertEquals(
            "the directory can be reached by the group or by others",
            ScriptCachePolicy.reasonFor(privateFacts(readableByOthers = true)),
        )
    }
}
