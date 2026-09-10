package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-DSL-003: Parallel — canonical parallel branch execution.
 *
 * Mandatory laws under test (E-EM-11 parallel rows), all against the
 * real installed distribution binary (installDist), never the test JVM:
 *
 *  P1  exact cardinality: 1 Started + 1 Finished per branch, no spurious events.
 *  P2  identity pairing: Started/Finished of one branch share (branchIndex, branchName);
 *      identities of distinct branches are distinct.
 *  P3  intra-branch ordering only: Started(X) < Finished(X); no global A/B interleaving frozen.
 *  P4  outcomes: Finished reflects real branch outcome; aggregate RunFinished follows
 *      the current productive policy (any branch failure => failure).
 *  P5  canonical children: each branch executes its steps through the same spine
 *      (StepStarted/StepFinished per child); architecture fitness separately proves
 *      direct StepSpec execution = 0 (see Lfc2RegistryFamilyFitness).
 *  P7  one branch failure: Finished(success) + Finished(failure) + aggregate failure.
 *  P8  branch isolation: durable child identities of A and B are disjoint
 *      (identity law detailed in OpIdContractTest).
 *
 * Legacy fixture note: the original parallel.pipeline.kts mixed a parallel body with a
 * sibling step. The canonical compiler rejects that fail-closed (G2, stageNode) — correct
 * behavior, not a regression. It is preserved as the negative fixture
 * parallel-mixed-body-sibling.pipeline.kts and asserted as rejected below.
 *
 * P6 (parallel replay/reuse semantics) is a durable-policy question and is explicitly
 * NOT PART OF E-EM-11 EVENT CLOSURE; no reuse guarantee is asserted here.
 */
@Timeout(180)
class UatDsl003ParallelTest {

    private val appBin: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        val bin = moduleDir
            .resolve("build")
            .resolve("install")
            .resolve("pipeline-application")
            .resolve("bin")
            .resolve("pipeline-application")
        if (!bin.toFile().exists()) {
            throw IllegalStateException(
                "Application binary not found at $bin. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        bin
    }

    private val parallelScript: Path = resource("/parallel.pipeline.kts")
    private val failureScript: Path = resource("/parallel-failure.pipeline.kts")
    private val mixedBodySiblingScript: Path = resource("/parallel-mixed-body-sibling.pipeline.kts")

    // ---- P4 (success path) + P1 + P2 + P3 + P5 + P8 ----

    @Test
    fun `P1 P2 P3 - exactly one start and finish per branch with stable paired identity`() {
        val (_, events) = runAndDecode(parallelScript, expectedExit = 0)

        val started = events.filterIsInstance<ParallelBranchStarted>()
        val finished = events.filterIsInstance<ParallelBranchFinished>()

        // P1: exact cardinality, no spurious branch events.
        assertEquals(2, started.size, "Expected exactly 2 ParallelBranchStarted: $events")
        assertEquals(2, finished.size, "Expected exactly 2 ParallelBranchFinished: $events")

        val startedIds = started.map { it.branchIndex to it.branchName }
        val finishedIds = finished.map { it.branchIndex to it.branchName }

        // P2: same identity set on both sides; each identity appears exactly once.
        assertEquals(startedIds.toSet(), finishedIds.toSet(), "Started/Finished identities must pair")
        assertEquals(startedIds.size, startedIds.toSet().size, "No duplicate Started identities")
        assertEquals(finishedIds.size, finishedIds.toSet().size, "No duplicate Finished identities")
        assertTrue(setOf("branch-a", "branch-b").all { n -> startedIds.any { it.second == n } },
            "Both branch names must be present: $startedIds")

        // P2: identities of distinct branches are distinct.
        assertTrue(startedIds.toSet().size == started.size, "Branch identities must be distinct")
    }

    @Test
    fun `P3 - intra-branch ordering Started before Finished, no global interleaving frozen`() {
        val (_, events) = runAndDecode(parallelScript, expectedExit = 0)

        val startedIdx = events.withIndex().mapNotNull { (i, e) ->
            (e as? ParallelBranchStarted)?.let { i to (it.branchIndex to it.branchName) }
        }
        val finishedIdx = events.withIndex().mapNotNull { (i, e) ->
            (e as? ParallelBranchFinished)?.let { i to (it.branchIndex to it.branchName) }
        }

        for (id in setOf(0 to "branch-a", 1 to "branch-b")) {
            val s = startedIdx.first { it.second == id }.first
            val f = finishedIdx.first { it.second == id }.first
            assertTrue(s < f, "Started($id) must precede Finished($id)")
        }
        // Deliberately NO assertion on relative order between branch-a and branch-b events:
        // any interleaving is valid under concurrency.
    }

    @Test
    fun `P4 - success fixture branch outcomes success and RunFinished success`() {
        val (stdout, events) = runAndDecode(parallelScript, expectedExit = 0)

        val finished = events.filterIsInstance<ParallelBranchFinished>()
        assertEquals(2, finished.size)
        assertTrue(finished.all { it.outcome == "success" }, "All branches must be success: $finished")

        val runFinished = events.last() as? RunFinished
        assertTrue(runFinished != null, "Last event must be RunFinished")
        assertEquals("success", runFinished!!.outcome, "Aggregate must be success: $stdout")
    }

    @Test
    fun `P5 - canonical children execute inside each branch`() {
        val (_, events) = runAndDecode(parallelScript, expectedExit = 0)

        val stepStarted = events.filterIsInstance<StepStarted>()
        val stepFinished = events.filterIsInstance<StepFinished>()
        assertEquals(stepStarted.size, stepFinished.size, "Every branch step must terminate")

        val names = stepStarted.map { it.stepName }
        // 3 children per branch, each executed through the canonical spine.
        assertEquals(6, names.size, "Expected 3 children per branch across 2 branches: $names")
        assertTrue(names.any { it.contains("branch-branch-a") }, "Branch A children present: $names")
        assertTrue(names.any { it.contains("branch-branch-b") }, "Branch B children present: $names")

        // P8: durable child identities of A and B are disjoint.
        val a = names.filter { it.contains("branch-branch-a") }.toSet()
        val b = names.filter { it.contains("branch-branch-b") }.toSet()
        assertTrue(a.intersect(b).isEmpty(), "Branch child identities must be disjoint: $names")
    }

    // ---- P4 (failure path) + P7 ----

    @Test
    fun `P4 P7 - one branch failure yields per-branch outcomes and aggregate failure`() {
        val (_, events) = runAndDecode(failureScript, expectedExit = 1)

        val finished = events.filterIsInstance<ParallelBranchFinished>()
        val byName = finished.associate { it.branchName to it.outcome }

        assertEquals(2, finished.size, "Exactly two branch finishes: $events")
        assertEquals("success", byName["ok"], "ok branch must succeed: $finished")
        assertEquals("failure", byName["bad"], "bad branch must fail: $finished")

        val runFinished = events.last() as? RunFinished
        assertTrue(runFinished != null, "Last event must be RunFinished")
        assertEquals("failure", runFinished!!.outcome, "Aggregate policy: any branch failure => failure")
    }

    // ---- G2 negative: parallel body + sibling step is fail-closed ----

    @Test
    fun `G2 - stage mixing parallel body with sibling step is rejected fail-closed`() {
        val stdoutFile = java.nio.file.Files.createTempFile("uat", ".stdout")
        val process = ProcessBuilder(appBin.toString(), "run", mixedBodySiblingScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectErrorStream(true)
            .start()
        val exit = process.waitFor()

        assertEquals(1, exit, "Mixed parallel+sibling stage must fail closed")
        val output = java.nio.file.Files.readString(stdoutFile)
        assertTrue(
            "cannot mix a parallel body with sibling steps" in output,
            "Rejection must name the G2 contract: $output",
        )
    }

    // ---- full timeline sanity (success fixture) ----

    @Test
    fun `complete canonical event timeline for parallel stage`() {
        val (_, events) = runAndDecode(parallelScript, expectedExit = 0)

        assertTrue(events.first() is CompilationStarted, "First event must be CompilationStarted")
        assertTrue(events.any { it is CompilationFinished })
        // OBSERVATION (E-EM-11): parallel stages currently emit StageFinished but no
        // StageStarted (verified in dist); recorded here as characterized behavior, not
        // asserted as a law. If stage-entry projection becomes mandatory, that is a
        // production change outside this test-only slice.
        assertTrue(events.any { it is StageFinished })
        assertTrue(events.any { it is RunStarted })
        assertTrue(events.any { it is StepStarted })
        assertTrue(events.any { it is StepFinished })
        assertTrue(events.last() is RunFinished, "Last event must be RunFinished")
    }

    private fun resource(name: String): Path =
        Paths.get(javaClass.getResource(name)!!.toURI())

    private fun runAndDecode(script: Path, expectedExit: Int): Pair<String, List<DomainEvent>> {
        val stdoutFile = java.nio.file.Files.createTempFile("uat", ".stdout")
        val process = ProcessBuilder(appBin.toString(), "run", script.toString())
            .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        val stdout = java.nio.file.Files.readString(stdoutFile).trim()
        if (exitCode != expectedExit) {
            throw IllegalStateException("CLI exited with $exitCode, expected $expectedExit. output: $stdout")
        }
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
    @Test
    fun `Z2 - parallel stage emits StageStarted and StageFinished with same stage identity`() {
        val (_, events) = runAndDecode(parallelScript, expectedExit = 0)

        val started = events.filterIsInstance<StageStarted>()
        val finished = events.filterIsInstance<StageFinished>()

        assertEquals(1, started.size, "Exactly one StageStarted for the parallel stage: $events")
        assertEquals(1, finished.size, "Exactly one StageFinished for the parallel stage: $finished")
        assertEquals(started.single().stageIndex, finished.single().stageIndex, "Stage identity must pair")
        assertEquals(started.single().stageName, finished.single().stageName, "Stage identity must pair")

        // Ordering laws (no global branch ordering frozen):
        assertTrue(events.indexOf(started.single()) < events.indexOfFirst { it is ParallelBranchStarted },
            "StageStarted must precede every ParallelBranchStarted")
        assertTrue(events.indexOfLast { it is ParallelBranchFinished } < events.indexOf(finished.single()),
            "Every ParallelBranchFinished must precede StageFinished")
    }
}