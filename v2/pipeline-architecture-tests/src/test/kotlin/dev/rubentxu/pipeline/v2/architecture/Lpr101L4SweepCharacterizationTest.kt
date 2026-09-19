package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * WU-LPR-101 — L4 sweep findings, characterized as RED tests on purpose.
 *
 * These tests do not attempt to fix the laws they describe; they pin the
 * current state so the next cycle (LPR-101a..LPR-101d) has a measurable
 * starting line. A test that stays RED until the corresponding WU lands
 * is the explicit signal the law is still unfulfilled.
 *
 * Law families covered:
 *   F-LE-1   no concrete Step key in canonical coordinator dispatch
 *   F-LE-2   canonical coordinator field dispatcher unused in production
 *   F-DSL-1  DSL construction does not execute process or IO
 *   F-DSL-2  absence of @DslMarker (current state; not a defect here)
 *   F-EVT-1  EventSink (V1) and EventPublisher (EVT-2) coexist
 *   F-PROC-1 runBlocking call sites in ProcessDurableTaskRuntime
 */
class Lpr101L4SweepCharacterizationTest {

    private val v2Root = ScannerSupport.v2Root()
    private val pipelineDslPath = v2Root.resolve(
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt"
    )
    private val coordinatorPath = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt"
    )
    private val processRuntimePath = v2Root.resolve(
        "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/task/ProcessDurableTaskRuntime.kt"
    )
    private val eventStorePath = v2Root.resolve(
        "pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventStore.kt"
    )
    private val eventHistoryPortsPath = v2Root.resolve(
        "pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt"
    )

    private fun readOrSkip(path: java.nio.file.Path): String? =
        if (Files.exists(path)) Files.readString(path) else null

    // ---- F-LE-1: canonical coordinator has no when(pluginStepId.value) ----

    @Test
    fun `F-LE-1a canonical coordinator does not branch on concrete step keys in dispatch`() {
        val text = readOrSkip(coordinatorPath) ?: return
        // The body-routing dispatch must be a closed ADT match on BodyExecutionPolicy,
        // NOT a when on a Step key. The "core.waitUntil" branch was the only offender
        // (retired at WU-LPR-301 / G5, 2026-09-18). Comments that mention the pattern
        // historically are excluded so the test stays semantic, not lexical.
        val lines = text.lines()
        val offenders = lines.mapIndexed { i, line -> i + 1 to line }.filter { (_, line) ->
            val trimmed = line.trim()
            !trimmed.startsWith("//") &&
                (trimmed.contains("pluginStepId.value ==") || trimmed.contains("pluginStepId.value == \""))
        }
        assertEquals(
            emptyList<Pair<Int, String>>(),
            offenders,
            "Canonical coordinator dispatch must not branch on concrete pluginStepId keys; " +
                "this is the Step Constitution invariant. Found ${offenders.size} offender(s) " +
                "in CanonicalDurableRunCoordinator.kt"
        )
    }

    // ---- F-LE-2: coordinator field dispatcher unused in production dispatch path ----

    @Test
    fun `F-LE-2 coordinator's CanonicalNodeDispatcher field is unused in production paths`() {
        val text = readOrSkip(coordinatorPath) ?: return
        // The field exists (we saw it at construction), but the dispatch() method on it
        // is never called from production code. A zombie dispatcher is a future maintenance
        // hazard; the law here is to keep it that way until a deliberate retirement.
        val lines = text.lines()
        val dispatcherCallSites = lines.mapIndexed { i, line -> i + 1 to line }.filter { (_, line) ->
            val trimmed = line.trim()
            // Match `dispatcher.dispatch(` or `this.dispatcher.dispatch(` but exclude comments.
            (trimmed.startsWith("dispatcher.dispatch") || trimmed.startsWith("this.dispatcher.dispatch") ||
                trimmed.contains(".dispatcher.dispatch(")) && !trimmed.startsWith("//")
        }
        assertEquals(
            emptyList<Pair<Int, String>>(),
            dispatcherCallSites,
            "CanonicalDurableRunCoordinator.dispatcher must not be invoked from production " +
                "code in the canonical path (BlockStepNode routes through dispatchBody, " +
                "not through CanonicalNodeDispatcher). Found ${dispatcherCallSites.size} call site(s)."
        )
    }

    // ---- F-DSL-1: DSL construction does not execute process or IO ----

    @Test
    fun `F-DSL-1 PipelineDsl DSL does not import process or IO at construction time`() {
        val text = readOrSkip(pipelineDslPath) ?: return
        // We allow construction-time references to types and functions, but NOT actual calls.
        // A real construction-time call would be ProcessBuilder().start(), Runtime.exec(),
        // Files.write, etc. We assert: no ProcessBuilder, no Runtime.exec, no Files.write in DSL.
        val forbidden = listOf(
            "ProcessBuilder(",
            "Runtime.getRuntime().exec(",
            "Files.write",
            "Files.createDirectories",
        )
        val lines = text.lines()
        val hits = lines.mapIndexed { i, line -> i + 1 to line }.filter { (_, line) ->
            val trimmed = line.trim()
            forbidden.any { trimmed.contains(it) } && !trimmed.startsWith("//")
        }
        assertEquals(
            emptyList<Pair<Int, String>>(),
            hits,
            "PipelineDsl.kt must remain declarative; construction-time process/IO is forbidden. " +
                "Found ${hits.size} offender(s)."
        )
    }

    // ---- F-DSL-2: absence of @DslMarker (characterization) ----

    @Test
    fun `F-DSL-2 PipelineDsl declares DslMarker annotations (characterization satisfied)`() {
        val text = readOrSkip(pipelineDslPath) ?: return
        val count = Regex("@DslMarker").findAll(text).count()
        // LPR-401 was implemented: PipelineDsl.kt now declares a marker hierarchy
        // (PipelineDslMarker/StageDslMarker/StepDslMarker/PostDslMarker) applied to the
        // DSL scopes, satisfying the LPR-401 target (>= 1). Pin the declared markers.
        assertTrue(
            count >= 1,
            "PipelineDsl.kt is expected to declare @DslMarker markers (LPR-401 satisfied); found $count",
        )
    }

    // ---- F-EVT-1: EventSink (V1) and EventPublisher (EVT-2) coexist ----

    @Test
    fun `F-EVT-1a EventStore and EventHistoryPorts both exist (two event APIs coexist)`() {
        val v1 = readOrSkip(eventStorePath)
        val v2 = readOrSkip(eventHistoryPortsPath)
        assertTrue(v1 != null, "V1 EventStore source must exist")
        assertTrue(v2 != null, "EVT-2 EventHistoryPorts source must exist")
        assertTrue(v1!!.contains("interface EventSink") || v1.contains("class EventSink"),
            "V1 EventStore must declare EventSink")
        assertTrue(v2!!.contains("interface EventPublisher"),
            "EVT-2 EventHistoryPorts must declare EventPublisher")
    }

    @Test
    fun `F-EVT-1b the two event APIs have disjoint publisher-consumer surfaces`() {
        val v1 = readOrSkip(eventStorePath) ?: return
        val v2 = readOrSkip(eventHistoryPortsPath) ?: return
        // Disjoint surface signals they are NOT a code duplication. Each API has its own type.
        assertFalse(v1.contains("EventPublisher"), "V1 EventStore must not mention EVT-2 types")
        assertFalse(v2.contains("EventSink"), "EVT-2 EventHistoryPorts must not mention V1 types")
    }

    // ---- F-PROC-1: runBlocking call sites in ProcessDurableTaskRuntime ----

    @Test
    fun `F-PROC-1 ProcessDurableTaskRuntime runBlocking call sites (characterization)`() {
        val text = readOrSkip(processRuntimePath) ?: return
        val lines = text.lines()
        val hits = lines.mapIndexed { i, line -> i + 1 to line }.filter { (_, line) ->
            line.contains("runBlocking") && !line.trim().startsWith("//")
        }
        // Baseline: 4 call sites (lpr-000 counted 3 in the wrapper path; the SDK runtime
        // has 4). Pin the count so LPR-203 has a measurable target.
        assertEquals(
            4,
            hits.size,
            "ProcessDurableTaskRuntime currently has 4 runBlocking call sites; " +
                "LPR-203 (output-pump refactor) targets reducing this. Found ${hits.size}."
        )
    }
}
