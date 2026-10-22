package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The identity of a compilation: what makes two compilations interchangeable.
 *
 * **The guarantee.** A cache key decides whether one compiled artefact may be reused for
 * another compilation. If two DIFFERENT compilations share a key, a pipeline runs code that
 * was not compiled from the script it was given; if two IDENTICAL compilations get different
 * keys, the cache never hits and pays for nothing. Both directions are silent, which is why
 * the rule is worth a table.
 *
 * **The two recipes, and why the older one is pinned as it is.**
 *
 * The first recipe joins its parts with `|` and hashes the result. That is not injective on
 * the assignment: `("a", "b", "c|d", "")` and `("a", "b", "c", "d|")` produce the same
 * pre-image, and the script source is arbitrary text, so a `|` inside it is a reachable
 * collision.
 *
 * That recipe is NOT "fixed", and this test says so out loud: a value already written under
 * that label is only interpretable if the recipe that produced it still reproduces, so
 * reframing it would silently redefine every digest ever emitted. The fix arrived as a new
 * version label, and these rows pin both halves of that story: the old recipe keeps its
 * meaning, and the new one separates what the old one collided.
 *
 * The rows about the framing property of the new recipe itself live in the test named after
 * it; what is here is what the two recipes mean to a reader of an already-written digest.
 */
class CacheKeyTest {

    @Test
    fun `v1 keeps its unframed pre-image, because a v1 digest already written must keep its meaning`() {
        // The documented collision. It is pinned ON PURPOSE: it is the constraint that says
        // "do not reframe v1", not an endorsement of the collision.
        assertEquals(
            CacheKey.sha256Hex("a", "b", "c|d", ""),
            CacheKey.sha256Hex("a", "b", "c", "d|"),
        )
        assertEquals(
            CacheKey.Companion.v1.compute("a", "b", "c|d", "").value,
            CacheKey.Companion.v1.compute("a", "b", "c", "d|").value,
        )
    }

    @Test
    fun `v2 separates the two assignments that v1 collides`() {
        val first = CacheKey.Companion.v2.compute(
            CompilationIdentity(
                pipelineSource = "a",
                kotlinVersion = "b",
                hostVersion = "c|d",
                approvedClasspath = "",
                compilerOptions = emptyList(),
            ),
        )
        val second = CacheKey.Companion.v2.compute(
            CompilationIdentity(
                pipelineSource = "a",
                kotlinVersion = "b",
                hostVersion = "c",
                approvedClasspath = "d|",
                compilerOptions = emptyList(),
            ),
        )

        assertNotEquals(first.value, second.value)
    }

    @Test
    fun `the order of the compiler options is part of the identity`() {
        // Each option is its own framed part, so both the SET and the ORDER are part of the
        // identity. Joining them into one string first would reintroduce the ambiguity
        // framing was added to remove.
        val ordered = CacheKey.Companion.v2.compute(identity(compilerOptions = listOf("-Xjvm-default", "-opt-in=a")))
        val reordered = CacheKey.Companion.v2.compute(identity(compilerOptions = listOf("-opt-in=a", "-Xjvm-default")))

        assertNotEquals(ordered.value, reordered.value)
    }

    @Test
    fun `the version label says which algorithm produced the value`() {
        val v1Key = CacheKey.Companion.v1.compute("a", "b", "c", "d")
        val v2Key = CacheKey.Companion.v2.compute(identity())

        assertEquals(CacheKey.V1, v1Key.version)
        assertEquals(CacheKey.V2, v2Key.version)
        // The label alone does not separate them: the RECIPES do. Two keys with different
        // labels and the same digest would be two names for one artefact.
        assertNotEquals(v1Key.value, v2Key.value)
    }

    private fun identity(compilerOptions: List<String> = emptyList()) = CompilationIdentity(
        pipelineSource = "pipeline { }",
        kotlinVersion = "2.4.10",
        hostVersion = "0.53.0",
        approvedClasspath = "a.jar:b.jar",
        compilerOptions = compilerOptions,
    )
}
