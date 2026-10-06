package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RunOutcomeReducer
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.durable.outcomeOf
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4-D2 — `Unstable` must survive the durable scripted registry seam, on FRESH and on REUSE,
 * without a new durable format.
 *
 * ## The two losses this closes
 *
 * ```text
 * 1. RegistryExecutionBoundary  -> CommonExecutionResult(outcome = Unstable, encodedOutput = …)
 *       -> ScriptedRegistryInvoker   Unstable collapsed into Success(encoded)     LOSS 1
 * 2. runBody  discarded the runtime's result and returned a hardcoded Success         LOSS 2
 *       -> MainScriptedSupport re-derived a RunOutcome from that StepOutcome
 * ```
 *
 * The marker is real, not theoretical: `core.sh`, `core.emit.event`, `core.lock` and
 * `core.milestone` all produce it, and every one of them carries the outcome in its own durable
 * typed output. `S4A0ScriptedUnstableOutcomeCharacterizationTest` characterized the loss; this
 * file is the non-regression side of that transition.
 *
 * ## Why the carrier is a purpose-built test Step
 *
 * The law is GENERIC — `TypedStepOutput` is the single authority for `typed output -> StepOutcome`
 * and this suite must not name a concrete Step. So the fixture Step below is not `core.sh`: it is
 * a Step whose output happens to implement `TypedStepOutput` and whose codec round-trips the
 * outcome. A suite that proved conservation only for `core.sh` would be testing one Step, not the
 * projection rule.
 *
 * ## Why the durable status cannot be the source
 *
 * A Step that completed as `Unstable` is journalled `SUCCEEDED`, exactly like a successful one, so
 * replay cannot recover the distinction from `OperationStatus`. It is recovered from the decoded
 * payload — a place the existing durable format already stores. That is why FRESH and REUSE can be
 * made to agree BY CONSTRUCTION instead of by two implementations happening to coincide: both
 * arrive at `invokeTyped` as `Success(encoded)`, both are decoded by the Step's own codec, and
 * both read their outcome from the carrier.
 *
 * ## Harness fidelity (ADR-0072)
 *
 * Crosses the productive authority: `ScriptedRegistryInvoker.invokeTyped` →
 * `RegistryExecutionBoundary` → `DurableStepExecutor` → real `InMemoryOperationJournal`, composed
 * by the shared [ScriptedInvokerFixture]. Nothing here re-implements reconciliation; the
 * conservation claim is observed at the seam that used to drop it.
 */
class S4D2ScriptedUnstablePreservationTest {

    // ---- generic carrier ------------------------------------------------------

    /**
     * A typed output that carries its outcome, exactly as `CoreShellOutput` and
     * `HttpResponseOutput` do. The outcome is DERIVED from the payload rather than stored beside
     * it, so the carrier cannot be constructed in a state where the two disagree.
     */
    private data class CarrierOutput(
        val payload: String,
        val marker: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome
            get() = when (marker) {
                "unstable" -> StepOutcome.Unstable
                "fail" -> StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "carried failure"))
                else -> StepOutcome.Success
            }
    }

    private object CarrierCodec : StepCodec<CarrierOutput> {
        override fun encode(value: CarrierOutput): EncodedStepValue = EncodedStepValue(
            buildJsonObject {
                put("payload", JsonPrimitive(value.payload))
                put("marker", JsonPrimitive(value.marker))
            }.toString(),
        )

        override fun decode(encoded: EncodedStepValue): CarrierOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            return CarrierOutput(
                payload = obj.getValue("payload").jsonPrimitive.content,
                marker = obj.getValue("marker").jsonPrimitive.content,
            )
        }
    }

    private class PayloadCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private class CarrierStep(
        private val marker: String,
        private val replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED,
        private val effects: List<Effect> = listOf(Effect.READ_ONLY),
    ) :
        StepDefinition<String, CarrierOutput> {
        val handlerInvocations = AtomicInteger(0)

        override val contract: StepContract<String, CarrierOutput> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "carrier",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = effects,
                replayPolicy = replayPolicy,
            ),
            inputCodec = PayloadCodec(),
            outputCodec = CarrierCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<String, CarrierOutput> = StepHandler { input, _ ->
            handlerInvocations.incrementAndGet()
            CarrierOutput(input, marker)
        }

        companion object {
            val KEY = PluginStepId("scripted.carrier.d2")
        }
    }

    private fun identity(runId: String) = ScriptedScopeIdentity(
        runId = runId,
        entryPointId = "entry-d2",
        dynamicScopePath = emptyList(),
        definitionDigest = "digest-d2",
    )

    /**
     * The durable operation id the fixture derives for the FIRST invocation at [callSite] —
     * computed through the real derivation (`ScriptedRegistryCall.operationId()`), never a
     * local copy of the tuple discipline.
     */
    private fun callOperationId(id: ScriptedScopeIdentity, callSite: ScriptedCallSiteId): String =
        ScriptedRegistryCall(
            runId = id.runId,
            entryPointId = id.entryPointId,
            callSiteId = callSite,
            dynamicScopePath = id.dynamicScopePath,
            invocationOrdinal = 0,
            stepKey = CarrierStep.KEY,
            encodedInput = EncodedStepValue("payload-d2"),
            definitionDigest = id.definitionDigest,
        ).operationId()

    private fun invokerOver(registry: StepRegistry) = ScriptedInvokerFixture.build(
        registry = registry,
        journal = InMemoryOperationJournal(SystemClock()),
    )

    // ---- the projection is one rule ------------------------------------------

    @Test
    fun `outcomeOf reads the carrier and defaults to Success for a non-carrier`() {
        // If this ever needs a second implementation somewhere, this is the test that must fail
        // first: it pins the rule itself, not any one Step's use of it.
        assertEquals(
            StepOutcome.Unstable,
            outcomeOf(CarrierOutput("x", "unstable")),
            "a TypedStepOutput's own outcome is the projection",
        )
        assertEquals(
            StepOutcome.Success,
            outcomeOf("a plain String"),
            "a non-carrier value keeps the legacy Success default that core.echo relies on",
        )
        assertEquals(StepOutcome.Success, outcomeOf(Unit), "Unit is a value, and defaults to Success")
    }

    // ---- conservation: fresh and reuse ----------------------------------------

    @Test
    fun `D2-L1 and D2-L2 - FRESH conserves value and Unstable, and the PERSISTED payload carries it`() =
        runBlocking {
            // D2's certified contract. It says nothing about replay: see the DURABLE FRONTIER test.
            val step = CarrierStep(marker = "unstable")
            val registry = StepRegistryBuilder().also { it.add(step) }.build()
            val journal = InMemoryOperationJournal(SystemClock())
            val invoker = ScriptedInvokerFixture.build(registry = registry, journal = journal)
            val id = identity("run-unstable")
            val callSite = ScriptedCallSiteId("cs-d2")
            val call = ScriptedRegistryCall(
                runId = id.runId,
                entryPointId = id.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = id.dynamicScopePath,
                invocationOrdinal = 0,
                stepKey = CarrierStep.KEY,
                encodedInput = EncodedStepValue("payload-d2"),
                definitionDigest = id.definitionDigest,
            )

            val fresh = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")

            // D2-L1a: the Kotlin value survives.
            assertEquals(
                "payload-d2",
                fresh.value.payload,
                "D2-L1: FRESH delivers the expected Kotlin value",
            )
            // D2-L1b: the semantic outcome survives — the loss this slice closes.
            assertEquals(
                StepOutcome.Unstable,
                fresh.outcome,
                "D2-L1: FRESH reports the carried outcome, not Success",
            )
            assertEquals(1, step.handlerInvocations.get(), "the handler ran exactly once")
            assertEquals(
                RunOutcome.Unstable,
                RunOutcomeReducer.reduce(listOf(fresh.outcome)),
                "D2-L1: the canonical reducer turns that outcome into an Unstable run",
            )

            // D2-L2: the PERSISTED payload carries it. This is read back from the journal's own
            // bytes and decoded with the Step's own codec — not re-derived from the live object —
            // so it proves the durable record itself preserves the outcome, and it proves
            // `outcomeOf` recovers it from what was actually stored.
            val persisted = journal.get(call.operationId())!!.output
            assertTrue(persisted != null, "the invocation must have persisted an output")
            val persistedJson = (persisted!!.result as JsonPrimitive).content
            val decoded = CarrierCodec.decode(EncodedStepValue(persistedJson))
            assertEquals("payload-d2", decoded.payload, "D2-L2: the persisted payload keeps the value")
            assertEquals(
                StepOutcome.Unstable,
                outcomeOf(decoded),
                "D2-L2: decoding the PERSISTED bytes yields the Unstable outcome, so the " +
                    "information is in the durable record and not only in memory",
            )
        }

    @Test
    fun `DURABLE FRONTIER - a second invocation reuses a completed-unstable step (debt RESOLVED by P2)`() =
        runBlocking {
            // This test WAS the recorded S4-D2 frontier, and P2 of the Runtime Observation
            // Contract Closure CLOSED it. The full history, because this row changed meaning
            // twice and both changes were deliberate:
            //
            //   AS CHARACTERIZED (pre-P1)
            //     The durable projection collapsed StepOutcome.Unstable onto FAILED, no rule
            //     reused a non-SUCCEEDED row, and a second invocation re-executed. Recorded as
            //     design debt, not endorsed.
            //
            //   P1 (representation half closed)
            //     The projection persisted OperationStatus.UNSTABLE. The row carried the fact;
            //     the re-execution debt remained, because replay reused a SUCCEEDED row only.
            //     This test then asserted 2 handler invocations and said so.
            //
            //   P2 (replay half closed — THIS state)
            //     `EffectReplayPolicy.isReusableCompletion` admits UNSTABLE, so the recorded
            //     debt is RESOLVED, not re-recorded: the second invocation reuses the decoded
            //     typed carrier, derives the outcome from it, and the handler does not run.
            //
            // Still deliberately NOT done: no rule is widened beyond reusable completions
            // (FAILED/ABORTED/... stay non-reusable — pinned in
            // `EffectReplayPolicyTableFitnessTest`), and scripted never fabricates
            // `Unstable -> SUCCEEDED` to win a reuse. The reuse path is ONE authority:
            // `DurableInvocationResolver` decides, `ScriptedTypedResult.from` derives the
            // outcome from the decoded carrier.
            val step = CarrierStep(marker = "unstable")
            val registry = StepRegistryBuilder().also { it.add(step) }.build()
            val journal = InMemoryOperationJournal(SystemClock())
            val invoker = ScriptedInvokerFixture.build(registry = registry, journal = journal)
            val id = identity("run-unstable")
            val callSite = ScriptedCallSiteId("cs-d2")

            val fresh = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")
            val reused = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")

            assertEquals(
                OperationStatus.UNSTABLE,
                journal.get(callOperationId(id, callSite))!!.status,
                "the durable row carries the fact: an unstable run is persisted UNSTABLE (P1)",
            )
            // THE LAW (fresh == reuse == restart), MEMOIZED + purely READ_ONLY half:
            assertEquals(fresh.value, reused.value, "reuse delivers the SAME decoded value")
            assertEquals(
                StepOutcome.Unstable,
                reused.outcome,
                "reuse reports the SAME semantic outcome, derived from the typed carrier — " +
                    "not from the durable status column",
            )
            assertEquals(
                1,
                step.handlerInvocations.get(),
                "RESOLVED BY P2 (was the measured debt): a completed-unstable step is REUSED on " +
                    "the second invocation — zero further handler executions — because UNSTABLE " +
                    "is a reusable completion. If this goes back to 2, the replay debt reopened",
            )
        }

    /**
     * The same law under RERUN + EXECUTES_SUBPROCESS: a completed-unstable step is reusable
     * whatever the policy branch admits the reuse, and the reuse never re-runs the handler.
     * RERUN (misnamed) is the reuse branch for non-read-only steps; MEMOIZED with a purely
     * read-only set is the other. Both read the SAME `isReusableCompletion` classification —
     * this row fails if anyone ever forks a second table.
     */
    @Test
    fun `P2 - fresh == restart under RERUN too - unstable reuses with zero handler executions`() =
        runBlocking {
            val step = CarrierStep(
                marker = "unstable",
                replayPolicy = ReplayPolicy.RERUN,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
            )
            val registry = StepRegistryBuilder().also { it.add(step) }.build()
            val journal = InMemoryOperationJournal(SystemClock())
            val invoker = ScriptedInvokerFixture.build(registry = registry, journal = journal)
            val id = identity("run-unstable-rerun")
            val callSite = ScriptedCallSiteId("cs-d2-rerun")

            val fresh = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")
            val reused = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")

            assertEquals(fresh.value, reused.value, "reuse delivers the SAME decoded value")
            assertEquals(StepOutcome.Unstable, reused.outcome)
            assertEquals(
                1,
                step.handlerInvocations.get(),
                "RERUN + UNSTABLE is a reusable completion: the restart reuses, zero executions",
            )
        }

    @Test
    fun `MEASURED - a FAILED row is re-executed under MEMOIZED even for a plain READ_ONLY step`() =
        runBlocking {
        // The substrate fact, stated generically and without naming any Step. It is what makes
        // the previous test's result a property of the replay policy rather than of D2.
        val step = CarrierStep(marker = "fail")
        val registry = StepRegistryBuilder().also { it.add(step) }.build()
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = ScriptedInvokerFixture.build(registry = registry, journal = journal)
        val call = ScriptedRegistryCall(
            runId = "run-failed",
            entryPointId = "entry-d2",
            callSiteId = ScriptedCallSiteId("cs-d2"),
            dynamicScopePath = emptyList(),
            invocationOrdinal = 0,
            stepKey = CarrierStep.KEY,
            encodedInput = EncodedStepValue("payload-d2"),
            definitionDigest = "digest-d2",
        )

        invoker.invoke(call)
        assertEquals(1, step.handlerInvocations.get())
        assertEquals(
            OperationStatus.FAILED,
            journal.get(call.operationId())!!.status,
            "MEASURED: a genuine Failure persists FAILED. BEFORE P1 an Unstable Step persisted " +
                "the SAME status, so the durable format could not tell them apart; P1 gave " +
                "Unstable its own terminal case, so that asymmetry is resolved and this row now " +
                "measures only the genuine-failure side.",
        )

        invoker.invoke(call)
        assertEquals(
            2,
            step.handlerInvocations.get(),
            "MEASURED: a FAILED row is not served from cache under MEMOIZED + READ_ONLY",
        )
    }

    @Test
    fun `a Success Step stays Success on both paths, so the fix is not a blanket Unstable`() =
        runBlocking {
            val step = CarrierStep(marker = "ok")
            val registry = StepRegistryBuilder().also { it.add(step) }.build()
            val invoker = invokerOver(registry)
            val id = identity("run-success")
            val callSite = ScriptedCallSiteId("cs-d2")

            val fresh = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")
            val reuse = invoker.invokeTyped(id, callSite, 0, step, "payload-d2")

            assertEquals(StepOutcome.Success, fresh.outcome)
            assertEquals(StepOutcome.Success, reuse.outcome)
            assertEquals(1, step.handlerInvocations.get())
        }

    // ---- aggregation: the reducer is the only precedence ----------------------

    @Test
    fun `the collector preserves order and precedence belongs to the reducer alone`() {
        val unstableOnly = ScriptedOutcomeCollector().apply {
            record(StepOutcome.Success)
            record(StepOutcome.Unstable)
        }
        assertEquals(
            listOf(StepOutcome.Success, StepOutcome.Unstable),
            unstableOnly.snapshot(),
            "the collector is a recorder, not a folder: order is execution order",
        )
        assertEquals(
            RunOutcome.Unstable,
            RunOutcomeReducer.reduce(unstableOnly.snapshot()),
            "Success then Unstable reduces to Unstable",
        )

        val withFailure = ScriptedOutcomeCollector().apply {
            record(StepOutcome.Success)
            record(StepOutcome.Unstable)
            record(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "later failure")))
        }
        assertTrue(
            RunOutcomeReducer.reduce(withFailure.snapshot()) is RunOutcome.Failure,
            "a later Failure outranks an earlier Unstable; the collector never decides this",
        )

        assertEquals(
            RunOutcome.Success,
            RunOutcomeReducer.reduce(emptyList()),
            "a body that ran no Steps is a Success run, not a fabricated Unstable",
        )
    }

    @Test
    fun `a child dynamic scope records into the SAME run-local collector`() = runBlocking {
        // The failure this guards against is a per-scope accumulator: an Unstable raised inside a
        // dynamic scope would be discarded on return to the parent, and the run would report
        // Success. Child scopes receive the same instance, exactly as they share the ordinals map.
        val outcomes = ScriptedOutcomeCollector()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { error("this test must not reach the shell") },
            callSites = ScriptedCallSiteProvider.fixed("cs-d2"),
        )

        runtime.run(
            definitionDigest = "digest-d2",
            entryPointId = "entry-d2",
            runId = "run-scope",
            outcomes = outcomes,
        ) {
            scoped(ScriptedDynamicScopeId("loop:1")) {
                recordOutcome(StepOutcome.Unstable)
            }
            recordOutcome(StepOutcome.Success)
        }

        assertEquals(
            listOf(StepOutcome.Unstable, StepOutcome.Success),
            outcomes.snapshot(),
            "an outcome recorded inside a child scope survives on return to the parent",
        )
        assertEquals(RunOutcome.Unstable, RunOutcomeReducer.reduce(outcomes.snapshot()))
    }

    @Test
    fun `the frontend reduces with the canonical reducer and hardcodes no outcome`() {
        // WHY THIS IS A SOURCE SCAN
        //
        // `runBody` is private and reducing through it needs a compiled artifact, so the unit
        // suite above proves `collector + RunOutcomeReducer.reduce(snapshot())` is correct but
        // does NOT prove `runBody` calls it. Without this test, reverting `runBody` to
        // `StepOutcome.Success` would kill nothing — a mutation with no teeth.
        //
        // It is a source scan, and deliberately so: the repo already pins properties of the
        // scripted path this way (`S4A1ScriptedShellPathPrivilegeCanaryTest` scans every
        // production source for a privileged call). It is a fitness check on STRUCTURE, not a
        // re-implementation of the algorithm, and it names the two files that must hold.
        val runner = productionCode("scripted/ScriptedFrontendRunner.kt")
        val support = productionCode("MainScriptedSupport.kt")
        val invoker = productionCode("scripted/ScriptedRegistryInvoker.kt")

        assertTrue(
            "RunOutcomeReducer.reduce(" in runner,
            "runBody MUST reduce with the canonical RunOutcomeReducer; precedence is written " +
                "once, in the reducer, and nowhere else.",
        )
        assertTrue(
            "outcomes.snapshot()" in runner,
            "runBody MUST read the snapshot of the run-local collector, which it owns above " +
                "the try so the catch can reach it.",
        )
        assertTrue(
            "StepOutcome.Success" !in runner,
            "runBody MUST NOT hardcode a StepOutcome. The previous body returned a literal " +
                "StepOutcome.Success after discarding the runtime's result; that single line " +
                "is how every Unstable step reported a successful run.",
        )
        assertTrue(
            "StepOutcome." !in support,
            "MainScriptedSupport MUST NOT re-derive a RunOutcome from a StepOutcome. The " +
                "fabricated Success/Unstable/Failure table there was a second precedence " +
                "authority; the aggregate is already a RunOutcome produced by the reducer.",
        )

        // D2-L3: no parallel scripted classifier. The scripted invoker must ask the ONE
        // projection, never re-derive an outcome or inline the same cast.
        assertTrue(
            "ScriptedTypedResult.from(decoded)" in invoker,
            "D2-L3: the scripted invoker MUST build its carrier through ScriptedTypedResult.from, " +
                "which derives the outcome with the canonical outcomeOf.",
        )
        assertTrue(
            "as? TypedStepOutput" !in invoker,
            "D2-L3: the scripted invoker MUST NOT inline its own TypedStepOutput projection. " +
                "Two copies of one rule are two authorities that drift.",
        )
        assertTrue(
            "StepOutcome.Unstable" !in invoker,
            "D2-L3: the scripted invoker MUST NOT name Unstable at all. It transports whatever " +
                "the carrier decided; a literal here would be a second classifier.",
        )

        // D2-L5: a PipelineStepException is recorded exactly once. The invoker THROWS rather than
        // returning a failed result, so nothing upstream already recorded it — and if both the
        // throw site and the catch recorded, the reducer would see a duplicate.
        assertEquals(
            1,
            Regex("""outcomes\.record\(StepOutcome\.Failure""").findAll(runner).count(),
            "D2-L5: exactly one record(Failure) site. Two would double-count the failure; zero " +
                "would drop it and the run would not fail closed.",
        )
    }

    /**
     * Production CODE, with comments stripped.
     *
     * Comments are removed because a fitness scan must read code, not prose: the KDoc of the very
     * method these assertions guard NAMES `StepOutcome.Success` in order to explain that the
     * method must not contain it. A scan that matched raw text would be satisfiable by deleting a
     * comment and unsatisfiable by keeping the explanation — i.e. it would grade documentation,
     * which is not what it is for.
     */
    private fun productionCode(relativePath: String): String {
        val candidates = listOf(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/$relativePath",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/$relativePath",
        )
        val found = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("production source not found; looked in $candidates")
        return found.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }
}
