package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.NoBasicCredentialSource
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.http.HTTP_TRANSPORT_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.http.HttpMethod
import dev.rubentxu.pipeline.v2.sdk.http.HttpRequestCodec
import dev.rubentxu.pipeline.v2.sdk.http.HttpRequestInput
import dev.rubentxu.pipeline.v2.sdk.http.HttpRequestStep
import dev.rubentxu.pipeline.v2.sdk.http.HttpSendOutcome
import dev.rubentxu.pipeline.v2.sdk.http.HttpSendRequest
import dev.rubentxu.pipeline.v2.sdk.http.HttpTransport
import dev.rubentxu.pipeline.v2.sdk.http.HttpTransportResult
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * H7 — an HTTP request is never re-sent, and a retry is a different request.
 *
 * ## The law, and why it is not the obvious thing
 *
 * `http.request` declares `ReplayPolicy.NEVER` and `RecoveryPolicy.None`. Every other
 * Step in this repository declares `MEMOIZED`, and `MEMOIZED` + `READ_ONLY` is exactly
 * what makes replay cheap and invisible. The two policies exist because the effect is
 * different in kind:
 *
 * ```text
 * core.echo      the world did not change        → reuse the recorded answer
 * http.request   the world may have changed      → the answer is the only trace
 * ```
 *
 * A POST that charged a card has an effect nobody can observe or undo. Reusing a
 * recorded response would report Success for a request this process never made;
 * re-sending blindly would charge twice. Neither is available, so the run STOPS and
 * says so. That is what these canaries measure.
 *
 * ## What "no resend" does and does not mean
 *
 * It means: for the SAME durable identity, no second socket. It does not mean "once
 * ever". A new run is a new invocation, and R7 pins that so nobody later reads NEVER
 * as a global latch and \"fixes\" it into one.
 *
 * ```text
 * R1  fresh                        → exactly one send
 * R2  journaled SUCCEEDED, same id → zero sends, the run FAILS
 * R3  journaled RUNNING, same id   → zero sends  (process died after the send)
 * R4  journaled FAILED, same id    → zero sends  (a timeout is not a licence to retry)
 * R5  the decision table itself    → every journaled state aborts, fresh executes
 * R6  divergent input              → zero sends, fails closed
 * R7  a DIFFERENT run id           → one send (a new run is a new invocation)
 * R8  the descriptor is the reason → NEVER / None / NETWORKS, asserted by name
 * R9  a failing send is still one  → the Step does not retry the transport itself
 * R10 retry { } gets a new id      → attempt 2 is not blocked by attempt 1's row
 * ```
 */
@Timeout(30)
class HttpReplayLawsTest {

    private val httpKey = PluginStepId("http.request")
    private val url = "https://api.example.test/v1/things"

    /** Counts sends. The ONLY thing these tests are allowed to infer about the network. */
    private class RecordingTransport(
        private val outcome: (Int) -> HttpSendOutcome = { HttpSendOutcome.Answered(200, emptyList(), "", "", 0L, false) },
    ) : HttpTransport {
        var calls = 0
        override suspend fun send(request: HttpSendRequest): HttpTransportResult {
            calls++
            return HttpTransportResult(outcome = outcome(calls), durationMs = 5L)
        }
    }

    private fun payload(): String = HttpRequestCodec
        .encode(HttpRequestInput(url = url, method = HttpMethod.Post, body = """{"charge":1}"""))
        .value

    private fun node(payload: String = payload()) = OpaqueStepNode(
        id = StepId("build/request"),
        pluginStepId = httpKey,
        payload = VersionedStepPayload("dsl-v1", payload),
    )

    private fun pipeline(payload: String = payload()) = CompiledPipeline(
        id = DefinitionId("http-replay-laws"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(listOf(node(payload))),
            ),
        ),
    )

    /**
     * The real production wiring, with the seams `http.request` declares and one that
     * permits egress. Everything else is the fixture's production default.
     */
    private fun coordinator(transport: HttpTransport, clock: SystemClock, journal: InMemoryOperationJournal) =
        CoordinatorFixture.default(
            clock = clock,
            journal = journal,
            eventSink = InMemoryEventStore(),
            stepRegistry = InMemoryStepRegistry().apply { register(HttpRequestStep.definition) },
            shOptions = ShOptions(
                workspaceRoot = Files.createTempDirectory("h7"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                networkEgress = AllowAll,
            ),
            capabilityContributor = CompositeCapabilityContributor(
                listOf(
                    RuntimeCapabilityContributor {
                        mapOf<StepCapability, Any>(
                            HTTP_TRANSPORT_CAPABILITY to transport,
                            BASIC_CREDENTIALS_CAPABILITY to NoBasicCredentialSource,
                        )
                    },
                ),
            ),
        )

    private fun operationInput(runId: String, payload: String = payload()) = OperationInput(
        stepId = httpKey.value,
        params = mapOf("payload" to JsonPrimitive(payload)),
        runId = runId,
        attempt = 1,
    )

    /**
     * The fingerprint the ENGINE computes.
     *
     * StepDispatchEngine passes the Step's DECLARED policy into the fingerprint, so this
     * must read it from the descriptor rather than hard-code `NEVER`.
     *
     * That is not pedantry. With a hard-coded `NEVER` here, flipping the descriptor to
     * `MEMOIZED` changed the engine's fingerprint, every seeded row stopped matching, and
     * R2/R3/R4/R6 passed through DIVERGENCE instead of through the replay law — four
     * green tests asserting the right thing for the wrong reason. Mutation M-http-30 is
     * what exposed it, and it is the reason this function reads the descriptor.
     */
    private fun engineFingerprint(input: OperationInput) = Fingerprint.compute(
        input,
        httpKey.value,
        HttpRequestStep.definition.contract.descriptor.replayPolicy,
        1,
    )

    private fun seed(
        journal: InMemoryOperationJournal,
        runId: RunId,
        status: OperationStatus,
        payload: String = payload(),
    ) {
        val input = operationInput(runId.value, payload)
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = engineFingerprint(input),
                input = input,
                output = null,
                status = status,
                attempt = 1,
            ),
        )
    }

    // ── R1: fresh means exactly one send ────────────────────────────────────

    @Test
    fun `R1 a fresh run sends exactly once`() = runBlocking {
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-fresh"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, transport.calls, "a fresh invocation is one send. Not zero (it never ran) and not two.")
        assertEquals(OperationStatus.SUCCEEDED, journal.listForRun("h7-fresh").single().status)
    }

    // ── R2 / R3 / R4: no journaled identity is ever re-sent ─────────────────

    @Test
    fun `R2 a journaled SUCCESS is never re-sent and never silently reused`() = runBlocking {
        // The subtle half. MEMOIZED would SKIP here and report Success from the
        // recorded row — which for http.request means reporting that a charge
        // happened because a previous process said so. The run must FAIL instead.
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        seed(journal, RunId("h7-replay-success"), OperationStatus.SUCCEEDED)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-replay-success"))

        assertEquals(0, transport.calls, "a replay must not open a second socket")
        assertTrue(
            outcome is RunOutcome.Failure,
            "the run must FAIL, not report Success from a recorded row. got $outcome",
        )
    }

    @Test
    fun `R3 a request that died in flight is never re-sent`() = runBlocking {
        // THE case. RUNNING means the previous process sent the request and died
        // before recording a terminal row. Whether the server acted is unknown and
        // unknowable from here. Re-sending is the one thing that could turn "unknown"
        // into "charged twice"; reusing would turn it into "claimed success".
        // Both are refused, and the refusal is the honest answer.
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        seed(journal, RunId("h7-process-death"), OperationStatus.RUNNING)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-process-death"))

        assertEquals(
            0,
            transport.calls,
            "the request left before the process died; whether it took effect is unknown, and " +
                "a resend is how 'unknown' becomes 'twice'.",
        )
        assertTrue(outcome is RunOutcome.Failure, "an uncertain remote effect must not close green")
    }

    @Test
    fun `R4 a FAILED attempt is never re-sent automatically`() = runBlocking {
        // A timeout or a mid-body drop is recorded FAILED. The tempting rescue is
        // "it failed, so nothing happened, so retrying is safe" — which is precisely
        // the inference that is unsafe for a POST whose server already acted.
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        seed(journal, RunId("h7-previous-failure"), OperationStatus.FAILED)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-previous-failure"))

        assertEquals(0, transport.calls, "a recorded failure is not evidence that the world is unchanged")
        assertTrue(outcome is RunOutcome.Failure, "got $outcome")
    }

    // ── R5: the decision table, so the four cases above share one authority ──

    @Test
    fun `R5 every journaled state aborts and only a fresh one executes`() {
        // Pure. This is the single function R2..R4 all went through, stated as a
        // table so a future edit to `EffectReplayPolicy` cannot quietly reopen the
        // middle rows.
        val policy = DefaultEffectReplayPolicy()
        val descriptor = HttpRequestStep.definition.contract.descriptor
        val effects = descriptor.effects.toSet()

        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.NEVER, effects, hasJournalEntry = false, journaledOutcome = null),
            "fresh must execute: NEVER constrains RE-execution, and forbidding the first send " +
                "would make the Step unusable",
        )
        for (journaled in listOf(
            OperationStatus.RUNNING,
            OperationStatus.SUCCEEDED,
            OperationStatus.FAILED,
            OperationStatus.ABORTED,
        )) {
            assertEquals(
                ReplayDecision.ABORT,
                policy.decide(ReplayPolicy.NEVER, effects, hasJournalEntry = true, journaledOutcome = journaled),
                "$journaled must ABORT. RUNNING is the one that matters most — it is a request " +
                    "that left this process and never came back.",
            )
        }
    }

    @Test
    fun `R5b the descriptor, not the engine, is what forbids the resend`() {
        // The contrast that keeps R5 honest: with the policy every other Step uses,
        // the SAME engine SKIPs and reports success from the recorded row. So "no
        // resend" is a property http.request declares, not a safety the spine adds.
        val policy = DefaultEffectReplayPolicy()
        val descriptor = HttpRequestStep.definition.contract.descriptor

        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(
                ReplayPolicy.MEMOIZED,
                setOf(Effect.READ_ONLY),
                hasJournalEntry = true,
                journaledOutcome = OperationStatus.SUCCEEDED,
            ),
            "the engine will happily reuse; this Step must not be asking for that",
        )
        assertEquals(
            setOf(Effect.NETWORKS),
            descriptor.effects.toSet(),
            "http.request declares NETWORKS, and nothing else",
        )
    }

    // ── R6: divergence fails closed, and does not send either ───────────────

    @Test
    fun `R6 a diverged invocation fails closed without sending`() = runBlocking {
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        seed(journal, RunId("h7-diverged"), OperationStatus.SUCCEEDED, payload = """{"charge":999}""")

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-diverged"))

        assertEquals(0, transport.calls, "the recorded request was for a different body")
        assertTrue(outcome is RunOutcome.Failure, "got $outcome")
    }

    // ── R7: "never" is per durable identity, not once ever ──────────────────

    @Test
    fun `R7 a NEW run sends again, because a new run is a new invocation`() = runBlocking {
        // The law has a boundary and it is worth stating: replay and resume do not
        // re-send; a genuinely new run does. Jenkins behaves the same way, and a
        // product that refused to re-run http.request on a new build would be broken
        // in a way nobody asked for. Reading NEVER as a global latch would also
        // silently make every pipeline un-re-runnable.
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        seed(journal, RunId("h7-old-run"), OperationStatus.FAILED)
        val coordinator = coordinator(transport, clock, journal)

        val resumed = coordinator.run(pipeline(), RunId("h7-old-run"))
        val fresh = coordinator.run(pipeline(), RunId("h7-new-run"))

        assertTrue(resumed is RunOutcome.Failure, "the resumed run is the refused one")
        assertEquals(RunOutcome.Success, fresh, "the new run is an ordinary invocation")
        assertEquals(1, transport.calls, "exactly one send, and it belongs to the NEW run")
    }

    // ── R8: the declaration, pinned by name so a future flip is loud ────────

    @Test
    fun `R8 the descriptor still says NEVER, None and NETWORKS`() {
        val descriptor = HttpRequestStep.definition.contract.descriptor

        assertEquals(ReplayPolicy.NEVER, descriptor.replayPolicy, "...")
        assertEquals(RecoveryPolicy.None, descriptor.recoveryPolicy, "...")
        assertEquals(listOf(Effect.NETWORKS), descriptor.effects, "...")
    }

    // ── R9: the Step itself does not retry ──────────────────────────────────

    @Test
    fun `R9 a timed-out send is one send, not an internal retry`() = runBlocking {
        val transport = RecordingTransport { HttpSendOutcome.Expired(30_000L) }
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-timeout"))

        assertEquals(
            1,
            transport.calls,
            "the Step asked once. A hidden retry here would be invisible in the journal, would " +
                "double every POST that timed out after the server acted, and would make the " +
                "author's own retry block a lie about how many attempts exist.",
        )
        assertTrue(outcome is RunOutcome.Failure, "got $outcome")
    }

    @Test
    fun `R9b a body cut short is one send and a NETWORK failure, not a resend`() = runBlocking {
        val transport = RecordingTransport {
            HttpSendOutcome.ResponseInterrupted("peer closed after headers", 65_536L)
        }
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)

        val outcome = coordinator(transport, clock, journal).run(pipeline(), RunId("h7-interrupted"))

        assertEquals(1, transport.calls, "one send")
        assertTrue(outcome is RunOutcome.Failure, "got $outcome")
    }

    // ── R10: a visible retry is a DIFFERENT durable identity ────────────────

    @Test
    fun `R10 retry attempt 2 is a fresh identity, so NEVER does not block it`() {
        // This is why `retry { httpRequest(...) }` is the supported way to retry, and
        // it is a fact about identity rather than about politeness: the retry block
        // gives each attempt its own operation id, the journal is keyed by operation
        // id, and therefore attempt 2 has no row of its own to be refused against.
        //
        // Composed from the real components rather than a real forked run: the
        // identity factory and the real replay policy. The claim is exactly "these two
        // decide between them", so demonstrating them separately is not a weaker form
        // of the claim.
        val firstAttempt = RetryIdentityFactory.childOperationId(
            runId = "h7-retry",
            stageIndex = 0,
            stepIndex = 1,
            parentBodyPath = emptyList(),
            attempt = 1,
            childIndex = 0,
            childPluginStepId = httpKey,
        )
        val secondAttempt = RetryIdentityFactory.childOperationId(
            runId = "h7-retry",
            stageIndex = 0,
            stepIndex = 1,
            parentBodyPath = emptyList(),
            attempt = 2,
            childIndex = 0,
            childPluginStepId = httpKey,
        )

        assertNotEquals(
            firstAttempt,
            secondAttempt,
            "two attempts of the same body MUST NOT share a durable identity. Sharing it " +
                "would let attempt 1's journal row refuse attempt 2, and `retry {}` would " +
                "work exactly once.",
        )

        val policy = DefaultEffectReplayPolicy()
        val effects = HttpRequestStep.definition.contract.descriptor.effects.toSet()
        assertEquals(
            ReplayDecision.ABORT,
            policy.decide(ReplayPolicy.NEVER, effects, hasJournalEntry = true, journaledOutcome = OperationStatus.FAILED),
            "attempt 1 already has a row, so resuming it aborts — the law still holds",
        )
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.NEVER, effects, hasJournalEntry = false, journaledOutcome = null),
            "attempt 2 is a different operation id, so its journal lookup is empty and it is " +
                "an ordinary first execution: visible, counted, and the author's decision.",
        )
    }

    @Test
    fun `R10b the author cannot make a resend invisible by naming a header`() = runBlocking {
        // `Idempotency-Key` is the standard way to say "this is safe to repeat", and the
        // temptation is to treat its presence as a licence to MEMOIZE or to retry. Only
        // the server knows what its key means. Nothing in the transport or the Step
        // inspects it, so the durable law is unchanged by what the author sent.
        val transport = RecordingTransport()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val keyed = HttpRequestCodec.encode(
            HttpRequestInput(
                url = url,
                method = HttpMethod.Post,
                body = """{"charge":1}""",
                customHeaders = listOf(dev.rubentxu.pipeline.v2.sdk.http.HttpHeader.of("Idempotency-Key", "abc-123")),
            ),
        ).value
        val input = OperationInput(
            stepId = httpKey.value,
            params = mapOf("payload" to JsonPrimitive(keyed)),
            runId = "h7-keyed",
            attempt = 1,
        )
        journal.append(
            RerunOperation(
                id = "h7-keyed-s0-0",
                fingerprint = engineFingerprint(input),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )

        val outcome = coordinator(transport, clock, journal).run(pipeline(keyed), RunId("h7-keyed"))

        assertEquals(0, transport.calls, "a header the server interprets cannot change our law")
        assertTrue(outcome is RunOutcome.Failure, "got $outcome")
    }
}
