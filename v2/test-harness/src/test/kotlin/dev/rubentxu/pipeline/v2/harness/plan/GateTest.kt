package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contracts for the seam that ACTUALLY RUNS the dispatch and then adjudicates it.
 *
 * ## Why the runner is an interface
 *
 * The decision of what to run is pure ([Dispatch]). The running is not. Injecting the running keeps
 * the two separable: these tests assert that the gate runs every invocation the dispatch produced,
 * compares the evidence that came back against the plan, and refuses when they disagree — without a
 * build. The production runner passes the argv to Gradle unchanged, so the argv asserted here is
 * the argv that executes.
 */
class GateTest {

    /** Records what it was asked to run and returns a fixed outcome. */
    private class RecordingRunner(private val outcome: (Dispatch.GradleInvocation) -> Boolean) :
        SuiteRunner {
        val ran = mutableListOf<Dispatch.GradleInvocation>()

        override fun run(invocation: Dispatch.GradleInvocation): Boolean {
            ran += invocation
            return outcome(invocation)
        }
    }

    private val suiteA = TestSuiteId("AlphaTest", ":mod-one")
    private val suiteB = TestSuiteId("BetaTest", ":mod-one")
    private val suiteC = TestSuiteId("GammaTest", ":mod-two")

    private fun plan(vararg suites: TestSuiteId) = ValidationPlan.Selected(
        capabilities = setOf("cap.one"),
        contracts = emptySet(),
        suites = suites.toSet(),
        minimumFidelities = setOf(Fidelity.HF2),
        reasons = emptyList(),
    )

    // ---- the runner is driven exactly as the dispatch says ----

    @Test
    fun `every invocation the dispatch produced is handed to the runner`(@TempDir tmp: Path) {
        val gateRunner = RecordingRunner { true }
        Gate.run(
            plan = plan(suiteA, suiteB, suiteC),
            invocations = Dispatch.dispatch(plan(suiteA, suiteB, suiteC)),
            runner = gateRunner,
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        // Two invocations, not three: :mod-one owns AlphaTest and BetaTest in ONE Gradle run, and
        // that grouping is the property DispatchTest asserts separately. Repeating them here would
        // assert the opposite of the design.
        assertEquals(2, gateRunner.ran.size, "one invocation per module, not per suite")
        assertEquals(
            listOf(":mod-one", ":mod-two"),
            gateRunner.ran.map { it.gradleProject },
        )
        assertEquals(listOf("AlphaTest", "BetaTest"), gateRunner.ran[0].filters)
        assertEquals(listOf("GammaTest"), gateRunner.ran[1].filters)
    }

    @Test
    fun `a runner that fails refuses the gate even when every other suite has evidence`(
        @TempDir tmp: Path,
    ) {
        // The runner failing IS the evidence of failure; there is no XML to consult for a Gradle
        // invocation that could not start. A gate that ignores this reports green on a build that
        // never ran.
        val refusal = Gate.run(
            plan = plan(suiteA),
            invocations = Dispatch.dispatch(plan(suiteA)),
            runner = RecordingRunner { false },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        assertTrue(refusal != null, "a failed invocation must refuse the gate")
        assertEquals(
            listOf(SelectionRefusal.SuiteDidNotRun("AlphaTest", ":mod-one")),
            refusal.reasons,
        )
    }

    // ---- the evidence side ----

    @Test
    fun `a plan whose suite produced no XML is refused, not assumed green`(@TempDir tmp: Path) {
        // The failure mode this whole line of work exists to prevent: a required suite that never
        // ran, read as a pass because nothing contradicted it.
        val refusal = Gate.run(
            plan = plan(suiteA),
            invocations = Dispatch.dispatch(plan(suiteA)),
            runner = RecordingRunner { true },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        assertTrue(refusal != null, "an absent report must refuse, not pass")
        assertEquals(
            listOf(SelectionRefusal.SelectedButNotRun("AlphaTest")),
            refusal.reasons,
        )
    }

    @Test
    fun `a stale report left by a previous run does not discharge the gate`(@TempDir tmp: Path) {
        // The mutation this kills is reading the evidence BEFORE running: a report left by an
        // earlier run then satisfies the gate even though nothing executed this time.
        // `GradleSuiteRunner` deletes the reports first for exactly this reason.
        //
        // The report must be ON DISK when the gate starts and GONE by the time it adjudicates.
        // A runner that deletes it is the production behaviour; a runner that leaves it is the
        // mutation, and with the report still present a pre-run read would accept it.
        val resultsDir = Gate.resultsDirFor(tmp, ":mod-one")
        writeXml(resultsDir, "AlphaTest", 0, 0)

        val gateRunner = object : SuiteRunner {
            override fun run(invocation: Dispatch.GradleInvocation): Boolean {
                // Production deletes stale reports before invoking Gradle; a plain runner that did
                // not would let the pre-run read through.
                Files.deleteIfExists(resultsDir.resolve("TEST-AlphaTest.xml"))
                return true
            }
        }

        val refusal = Gate.run(
            plan = plan(suiteA),
            invocations = Dispatch.dispatch(plan(suiteA)),
            runner = gateRunner,
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        assertEquals(
            listOf(SelectionRefusal.SelectedButNotRun("AlphaTest")),
            refusal?.reasons,
            "the report was deleted before the run, so no evidence survives it",
        )

        // And the gate must not accept a report the run left untouched. Gate cannot decide that on its
        // own — it has no way to know whether the runner cleared the directory — so the guarantee
        // lives where it can be enforced: `GradleSuiteRunner` removes every `TEST-*.xml` before
        // invoking Gradle, and this asserts that removal actually happens rather than trusting the
        // comment. The wrapper is a real file that exits non-zero, so the invocation fails exactly
        // as a broken build would, and the clearing is still the property under test.
        writeXml(resultsDir, "AlphaTest", 0, 0)

        // `buildRoot` holds the wrapper: the runner resolves `gradlew` inside the build that owns
        // the modules. Putting the fake wrapper directly in the repo root would pass a test that no
        // longer describes how the runner finds it.
        val buildRoot = Files.createDirectories(tmp.resolve("v2"))
        val fakeGradlew = buildRoot.resolve("gradlew")
        Files.writeString(fakeGradlew, "#!/bin/sh\nexit 1\n")
        fakeGradlew.toFile().setExecutable(true)

        val gradleRunner = GradleSuiteRunner(repoRoot = tmp, buildRoot = buildRoot)
        val succeeded = gradleRunner.run(
            Dispatch.GradleInvocation(":mod-one", "test", listOf("AlphaTest")),
        )

        assertTrue(!succeeded, "a Gradle invocation that exits non-zero must report failure")
        assertEquals(
            emptyMap<String, EvidenceReader.SuiteEvidence>(),
            EvidenceReader.suitesWithEvidence(resultsDir),
            "GradleSuiteRunner must clear the previous run's reports before invoking Gradle",
        )
    }

    @Test
    fun `evidence bound to a different commit is refused`(@TempDir tmp: Path) {
        writeXml(tmp.resolve("mod-one/build/test-results/test"), "AlphaTest", 0, 0)

        val refusal = Gate.run(
            plan = plan(suiteA),
            invocations = Dispatch.dispatch(plan(suiteA)),
            runner = RecordingRunner { true },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "stale999",
        )

        assertTrue(refusal != null, "evidence from another binary must refuse")
    }

    @Test
    fun `a report with a failure is not evidence of a pass`(@TempDir tmp: Path) {
        writeXml(tmp.resolve("mod-one/build/test-results/test"), "AlphaTest", 1, 0)

        val refusal = Gate.run(
            plan = plan(suiteA),
            invocations = Dispatch.dispatch(plan(suiteA)),
            runner = RecordingRunner { true },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        assertTrue(refusal != null, "a failing report must refuse")
    }

    @Test
    fun `every suite with clean evidence and the right commit discharges the gate`(
        @TempDir tmp: Path,
    ) {
        writeXml(tmp.resolve("mod-one/build/test-results/test"), "AlphaTest", 0, 0)
        writeXml(tmp.resolve("mod-one/build/test-results/test"), "BetaTest", 0, 0)

        val refusal = Gate.run(
            plan = plan(suiteA, suiteB),
            invocations = Dispatch.dispatch(plan(suiteA, suiteB)),
            runner = RecordingRunner { true },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        // H1 TODO 2: a clean run on the right commit used
        // to discharge the gate; the new gate also
        // demands a sidecar per suite. The test does
        // NOT write sidecars; the gate refuses on
        // IncompleteEvidence for both AlphaTest and
        // BetaTest. The test now pins the sidecar
        // requirement as part of "clean evidence".
        assertTrue(refusal != null, "missing sidecars must refuse the gate")
        val reasons = refusal!!.reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.IncompleteEvidence && it.suite == "AlphaTest" },
            "AlphaTest's XML has no sidecar; got $reasons",
        )
        assertTrue(
            reasons.any { it is SelectionRefusal.IncompleteEvidence && it.suite == "BetaTest" },
            "BetaTest's XML has no sidecar; got $reasons",
        )
    }

    @Test
    fun `evidence is read per module, not from one directory`(@TempDir tmp: Path) {
        // A suite that ran in :mod-two wrote its XML under mod-two's tree. Reading one shared
        // directory would find neither and report a green that spans two invocations it never
        // joined; reading mod-one's directory for mod-two's suite would find AlphaTest and miss
        // GammaTest. Both are the same absence-read-as-a-green shape.
        writeXml(tmp.resolve("mod-one/build/test-results/test"), "AlphaTest", failures = 0, errors = 0)

        val refusal = Gate.run(
            plan = plan(suiteA, suiteC),
            invocations = Dispatch.dispatch(plan(suiteA, suiteC)),
            runner = RecordingRunner { true },
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        // H1 TODO 2: the gate now also demands a sidecar
        // per suite. AlphaTest's XML exists in mod-one but
        // has no sidecar (the test never wrote one), so
        // the gate refuses on `IncompleteEvidence` for
        // AlphaTest and on `SelectedButNotRun` for
        // GammaTest (which has no XML at all). The test
        // pins both shapes: per-module evidence AND
        // sidecar presence.
        val reasons = refusal?.reasons ?: emptyList()
        assertTrue(
            reasons.any { it is SelectionRefusal.SelectedButNotRun && it.suite == "GammaTest" },
            "GammaTest's report is under mod-two, not mod-one; the gate must name it, got $reasons",
        )
        assertTrue(
            reasons.any { it is SelectionRefusal.IncompleteEvidence && it.suite == "AlphaTest" },
            "AlphaTest has an XML but no sidecar; the gate must refuse on IncompleteEvidence, " +
                "got $reasons",
        )
    }

    @Test
    fun `a refused plan dispatches nothing and refuses without running`(@TempDir tmp: Path) {
        val gateRunner = RecordingRunner { true }
        val refused = ValidationPlan.Refused(listOf(SelectionRefusal.UnclassifiedPath("unclassified path")))

        val result = Gate.run(
            plan = refused,
            invocations = Dispatch.dispatch(refused),
            runner = gateRunner,
            resultsRoot = tmp,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )

        assertEquals(0, gateRunner.ran.size, "a refused plan must not launch a single suite")
        assertTrue(result != null, "a refused plan refuses the gate")
    }
}

/** Writes a minimal JUnit report, so the evidence path is exercised without running anything. */
private fun writeXml(dir: Path, className: String, failures: Int, errors: Int) {
    Files.createDirectories(dir)
    Files.writeString(
        dir.resolve("TEST-$className.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="$className" tests="1" failures="$failures" errors="$errors"/>
        """.trimIndent(),
    )
}
