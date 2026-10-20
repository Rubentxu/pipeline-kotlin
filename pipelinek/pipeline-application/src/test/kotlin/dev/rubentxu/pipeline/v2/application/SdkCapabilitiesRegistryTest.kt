package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The advertised capability catalogue, observed where it is declared.
 *
 * `pipeline capabilities` is how a machine finds out what this build can do, before it tries to
 * do it. That makes the catalogue a contract, and a contract nobody watches drifts: an id added
 * for a capability that was never delivered, an id dropped without anyone noticing, a state
 * changed to a value that means the opposite of what the reader assumes.
 *
 * This test pins the three things that can be checked without running anything:
 *
 *  - the catalogue is exactly the list below, id by id, with its state and its first publication;
 *  - no id is announced twice, because two entries with one id would leave the reader choosing;
 *  - every id and every version is well-formed, because a malformed id is one no consumer can
 *    match against;
 *  - every announced id is one the build delivers, OR one it deliberately announces as not
 *    delivered; an id announced before it is delivered has to be a deliberate act here rather than
 *    a silent lie in the table.
 *
 * What it cannot say is whether the product really delivers what it announces. That question has
 * an answer in the executable examples, and it is the reason the catalogue states, per state,
 * what "delivered" means.
 */
class SdkCapabilitiesRegistryTest {

    /** The catalogue as a consumer reads it: id, state, first publication. */
    private val expected = listOf(
        Triple("output.follow.v1", SdkCapabilities.CapabilityStatus.PUBLICADA, "v0.49.0-rc1"),
        Triple("events.follow.v1", SdkCapabilities.CapabilityStatus.DEPRECATED, "v0.49.0-rc1"),
        Triple("events.follow.v2", SdkCapabilities.CapabilityStatus.PUBLICADA, "v0.62.0"),
        Triple("runtime.inspect.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.50.0-rc1"),
        Triple("runtime.cancel.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.50.0-rc1"),
        Triple("runtime.recover.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.50.0-rc1"),
        Triple("output.read.digested.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.51.0-rc1"),
        Triple("output.pin.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.51.0-rc1"),
        Triple("output.refusal.retention.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.51.0-rc1"),
        Triple("runtime.structure.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.53.0-rc1"),
        Triple("runtime.operation.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.53.0-rc1"),
        Triple("runtime.eventshape.v1", SdkCapabilities.CapabilityStatus.EXPERIMENTAL, "v0.54.0-rc1"),
    )

    @Test
    @DisplayName("the advertised catalogue is exactly the published list")
    fun `the catalogue is the published list`() {
        val announced = SdkCapabilities.ALL.map { Triple(it.id, it.status, it.firstPublished) }

        assertEquals(expected, announced)
    }

    @Test
    @DisplayName("no id is announced twice")
    fun `no id is announced twice`() {
        val ids = SdkCapabilities.ALL.map { it.id }

        assertEquals(ids.size, ids.distinct().size, "the catalogue announces an id more than once")
    }

    @Test
    @DisplayName("every id names a domain, a capability and a version")
    fun `every id is well formed`() {
        val shape = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9]+)+\\.v[0-9]+$")

        for (entry in SdkCapabilities.ALL) {
            assertTrue(shape.matches(entry.id), "malformed capability id: ${entry.id}")
        }
    }

    @Test
    @DisplayName("every first publication is a version")
    fun `every first publication is a version`() {
        val shape = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+(-rc[0-9]+)?$")

        for (entry in SdkCapabilities.ALL) {
            assertTrue(shape.matches(entry.firstPublished), "malformed first publication: ${entry.firstPublished}")
        }
    }

    @Test
    @DisplayName("an id this build does not deliver is announced as deprecated, and only those")
    fun `an undelivered id is announced as deprecated`() {
        val deprecated = SdkCapabilities.ALL.filter { it.status == SdkCapabilities.CapabilityStatus.DEPRECATED }
            .map { it.id }

        assertEquals(
            listOf("events.follow.v1"),
            deprecated,
            "the ids this build announces but does NOT deliver are exactly the ones a consumer " +
                "must not build on; anything else here is either an id the build stopped " +
                "delivering without saying so, or an announced-but-not-delivered id added " +
                "without deciding it here",
        )
    }

    @Test
    @DisplayName("the follow vocabulary is delivered under the id this build announces")
    fun `the follow vocabulary is delivered under the announced id`() {
        val announced = SdkCapabilities.ALL.map { it.id }

        assertTrue(
            announced.contains("events.follow.v2"),
            "the build delivers the grown follow refusal vocabulary, so it must announce the id " +
                "that names it",
        )
        assertTrue(
            SdkCapabilities.ALL.none {
                it.id.startsWith("events.follow.") && it.status == SdkCapabilities.CapabilityStatus.PUBLICADA &&
                    it.id != "events.follow.v2"
            },
            "only the id this build delivers may claim to be delivered; two ids for one " +
                "vocabulary leave the consumer choosing between them",
        )
    }
}
