package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The cache key is the identity of a compiled artifact, and `CompiledScriptCache.fileNameFor`
 * turns it into a jar FILE NAME, so two identities that share a key are two different
 * compilations competing for one file, and whichever wrote last is the one the next run executes.
 *
 * The pair below is the one named in `CacheKey`'s own KDoc: joined with a single `|`, both spell
 * `a|b|c|d|`. Framing each part by its length is the only thing keeping them apart, and this is
 * the only instrument that notices if that ever goes away.
 *
 * The object is reached as `CacheKey.Companion.v2`, not as the `CacheKey.v2` shorthand: measured
 * 2026-10-19, the shorthand does not resolve from another file in this module while all three
 * explicit forms do. The reason is not established, so this test uses the spelling that compiles
 * rather than asserting a cause.
 */
class CacheKeyV2FramingTest {

    @Test
    fun twoIdentitiesThatCollideWhenJoinedDoNotShareAKey() {
        val barInsideTheHostVersion = identity(hostVersion = "c|d", approvedClasspath = "")
        val barInsideTheClasspath = identity(hostVersion = "c", approvedClasspath = "d|")

        assertNotEquals(
            CacheKey.Companion.v2.compute(barInsideTheHostVersion).value,
            CacheKey.Companion.v2.compute(barInsideTheClasspath).value,
            "a '|' inside one value moved a framing boundary, so both compilations got one key",
        )
    }

    @Test
    fun theSameIdentityAlwaysProducesTheSameKey() {
        // Without this, the test above would also pass if every key were made unique by
        // something other than the input -- a counter, a clock -- and the cache would never hit.
        assertEquals(
            CacheKey.Companion.v2.compute(identity()).value,
            CacheKey.Companion.v2.compute(identity()).value,
        )
    }

    private fun identity(
        pipelineSource: String = "a",
        kotlinVersion: String = "b",
        hostVersion: String = "c",
        approvedClasspath: String = "d",
        compilerOptions: List<String> = emptyList(),
    ) = CompilationIdentity(
        pipelineSource = pipelineSource,
        kotlinVersion = kotlinVersion,
        hostVersion = hostVersion,
        approvedClasspath = approvedClasspath,
        compilerOptions = compilerOptions,
    )
}