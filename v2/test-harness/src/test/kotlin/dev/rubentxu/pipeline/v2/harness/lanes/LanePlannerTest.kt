package dev.rubentxu.pipeline.v2.harness.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanePlannerTest {

    @Test
    fun `HF0 contract lands in FAST lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.FAST]!!.contains("unit.echo"))
    }

    @Test
    fun `HF2 in-process contract lands in INTEGRATION lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.INTEGRATION]!!.contains("integration.sh"))
    }

    @Test
    fun `requiresInstalledBinary lands in CANDIDATE lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.CANDIDATE]!!.contains("candidate.installed"))
    }

    @Test
    fun `HF3 in-process contract lands in RESTART lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.RESTART]!!.contains("restart.resume"))
    }

    @Test
    fun `requiresExternalCertifier lands in ONLINE_SMOKE lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.ONLINE_SMOKE]!!.contains("online.smoke"))
    }

    @Test
    fun `partition returns the same number of contracts as the input`() {
        val specs = LanePlanner.exampleSpecs()
        val partition = LanePlanner.partition(specs)
        val total = partition.values.sumOf { it.size }
        assertEquals(specs.size, total)
    }
}
