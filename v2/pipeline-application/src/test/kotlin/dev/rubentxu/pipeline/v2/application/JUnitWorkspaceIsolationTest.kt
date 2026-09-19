package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsInput
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsOutput
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsStepDefinition
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * WU-LPR-WC — isolation between concurrent invocations.
 *
 * Each [JUnitResultsStepDefinition.handler.execute] reads its canonical
 * workspace root from a typed `WORKSPACE_IDENTITY_CAPABILITY` carried by
 * the [StepHandlerContext]. Two handlers running concurrently in the same
 * JVM (e.g. inside a forked worker, a parallel branch, or a block-Step
 * fanout) MUST each see their own workspace root. They MUST NOT share any
 * global mutable state.
 *
 * This test fails closed if a future regression reintroduces a
 * process-wide state lookup (e.g. `System.getProperty("pipeline.workspace.root")`)
 * in the handler path.
 */
@Timeout(30)
class JUnitWorkspaceIsolationTest {

    /**
     * Minimal capability access exposing ONLY `WORKSPACE_IDENTITY_CAPABILITY`,
     * pointing at [workspaceRoot]. Mirrors the F5.2 contract-test helper
     * but is duplicated here to keep the test isolated from the contract
     * suite's evolution.
     */
    private class CapabilityAccessFor(
        private val workspaceRoot: Path,
    ) : StepCapabilityAccess {
        private val provided: Map<StepCapability, Any> = mapOf(
            WORKSPACE_IDENTITY_CAPABILITY to WorkspaceIdentity(workspaceRoot),
        )
        override fun available(): Set<StepCapability> = provided.keys
        override fun <T : Any> get(key: StepCapability): T = provided[key] as? T
            ?: error("Capability $key is not available")
    }

    private fun newContext(workspaceRoot: Path): StepHandlerContext = StepHandlerContext(
        runId = RunId("isolation-test"),
        stepIndex = 0,
        capabilities = CapabilityAccessFor(workspaceRoot),
    )

    private fun writeReport(dir: Path, name: String, tests: Int) {
        Files.createDirectories(dir)
        val xml = dir.resolve(name)
        Files.writeString(
            xml,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="$name" tests="$tests" failures="0" errors="0" skipped="0" time="0.0"/>
            """.trimIndent(),
        )
    }

    @Test
    fun `two concurrent handlers see independent workspaces`() {
        val wsA = Files.createTempDirectory("junit-iso-a-")
        val wsB = Files.createTempDirectory("junit-iso-b-")
        try {
            // wsA has a 3-test report; wsB has a 7-test report.
            writeReport(wsA.resolve("nested"), "a.xml", tests = 3)
            writeReport(wsB.resolve("nested"), "b.xml", tests = 7)

            val definition = JUnitResultsStepDefinition()
            val ready = CountDownLatch(2)
            val resultA = AtomicReference<JUnitResultsOutput?>(null)
            val resultB = AtomicReference<JUnitResultsOutput?>(null)

            val tA = thread(start = false, name = "handler-A") {
                ready.countDown()
                ready.await()
                runBlocking {
                    val input = JUnitResultsInput(
                        reportPath = "nested/a.xml",
                        workspaceRoot = ".",
                    )
                    resultA.set(definition.handler.execute(input, newContext(wsA)))
                }
            }
            val tB = thread(start = false, name = "handler-B") {
                ready.countDown()
                ready.await()
                runBlocking {
                    val input = JUnitResultsInput(
                        reportPath = "nested/b.xml",
                        workspaceRoot = ".",
                    )
                    resultB.set(definition.handler.execute(input, newContext(wsB)))
                }
            }
            tA.start(); tB.start()
            tA.join(); tB.join()

            val a = assertIsSuccess(resultA.get())
            val b = assertIsSuccess(resultB.get())
            // Each handler resolved its report against ITS workspace,
            // not the other's. A shared global resolver would have caused
            // both to read the same file (or both to fail).
            assertEquals(3, a.summary.tests, "handler A must see wsA's 3 tests")
            assertEquals(7, b.summary.tests, "handler B must see wsB's 7 tests")
            assertTrue(
                a.summary.reportPath.startsWith(wsA.toString()),
                "handler A's report path must start with wsA, got: ${a.summary.reportPath}",
            )
            assertTrue(
                b.summary.reportPath.startsWith(wsB.toString()),
                "handler B's report path must start with wsB, got: ${b.summary.reportPath}",
            )
        } finally {
            for (d in listOf(wsA, wsB)) {
                Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `capability access is per-handler - cross-handler state is not visible`() {
        // Even when one handler's capability access points at wsA and
        // another (in a sequential run after) points at wsB, the second
        // run MUST NOT see wsA's value. This is a degenerate case but
        // catches any leak through thread-locals or static state.
        val wsA = Files.createTempDirectory("junit-iso-seq-a-")
        val wsB = Files.createTempDirectory("junit-iso-seq-b-")
        try {
            writeReport(wsA.resolve("nested"), "a.xml", tests = 4)
            writeReport(wsB.resolve("nested"), "b.xml", tests = 11)
            val definition = JUnitResultsStepDefinition()
            runBlocking {
                val first = definition.handler.execute(
                    JUnitResultsInput(reportPath = "nested/a.xml", workspaceRoot = "."),
                    newContext(wsA),
                )
                val second = definition.handler.execute(
                    JUnitResultsInput(reportPath = "nested/b.xml", workspaceRoot = "."),
                    newContext(wsB),
                )
                assertEquals(4, assertIsSuccess(first).summary.tests)
                assertEquals(11, assertIsSuccess(second).summary.tests)
            }
        } finally {
            for (d in listOf(wsA, wsB)) {
                Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun assertIsSuccess(output: JUnitResultsOutput?): JUnitResultsOutput {
        assertTrue(output != null, "handler returned null")
        assertEquals(StepOutcome.Success, output!!.outcome, "expected Success, got ${output.outcome}")
        return output
    }
}
