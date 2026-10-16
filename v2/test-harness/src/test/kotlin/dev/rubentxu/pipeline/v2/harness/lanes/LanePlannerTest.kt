package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanePlannerTest {

    @Test
    fun `HF0 contract lands in FAST lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleCatalogue())
        assertTrue(partition[TestLane.FAST]!!.contains(ContractId("unit.echo")))
    }

    @Test
    fun `HF2 in-process contract lands in INTEGRATION lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleCatalogue())
        assertTrue(partition[TestLane.INTEGRATION]!!.contains(ContractId("integration.sh")))
    }

    @Test
    fun `requiresInstalledBinary lands in CANDIDATE lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleCatalogue())
        assertTrue(partition[TestLane.CANDIDATE]!!.contains(ContractId("candidate.installed")))
    }

    @Test
    fun `HF3 in-process contract lands in RESTART lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleCatalogue())
        assertTrue(partition[TestLane.RESTART]!!.contains(ContractId("restart.resume")))
    }

    @Test
    fun `requiresExternalCertifier lands in ONLINE_SMOKE lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleCatalogue())
        assertTrue(partition[TestLane.ONLINE_SMOKE]!!.contains(ContractId("online.smoke")))
    }

    @Test
    fun `partition returns the same number of contracts as the catalogue`() {
        val catalogue = LanePlanner.exampleCatalogue()
        val partition = LanePlanner.partition(catalogue)
        val total = partition.values.sumOf { it.size }
        assertEquals(catalogue.size, total)
    }
}
