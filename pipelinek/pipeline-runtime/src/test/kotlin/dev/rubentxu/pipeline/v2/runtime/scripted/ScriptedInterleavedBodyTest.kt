package dev.rubentxu.pipeline.v2.runtime.scripted

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.runtime.LiveSpanLedger
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A body that runs KOTLIN between two declared steps, and what the durable
 * authorities make of it.
 *
 * ## The guarantee under trial
 *
 * That a body can declare a step, take a DECISION with the value a runtime
 * observation returns, and declare another step — and that the decision
 * happens while the run runs, not while the plan is built. The body here is
 * the smallest program that says that: a step that writes a file, an
 * observation of that file, a branch on what the observation returned, and a
 * second step that only runs when the branch took its arm.
 *
 * ## Why this lane is worth having
 *
 * The DSL's own answer to a condition is a condition that is decided BEFORE
 * the run: a `script { }` block is evaluated while the plan is built, so the
 * branch it takes is the branch that reaches the plan. The scripted frontend
 * is the other answer — the body runs inside the run — and this lane asks the
 * question that decides between them: does the runtime path actually carry a
 * decision made from a runtime value, with the durability the rest of the
 * product has?
 *
 * ## What the rows measure
 *
 * - the ORDER: the marker the two steps append to holds `BEFORE` and then
 *   `AFTER`, and the decision sits between them.
 * - the decision's INPUT: the branch is taken because the body OBSERVED a
 *   file that the FIRST step wrote. A body that decided before the run would
 *   not have seen it.
 * - the REPLAY: the same journal and the same run id, run again, and the
 *   marker keeps ONE `BEFORE` and ONE `AFTER`. A replay that repeated the
 *   effect would double them.
 * - the SURFACE: what a body may call. It is the set that decides whether a
 *   body can reach a plugin's step or a credential, so it is pinned here
 *   instead of being read from the source of a day.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScriptedInterleavedBodyTest {

    /**
     * The body runs a step, decides with a runtime observation, and runs a
     * second step.
     *
     * The decision reads a file that the FIRST step wrote, so the branch can
     * only be taken by a body that ran after that step: a body whose Kotlin
     * was evaluated while the plan was built would have seen no file at all.
     */
    @Test
    fun `a body decides between two steps with a value the run observed`() {
        val marker = workspace.resolve("effect.log")
        val observed = workspace.resolve("observed.txt")
        val calls = mutableListOf<String>()
        val body = InterleavedBody(observed, marker, calls)

        val outcome = runBody(body, journal())

        assertEquals(RunOutcome.Success, outcome, "the body did not succeed: $outcome")
        assertEquals(
            listOf("sh:before", "observe:observed.txt", "decision:true", "sh:after"),
            calls,
            "the body did not take the order the guarantee is about: the decision has to sit " +
                "between the two steps, and it has to be taken from the observation the first " +
                "step made possible",
        )
        assertEquals(
            listOf("BEFORE", "AFTER"),
            linesOf(marker),
            "the marker does not hold the two steps in order",
        )
    }

    /**
     * The replay of a body does not repeat the effect.
     *
     * The same journal, the same run id, the same body: the durable
     * authorities find the recorded invocation and SERVE it, so the marker
     * keeps one line per step.
     */
    @Test
    fun `the replay of a body does not repeat the effect`() {
        val marker = workspace.resolve("effect.log")
        val observed = workspace.resolve("observed.txt")
        val journal = journal()

        val first = runBody(InterleavedBody(observed, marker, mutableListOf()), journal)
        val second = runBody(InterleavedBody(observed, marker, mutableListOf()), journal)

        assertEquals(RunOutcome.Success, first, "the first run did not succeed: $first")
        assertEquals(RunOutcome.Success, second, "the replay did not succeed: $second")
        assertEquals(
            listOf("BEFORE", "AFTER"),
            linesOf(marker),
            "the replay repeated an effect that was already registered: the marker would hold " +
                "the two steps twice",
        )
    }

    /**
     * What a body may call is a CLOSED set, and the set has no door to a
     * plugin's step nor to a credential.
     *
     * The question is not academic: it decides whether a body can reach
     * anything the eager DSL reaches. A new call on the façade is a new
     * capability, and this row is what forces the decision to be taken on
     * purpose instead of by accident.
     */
    @Test
    fun `the surface a body may call is closed and has no plugin nor credential door`() {
        val declared = ScriptedStepFacade::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            // Kotlin mangles a method that carries a value by default: the bytecode name is
            // `sh-<hash>`, not `sh`. The name the reader sees is the one before the dash, and
            // a Kotlin name cannot carry a dash, so the split is exact.
            .map { it.name.substringBefore('-') }
            .toSet()

        assertEquals(
            setOf("scoped", "sh", "isUnix", "pwd", "readFile", "fileExists"),
            declared,
            "the surface a body may call moved: a new call is a new capability, and the set " +
                "this row pins is the one the decision of this lane was taken on",
        )
    }

    /**
     * The body the rows run: a step, an observation, a decision, a step.
     */
    private class InterleavedBody(
        private val observed: Path,
        private val marker: Path,
        private val calls: MutableList<String>,
    ) : CompiledScriptedEntryPoint {

        override val artifact: ScriptedArtifactIdentity = ARTIFACT

        override val entryPointId: String = "interleaved-body"

        override suspend fun execute(steps: ScriptedStepFacade) {
            calls += "sh:before"
            steps.sh(
                ScriptedCallSiteId("before"),
                "printf 'BEFORE\\n' >> '$marker' && printf 'x' > '$observed'",
            )
            calls += "observe:observed.txt"
            val exists = steps.fileExists(ScriptedCallSiteId("observed"), observed.toString())
            calls += "decision:$exists"
            if (exists) {
                calls += "sh:after"
                steps.sh(ScriptedCallSiteId("after"), "printf 'AFTER\\n' >> '$marker'")
            }
        }

        private companion object {
            val ARTIFACT: ScriptedArtifactIdentity = ScriptedArtifactIdentity(
                sourceDigest = "source-digest",
                dslApiVersion = "dsl-api",
                compilerAdapterVersion = "compiler-adapter",
                runtimeCompatibilityVersion = "runtime-compatibility",
                pluginLockDigest = "plugin-lock",
                facadeSchemaDigest = "facade-schema",
            )
        }
    }

    /**
     * The plan's step is either an opaque step or a block, and neither of them
     * names a compiled artifact.
     *
     * This is what the plan can say, and it is why a body that runs at runtime
     * cannot live inside one: the plan has no node that says "here runs an
     * artifact", so a body that needed one would have no place to be written.
     * The row pins the two cases, so a third one — a node that names an
     * artifact — puts the row red and asks the question again on purpose.
     */
    @Test
    fun `the plan's step is an opaque step or a block and nothing else`() {
        val cases = StepNode::class.java.permittedSubclasses
            .map { it.simpleName }
            .toSet()

        assertEquals(
            setOf("OpaqueStepNode", "BlockStepNode"),
            cases,
            "the plan's step kinds moved: a new kind is a new place where a body could be " +
                "written, and the decision this lane took was taken on the two kinds there are",
        )
    }

    /**
     * The body is observed: its steps open spans under the run's own parent.
     *
     * A body that ran without leaving a trace would be a surface that hides
     * the run it is executing, and the trace is the only thing that says the
     * run happened.
     */
    @Test
    fun `the body is observed under the run's own parent`() {
        val ledger = LiveSpanLedger()

        val outcome = runBody(InterleavedBody(workspace.resolve("observed.txt"), workspace.resolve("effect.log"), mutableListOf()), journal(), ledger)

        assertEquals(RunOutcome.Success, outcome, "the body did not succeed: $outcome")
        assertTrue(
            ledger.spansOf(RUN_ID).isNotEmpty(),
            "the body left no trace: a run whose steps opened no span is a run nobody can see",
        )
        assertEquals(
            emptyList<Any>(),
            ledger.leaked(),
            "the body left a span open: a span that never closed is a span that never ended",
        )
    }

    /**
     * Runs the body through the canonical scripted execution.
     *
     * The runner is the product's own: the same journal, the same registry,
     * the same executor and the same recovery interpretation the durable
     * coordinator uses. Nothing here is a second engine.
     */
    private fun runBody(
        body: CompiledScriptedEntryPoint,
        journal: InMemoryOperationJournal,
        ledger: LiveSpanLedger = LiveSpanLedger(),
    ): RunOutcome {
        val outcome = ScriptedFrontendRunner.run(
            entryPoint = body,
            runId = RUN_ID,
            expectedArtifact = body.artifact,
            registry = CoreStepRegistryFactory.builder().build(),
            journal = journal,
            eventSink = RecordingEventSink(),
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            shOptions = ShOptions(
                workspaceRoot = workspace,
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                sandbox = SandboxConfigResolver.resolve(SandboxProfile.NONE),
                workspaceOwnership = WorkspaceOwnership.PIPELINEK,
            ),
            controlDirRoot = controlRoot,
            structure = ScriptedStructuralAddress.WholeProgram,
            telemetry = ledger,
        )
        val completed = outcome as? ScriptedFrontendRunner.Outcome.Completed
            ?: error("the artifact was refused: $outcome")
        return completed.aggregate
    }

    /** The journal of the run: the durable authority that survives the process. */
    private fun journal(): InMemoryOperationJournal = InMemoryOperationJournal(FixedClock)

    /** The lines the marker holds: the number of times the effects happened. */
    private fun linesOf(marker: Path): List<String> =
        if (!Files.exists(marker)) emptyList() else Files.readAllLines(marker)

    /** A clock that does not move, so nothing in this lane depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(FIXED_MILLIS)
    }

    /** A sink that keeps the events, so a row can read them if it needs to. */
    private class RecordingEventSink : EventSink {
        private val recorded = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            recorded += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = recorded.asSequence()
    }

    @TempDir
    lateinit var temp: Path

    @TempDir
    lateinit var controlRoot: Path

    /** The workspace the steps write into: the root the shell and the queries share. */
    private val workspace: Path
        get() = Files.createDirectories(temp.resolve("workspace"))

    private companion object {
        /** The identifier of the run both invocations carry: the run is RESUMED. */
        const val RUN_ID = "run-1"

        /** The moment the fixed clock reads: a moment, and not the epoch. */
        const val FIXED_MILLIS = 1_700_000_000_000L
    }
}
