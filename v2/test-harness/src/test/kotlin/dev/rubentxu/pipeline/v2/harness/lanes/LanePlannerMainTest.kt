package dev.rubentxu.pipeline.v2.harness.lanes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * BQ4 v27 (typed JSON output, 2026-10-17): the
 * `LanePlannerMain` previously hand-rolled the JSON
 * output via `buildString { append("\"total_specs\": ${specs.size},\n") }`
 * with `${a.contractId}` and `${a.reason}` interpolated
 * as raw strings. The new shape is a typed
 * [LaneReport] carrier with `@Serializable`.
 *
 * The tests pin the **structural** contract:
 *
 * 1. `buildReport(specs)` is a pure function: it takes
 *    a list of [LaneSpec] and returns a [LaneReport].
 * 2. The report's `totalSpecs` equals the input size.
 * 3. The report's `byLane` is the same map the planner
 *    produced (the typed carrier does not re-derive
 *    the partition).
 * 4. The report's `assignments` is one [LaneAssignmentReport]
 *    per input spec, in the same order.
 * 5. The JSON output contains the snake_case wire
 *    format fields (`total_specs`, `by_lane`,
 *    `contract_id`).
 * 6. The library does proper escaping: a contractId or
 *    reason containing `"` or `\` produces valid JSON
 *    (the hand-rolled writer would not).
 *
 * A byte-exact comparison of the JSON output is NOT
 * pinned because the previous hand-rolled format
 * (`" : "` with space before colon, `","` no space
 * after comma in arrays) was non-standard JSON; the
 * typed encoder produces the standard form (`": "`,
 * `", "`), which is a tiny improvement on top of the
 * refactor. The test pins the structural contract
 * that matters to a `json.loads` consumer.
 */
class LanePlannerMainTest {

    @Test
    fun `buildReport carries the spec count`() {
        val specs = LanePlanner.exampleSpecs()
        val report = buildReport(specs)
        assertEquals(specs.size, report.totalSpecs)
    }

    @Test
    fun `buildReport byLane is the planner partition unchanged`() {
        val specs = LanePlanner.exampleSpecs()
        val partition = LanePlanner.partition(specs)
        val report = buildReport(specs)
        assertEquals(partition.keys, report.byLane.keys)
        for (lane in partition.keys) {
            assertEquals(partition[lane], report.byLane[lane])
        }
    }

    @Test
    fun `buildReport assignments are one per spec in spec order`() {
        val specs = LanePlanner.exampleSpecs()
        val report = buildReport(specs)
        assertEquals(specs.size, report.assignments.size)
        for ((spec, assignment) in specs.zip(report.assignments)) {
            assertEquals(spec.contractId, assignment.contractId)
            assertEquals(LanePlanner.laneFor(spec), assignment.lane)
        }
    }

    @Test
    fun `JSON output contains snake_case wire format fields`(@TempDir tmp: Path) {
        val report = buildReport(LanePlanner.exampleSpecs())
        val json = report.toJsonString()
        val root = Json.parseToJsonElement(json).let { it as JsonObject }
        assertEquals(
            setOf("total_specs", "by_lane", "assignments"),
            root.keys,
        )
        // Each assignment has contract_id (snake_case), lane, reason.
        val firstAssignment = root["assignments"]!!
            .toString()
            .let { Json.parseToJsonElement(it).jsonArray[0] as JsonObject }
        assertEquals(
            setOf("contract_id", "lane", "reason"),
            firstAssignment.keys,
        )
    }

    @Test
    fun `typed encoder escapes special characters in contractId and reason`(@TempDir tmp: Path) {
        // A spec with a contractId that contains characters which
        // would break the hand-rolled writer (a `"` and a `\`).
        val weirdSpec = LaneSpec(
            contractId = "evil\"with\\quote",
            capability = "evil\"with\\quote",
            minimumFidelity = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF0,
            access = LaneAccess.InProcess,
        )
        val report = buildReport(listOf(weirdSpec))
        val reportJson = report.toJsonString()
        // The library MUST escape the `"` and `\`; the JSON must
        // be parseable. The hand-rolled writer would emit
        // invalid JSON here.
        val root = Json.parseToJsonElement(reportJson) as JsonObject
        val byLane = root["by_lane"] as JsonObject
        // The lane key is the TestLane enum name, not the
        // contractId, so the contractId appears only in the
        // values list and in the assignment.
        val fastIds = byLane["FAST"]!!.toString()
        // The library encodes the input `evil"with\quote` as
        // `evil\"with\\quote` in the JSON. The test asserts the
        // library-escaped form is present (the hand-rolled writer
        // would have produced raw `"` and `\` and broken the JSON).
        assertTrue(
            fastIds.contains("""evil\"with\\quote"""),
            "library-escaped form expected; got: " + fastIds,
        )
    }

    // ---- carrier self-protect: the typed JSON carrier
    // refuses blank String fields at construction, matching
    // the pattern of [TestSuiteId], [HarnessVerdict.Pending],
    // and [LaneAssignment]. The producer [buildReport] is
    // fed by [LaneAssignment] (already self-protected), so
    // today's production path never produces a blank. The
    // test pins the contract so a future test fixture or
    // a refactor that breaks the upstream protection is
    // rejected at construction rather than emitting
    // `"contract_id": ""` / `"reason": ""` in the JSON. ----

    @Test
    fun `LaneAssignmentReport refuses a blank contractId`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LaneAssignmentReport(
                contractId = "",
                lane = TestLane.FAST,
                reason = "valid reason",
            )
        }
        assertTrue(
            ex.message?.contains("contractId") == true,
            "the rejection must name the field; got: ${ex.message}",
        )
    }

    @Test
    fun `LaneAssignmentReport refuses a blank reason`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LaneAssignmentReport(
                contractId = "valid.id",
                lane = TestLane.FAST,
                reason = "",
            )
        }
        assertTrue(
            ex.message?.contains("reason") == true,
            "the rejection must name the field; got: ${ex.message}",
        )
    }

    @Test
    fun `buildReport over the real catalogue produces no blank assignment fields`() {
        // The producer is [buildReport], fed by the real
        // [ContractCatalogue] (already self-protected
        // through [LaneAssignment]). Every assignment in
        // the report must have a non-blank contractId and
        // reason. This is the property the self-protect
        // init pins against a future regression where
        // the catalogue's protection is bypassed.
        val specs = realSpecsFromCatalogueForTests()
        val report = buildReport(specs)
        for (assignment in report.assignments) {
            assertTrue(
                assignment.contractId.isNotBlank(),
                "every assignment in the real-catalogue report must have a non-blank contractId; " +
                    "found blank for $assignment",
            )
            assertTrue(
                assignment.reason.isNotBlank(),
                "every assignment in the real-catalogue report must have a non-blank reason; " +
                    "found blank for $assignment",
            )
        }
    }
}
