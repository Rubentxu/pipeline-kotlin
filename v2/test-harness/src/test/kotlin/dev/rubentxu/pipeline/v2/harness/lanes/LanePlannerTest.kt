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
    fun `LaneAccess Installed lands in CANDIDATE lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.CANDIDATE]!!.contains("candidate.installed"))
    }

    @Test
    fun `HF3 in-process contract lands in RESTART lane`() {
        val partition = LanePlanner.partition(LanePlanner.exampleSpecs())
        assertTrue(partition[TestLane.RESTART]!!.contains("restart.resume"))
    }

    @Test
    fun `LaneAccess InstalledWithCertifier lands in ONLINE_SMOKE lane`() {
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

    /**
     * H1 TODO 4 governance test: the planner MUST consume
     * the SAME catalogue the contracts use, not a hand-
     * built spec set. The hand-built `exampleSpecs` only
     * exercises the partition logic; the production path
     * is `LanePlannerMain.realSpecsFromCatalogue()`, which
     * reads the catalogue at CLI invocation. The mandate
     * says "fast / integration / candidate se calculan
     * mediante el mismo catálogo"; this test pins that
     * the catalogue is the authority and the partition
     * is exhaustive over the catalogue's contracts.
     */
    @Test
    fun `partition over the real catalogue is exhaustive and non-empty`() {
        val specs = realSpecsFromCatalogueForTests()
        assertTrue(
            specs.isNotEmpty(),
            "the catalogue must declare at least one contract; an empty catalogue " +
                "would silently produce an empty partition and a green fast lane",
        )
        val partition = LanePlanner.partition(specs)
        val total = partition.values.sumOf { it.size }
        assertEquals(
            specs.size,
            total,
            "every catalogue contract must be assigned to exactly one lane; the partition " +
                "size MUST equal the catalogue size or a contract silently drops. " +
                "got partition=$partition specs=$specs",
        )
        // Every contract is assigned to a known lane. An
        // unknown lane key would mean the planner is
        // building partitions the dispatcher cannot map
        // to a Gradle task.
        partition.keys.forEach { lane ->
            assertTrue(
                lane in TestLane.entries,
                "planner produced a partition under an unknown lane key: $lane",
            )
        }
    }

    /**
     * The CANDIDATE lane is the installed-CLI lane (HF2+).
     * The catalogue must declare at least one contract
     * that needs the installed binary, otherwise the
     * installed lane is a no-op (the same silent-green
     * the H1 TODO 3 fix closed in the test classpath).
     */
    @Test
    fun `the catalogue's installed-binary contracts are non-empty`() {
        val specs = realSpecsFromCatalogueForTests()
        val partition = LanePlanner.partition(specs)
        val candidate = partition[TestLane.CANDIDATE].orEmpty()
        assertTrue(
            candidate.isNotEmpty(),
            "the catalogue must declare at least one contract that needs the installed " +
                "binary; an empty CANDIDATE lane means installedContractTest runs 0 tests, " +
                "the same silent-green the H1 TODO 3 fix closed",
        )
    }
}
