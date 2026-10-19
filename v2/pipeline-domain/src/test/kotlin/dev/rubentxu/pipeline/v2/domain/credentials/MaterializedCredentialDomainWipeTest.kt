package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The wipe of a materialized credential.
 *
 * `MaterializedCredentialDomain.close()` used to wrap every delete in `runCatching { }` and return.
 * The consequence was not a slow leak but a SILENT one wearing a typed shape: `ProjectionResult.close`
 * accumulates a `WipeException` naming every orphan path, and against a `close` that cannot throw that
 * exception was never constructed. The materialised bytes stayed on disk and the pipeline reported
 * success. The tell was in the tree itself — every test double had to OVERRIDE `close` to manufacture
 * a wipe failure, because nothing in production could produce one.
 *
 * The wipe now reports. What it reports is decided by [wipeMaterialization], which takes its
 * filesystem operations as arguments, so it is pinned here without touching a disk — AGENTS.md
 * section 2 forbids that in a unit test, and the rule under test is about which deletes are
 * ATTEMPTED and which failures are RECORDED, not about what the filesystem does.
 *
 * What this does not cover is stated rather than implied: that `Files.deleteIfExists` throws at all
 * when a file is locked is the filesystem's behaviour, not this decision's. Every test double in the
 * tree had to simulate it precisely because nothing real could be provoked here.
 */
class MaterializedCredentialDomainWipeTest {

    private val key = Paths.get("/tmp/pipelinek-ssh-key")
    private val certDir = Paths.get("/tmp/pipelinek-cert-dir")
    private val secretBytes = "correct-horse-battery-staple".toByteArray()

    // --- one delete failing does not abandon the rest -----------------------------

    @Test
    fun `a delete that throws does not stop the deletes after it`() {
        val attempted = mutableListOf<Path>()

        val failures = wipeMaterialization(
            path = certDir,
            handle = null,
            isDirectory = { true },
            childrenOf = { listOf(key, certDir) },
            // `attempted.add(it)`, never `attempted += it`: under this build's Kotlin the `+=`
            // form resolves to `plus` plus an assignment, which does not compile against a `val`.
            // Same operation, no resolution to argue about.
            delete = { entry ->
                attempted.add(entry)
                if (entry == certDir) error("delete refused: $entry")
            },
        )

        // THE assertion, and both halves of it. A directory whose child is locked must still lose
        // its other entries — abandoning the walk half-done is strictly worse than finishing it —
        // AND the locked one must be reported, which is exactly what the old runCatching discarded.
        assertEquals(listOf(key, certDir), attempted)
        assertEquals(1, failures.size)
        // `message` stays a `String?` rather than the return of `assertNotNull`: that overload
        // resolves to the `void` one, so the binding would be `Unit` and `contains` would not
        // compile. The null is asserted on its own line, and nothing is dereferenced blindly.
        val message = failures.single().message
        assertNotNull(message, "a refused delete has to say why it was refused")
        assertTrue(
            message.orEmpty().contains(certDir.toString()),
            "the failure must name the path that survived: $message",
        )
    }

    @Test
    fun `every failed delete is recorded, not only the first`() {
        val failures = wipeMaterialization(
            path = certDir,
            handle = null,
            isDirectory = { true },
            childrenOf = { listOf(key, certDir) },
            delete = { throw IllegalStateException("delete refused: $it") },
        )

        assertEquals(2, failures.size)
        assertTrue(
            failures[0].message!!.contains(key.toString()),
            "the failure must name the path that survived: ${failures[0].message}",
        )
    }

    @Test
    fun `a failing delete does not cost the in-memory secret its wipe`() {
        val handle = SecretHandle.secret(secretBytes)

        val failures = wipeMaterialization(
            path = key,
            handle = handle,
            isDirectory = { false },
            childrenOf = { emptyList() },
            delete = { throw IllegalStateException("delete refused") },
        )

        assertEquals(1, failures.size)
        assertArrayEquals(ByteArray(secretBytes.size), handle.bytesView())
    }

    // --- the order is what makes a nested tree wipeable ---------------------------

    @Test
    fun `a directory is wiped deepest entry first`() {
        val nested = Paths.get("/tmp/pipelinek-cert-dir/nested")
        val attempted = mutableListOf<Path>()

        wipeMaterialization(
            path = certDir,
            handle = null,
            isDirectory = { true },
            childrenOf = { listOf(certDir, key, nested) },
            delete = { entry -> attempted.add(entry) },
        )

        // `Files.walk` yields the directory before its children. A directory cannot be deleted
        // until it is empty, so reverse-sorted order is not an optimisation — it is the only order
        // in which a nested tree wipes at all.
        //
        // Stated as the INVARIANT and not as a total order. `Path`'s natural order compares one
        // name element at a time, so a hard-coded expectation like `listOf(nested, key, certDir)`
        // would be asserting how this fixture's NAMES happen to sort -- and it is wrong on its own
        // terms: `cert-dir/nested` orders before `ssh-key`, so the reverse really does put `key`
        // first. That is not a defect in the wipe; it is a fixture naming its own expectation.
        // What must hold regardless of names is that nothing is attempted before its own contents.
        assertEquals(setOf(certDir, key, nested), attempted.toSet())
        assertTrue(
            attempted.indexOf(nested) < attempted.indexOf(certDir),
            "a nested entry must be deleted before the directory holding it: $attempted",
        )
    }

    @Test
    fun `a plain file is deleted without consulting the walk`() {
        val attempted = mutableListOf<Path>()
        var walked = false

        wipeMaterialization(
            path = key,
            handle = null,
            isDirectory = { false },
            childrenOf = { walked = true; emptyList() },
            delete = { entry -> attempted.add(entry) },
        )

        assertEquals(listOf(key), attempted)
        assertFalse(walked, "a plain file has no children and must not pay for a walk")
    }

    // --- nothing to wipe is not a failure ----------------------------------------

    @Test
    fun `a materialization with neither a path nor a handle wipes cleanly`() {
        val failures = wipeMaterialization(
            path = null,
            handle = null,
            isDirectory = { true },
            childrenOf = { error("must not be consulted") },
            delete = { error("must not be called") },
        )

        assertEquals(emptyList<Throwable>(), failures)
    }

    @Test
    fun `a failing walk is reported rather than propagated`() {
        // Nothing was attempted, but the caller still has to learn that the path survived — a walk
        // that cannot even enumerate is the worst case, not the case to stay quiet about.
        val failures = wipeMaterialization(
            path = certDir,
            handle = null,
            isDirectory = { true },
            childrenOf = { throw IllegalStateException("walk refused") },
            delete = { error("nothing to delete") },
        )

        assertEquals(1, failures.size)
        assertEquals("walk refused", failures.single().message)
    }
}
