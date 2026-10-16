package dev.rubentxu.pipeline.v2.harness.catalog

import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bidirectional invariants for [ContractCatalogue]. These are the catalogue
 * "fitness" rules: a catalogue that violates any of them is unfit.
 */
class ContractCatalogueFitnessTest {

    private fun entry(
        id: String,
        capability: String,
        patterns: List<String>,
        minFidelity: HarnessFidelity = HarnessFidelity.HF0,
    ) = ContractEntry(
        id = ContractId(id),
        capability = CapabilityId(capability),
        owner = "owner",
        metadata = ContractMetadata(
            minimumFidelity = minFidelity,
            requiresInstalledBinary = false,
            requiresExternalCertifier = false,
        ),
        suiteFqcn = "owner.$id",
        sourcePatterns = patterns,
    )

    @Test
    fun `empty catalogue is refused at construction`() {
        val ex = runCatching { ContractCatalogue.of(emptyList()) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException) { "got $ex" }
    }

    @Test
    fun `duplicate contract id refuses construction`() {
        val ex = runCatching {
            ContractCatalogue.of(
                listOf(
                    entry("a", "c.a", listOf("src/A.kt")),
                    entry("a", "c.b", listOf("src/B.kt")),
                ),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException) { "got $ex" }
    }

    @Test
    fun `bidirectional — capability to contracts is consistent with contracts to capability`() {
        val cat = ContractCatalogue.of(
            listOf(
                entry("a", "c", listOf("src/A.kt")),
                entry("b", "c", listOf("src/B.kt")),
                entry("d", "d", listOf("src/D.kt")),
            ),
        )
        val viaCapability = cat.contractsFor(CapabilityId("c"))
        assertEquals(setOf(ContractId("a"), ContractId("b")), viaCapability)
        for (cid in viaCapability) {
            val e = cat.entryFor(cid)
            assertNotNull(e) { "lost contract $cid" }
            assertEquals(CapabilityId("c"), e!!.capability)
        }
    }

    @Test
    fun `glob pattern matches a path with placeholders`() {
        val cat = ContractCatalogue.of(listOf(entry("a", "c", listOf("src/*.kt"))))
        val match = cat.lookupByPath("src/A.kt")
        assertTrue(match is ContractCatalogue.MatchResult.Single) { "got $match" }
    }

    @Test
    fun `no match returns None (not Single)`() {
        val cat = ContractCatalogue.of(listOf(entry("a", "c", listOf("src/A.kt"))))
        val match = cat.lookupByPath("src/B.kt")
        assertTrue(match is ContractCatalogue.MatchResult.None) { "got $match" }
    }

    @Test
    fun `duplicate source pattern across two contracts refuses construction`() {
        val ex = runCatching {
            ContractCatalogue.of(
                listOf(
                    entry("a", "c.a", listOf("src/A.kt")),
                    entry("b", "c.b", listOf("src/A.kt")),
                ),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException) { "got $ex" }
    }

    @Test
    fun `entryFor returns null for unknown contract`() {
        val cat = ContractCatalogue.of(listOf(entry("a", "c", listOf("src/A.kt"))))
        assertNull(cat.entryFor(ContractId("nope")))
    }
}
