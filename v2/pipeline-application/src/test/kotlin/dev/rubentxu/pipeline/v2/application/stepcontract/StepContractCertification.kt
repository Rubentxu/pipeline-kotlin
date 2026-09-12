package dev.rubentxu.pipeline.v2.application.stepcontract

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * StepContractCertification harness — LFC-2E1 generic G6 contract engine.
 *
 * ONE implementation of every GENERIC StepContractSuite row (identity, contract
 * completeness, codec laws, canonical envelope, registry resolution/freshness,
 * capability declaration/admission/missing-capability, coordinator success, typed
 * failure, fresh durable, the replay matrix, observability, real registry path).
 * A suite NEVER re-implements a generic row: doc/test drift like a coverage-matrix
 * mismatch becomes impossible because there is nothing left to drift.
 *
 * Each row method maps 1:1 to a coverage-matrix row name (see
 * `docs/v2/07-uat/STEP_CONTRACT_HARNESS_DESIGN.md`). Suites keep explicit `@Test`
 * methods — one per row — so JUnit XML keeps the row names and the assertion power
 * stays visible; the `@Test` bodies are one-line delegations.
 *
 * Step-specific semantics (payload field assertions, conditional capability
 * exposure, idempotence payloads) remain bespoke rows in the suite, built on the
 * shared [SuiteContext] helpers — the harness NEVER hides what is asserted.
 *
 * Zero-fabrication: every row drives the REAL registry seam
 * ([RegistryExecutionPreparation] → capability admission → handler → durable
 * journal → events). No fake returns, no stubbed outcomes.
 */
class StepContractCertification<I : Any, O : Any> internal constructor(
    val definition: StepDefinition<I, O>,
    /** Typed valid input used by every generic row. */
    val canonicalInput: I,
    /** Expected byte-identical legacy dsl-v1 envelope for [canonicalInput]. */
    val canonicalEnvelope: String,
    /** Representative typed output for the output codec laws. */
    val sampleOutput: O,
    /** Pinned expected descriptor effects (contract-completeness row). */
    val expectedEffects: Set<Effect>,
    /** Pinned expected descriptor replayPolicy. */
    val expectedReplayPolicy: ReplayPolicy,
    /** Pinned EXACT set of required capabilities (declaration + missing-cap rows). */
    val expectedCapabilities: Set<StepCapability>,
    /**
     * Pinned replay decision for (policy, effects, journaled SUCCEEDED) — the
     * suite states the law, the harness verifies execution follows it (row 17)
     * and that the policy agrees (row 17b).
     */
    val expectedReplayDecision: ReplayDecision,
    /** Input decode MUST fail closed for every probe. */
    val inputRejections: List<RejectionProbe>,
    /** Output decode MUST fail closed for every probe. */
    val outputRejections: List<RejectionProbe>,
) {
    data class RejectionProbe(val label: String, val encoded: EncodedStepValue)

    val contract: StepContract<I, O> get() = definition.contract
    val key get() = contract.key

    // ===== shared fixtures =====

    /** Canonical OpaqueStepNode carrying the legacy dsl-v1 envelope. */
    fun canonicalNode(nodeId: String = "contract/${key.value}"): OpaqueStepNode = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = key,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = canonicalEnvelope,
        ),
    )

    fun pipeline(vararg nodes: OpaqueStepNode): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("${key.value.replace('.', '-')}-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    /** Fresh coordinator + journal + event store over the PRODUCTION registry. */
    fun freshContext(eventStore: InMemoryEventStore = InMemoryEventStore()): SuiteContext {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val registry = CoreStepRegistryFactory.registry()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("step-contract-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry,
        )
        return SuiteContext(coord, journal, eventStore, registry)
    }

    /**
     * An independent [CanonicalRuntimeContext] for boundary-level rows. Pass
     * `controlDirRoot = null` for conditional-exposure probes.
     */
    fun runtimeContext(
        eventStore: InMemoryEventStore = InMemoryEventStore(),
        controlDirRoot: java.nio.file.Path? = Files.createTempDirectory("step-contract-boundary-"),
        opId: String = "${key.value}-boundary",
    ): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId(opId, 0, 0),
        runId = opId,
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = controlDirRoot,
        eventSink = eventStore,
    )

    class SuiteContext(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val registry: StepRegistry,
    ) {
        suspend fun run(pipeline: CompiledPipeline, runId: String): RunOutcome =
            coord.run(pipeline, RunId(runId))

        fun journalRows(runId: String) = journal.listForRun(runId)

        fun events(runId: String): List<DomainEvent> = eventStore.eventsFor(runId).toList()

        inline fun <reified E : DomainEvent> eventsOf(runId: String): List<E> =
            events(runId).filterIsInstance<E>()
    }

    // ===== GENERIC ROWS (ONE implementation each) =====

    /** Row 1 — identity: KEY ↔ descriptor coherence + duplicate registration fails closed. */
    fun row01_identity() {
        assertEquals(key.value, contract.descriptor.stepId, "descriptor stepId MUST equal the StepKey")
        assertFalse(contract.descriptor.name.isBlank(), "descriptor name MUST NOT be blank")
        val r = InMemoryStepRegistry()
        definition.let { r.register(it) }
        assertTrue(
            runCatching { r.register(definition) }.isFailure,
            "duplicate registration of '${key.value}' must fail closed",
        )
    }

    /** Row 2 — contract completeness: pinned effects, replay policy, capabilities, codecs. */
    fun row02_contractCompleteness() {
        assertEquals(key, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals(
            expectedEffects,
            contract.descriptor.effects.toSet(),
            "descriptor effects MUST equal the pinned expectation",
        )
        assertEquals(
            expectedReplayPolicy,
            contract.descriptor.replayPolicy,
            "descriptor replayPolicy MUST equal the pinned expectation",
        )
        assertEquals(
            expectedCapabilities,
            contract.requiredCapabilities,
            "requiredCapabilities MUST equal the pinned exact set",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    /** Row 3 — input codec law: encode(canonicalInput) round-trips. */
    fun row03_inputCodecRoundTrip() {
        val encoded = contract.inputCodec.encode(canonicalInput)
        val decoded = contract.inputCodec.decode(encoded)
        assertEquals(canonicalInput, decoded, "input codec MUST round-trip the canonical input")
    }

    /** Row 4 — input codec rejection: every pinned probe fails closed at decode. */
    fun row04_inputCodecRejection(probe: RejectionProbe) {
        assertTrue(
            runCatching { contract.inputCodec.decode(probe.encoded) }.isFailure,
            "input decode MUST fail closed on probe '${probe.label}'",
        )
    }

    /** Row 5 — output codec law: encode/decode round-trip AND durable string round-trip. */
    fun row05_outputCodecRoundTrip() {
        val encoded = contract.outputCodec.encode(sampleOutput)
        assertEquals(sampleOutput, contract.outputCodec.decode(encoded), "output codec MUST round-trip")
        assertEquals(
            sampleOutput,
            contract.outputCodec.decode(EncodedStepValue(encoded.value)),
            "durable persistence (string round-trip) MUST decode to the same values",
        )
    }

    /** Row 6 — output codec rejection: every pinned probe fails closed at decode. */
    fun row06_outputCodecRejection(probe: RejectionProbe) {
        assertTrue(
            runCatching { contract.outputCodec.decode(probe.encoded) }.isFailure,
            "output decode MUST fail closed on probe '${probe.label}'",
        )
    }

    /** Row 7 — canonical envelope: byte-identical to the pinned legacy dsl-v1 form. */
    fun row07_canonicalEnvelope() {
        assertEquals(
            canonicalEnvelope,
            contract.inputCodec.encode(canonicalInput).value,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope (durable fingerprint identity)",
        )
    }

    /** Row 8 — production registry resolution: factory resolves KEY to the canonical definition. */
    fun row08_registryResolution() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(key), "production registry must contain '${key.value}'")
        val resolved = registry.definition(key)
        assertNotNull(resolved, "production registry MUST resolve '${key.value}' to a StepDefinition")
        assertSame(definition, resolved, "production registry MUST return the canonical definition instance")
    }

    /** Row 9 — factory freshness: fresh registries per call, consistent definition instance. */
    fun row09_registryFactoryFreshness() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(key) && r2.contains(key))
        assertSame(r1.definition(key), r2.definition(key))
    }

    /** Row 10 — capability declaration: EXACTLY the pinned set. */
    fun row10_capabilityDeclaration() {
        assertEquals(
            expectedCapabilities,
            definition.contract.requiredCapabilities,
            "declared capabilities MUST be exactly the pinned set; got ${definition.contract.requiredCapabilities}",
        )
    }

    /** Row 11 — capability admission: all pinned capabilities available → Ready. */
    fun row11_capabilityAdmission() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = key,
                encodedInput = contract.inputCodec.encode(canonicalInput),
                availableCapabilities = expectedCapabilities,
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when all declared capabilities are available",
            )
        }
    }

    /** Row 12 — missing capability: admission rejects fail-closed naming the missing key. */
    fun row12_missingCapability(missing: StepCapability) {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = key,
                encodedInput = contract.inputCodec.encode(canonicalInput),
                availableCapabilities = expectedCapabilities - missing,
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing '${missing.key}' must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(missing.key),
                "Rejection MUST identify the missing capability '${missing.key}'; got ${rejected.reason}",
            )
        }
    }

    /** Row 14 — success via canonical coordinator: typed outcome + one terminal SUCCEEDED row. */
    fun row14_successViaCoordinator() {
        runBlocking {
            val ctx = freshContext()
            val outcome = ctx.run(pipeline(canonicalNode()), "${key.value.replace('.', '-')}-ok")
            assertEquals(RunOutcome.Success, outcome, "registry-routed run must succeed")
            val rows = ctx.journalRows("${key.value.replace('.', '-')}-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    /**
     * Row 15 — typed failure: a handler exception surfaces as RunOutcome.Failure
     * through the SAME registry composition (never silent success).
     */
    fun row15_typedFailure() {
        @Suppress("UNCHECKED_CAST")
        val throwingDefinition = object : StepDefinition<Any, Any> {
            override val contract: StepContract<Any, Any> =
                this@StepContractCertification.contract as StepContract<Any, Any>
            override val handler: StepHandler<Any, Any> =
                StepHandler { _: Any, _: dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext ->
                    throw IllegalStateException("'${key.value}' handler contract violated for test")
                }
        }
        val throwingRegistry = InMemoryStepRegistry().apply { register(throwingDefinition) }
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("step-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(canonicalNode()), RunId("${key.value.replace('.', '-')}-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    /** Row 16 — fresh durable: first execution writes exactly one terminal SUCCEEDED row. */
    fun row16_freshDurable() {
        runBlocking {
            val ctx = freshContext()
            val runId = "${key.value.replace('.', '-')}-first"
            ctx.run(pipeline(canonicalNode()), runId)
            val rows = ctx.journalRows(runId)
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    /**
     * Row 17 — the replay matrix, verified by OBSERVED execution (one law, one
     * implementation). The suite pins [expectedReplayDecision]; the harness asserts:
     *
     *  SKIP  → second run succeeds WITHOUT re-running the handler (StepStarted stays 1),
     *          journal keeps the single terminal SUCCEEDED row.
     *  RERUN → second run succeeds AND re-executes the handler (StepStarted becomes 2),
     *          journal keeps the single terminal SUCCEEDED row (one row per op identity).
     *  ABORT → second run fails closed, handler MUST NOT run again.
     */
    fun row17_replayMatrix() {
        runBlocking {
            val ctx = freshContext()
            val runId = "${key.value.replace('.', '-')}-replay"
            val first = ctx.run(pipeline(canonicalNode()), runId)
            assertEquals(RunOutcome.Success, first, "fresh execution must succeed")
            assertEquals(
                1,
                ctx.eventsOf<StepStarted>(runId).size,
                "first execution emits exactly one StepStarted",
            )
            when (expectedReplayDecision) {
                ReplayDecision.SKIP -> {
                    val second = ctx.run(pipeline(canonicalNode()), runId)
                    assertEquals(RunOutcome.Success, second, "MEMOIZED replay must succeed")
                    assertEquals(
                        1,
                        ctx.eventsOf<StepStarted>(runId).size,
                        "SKIP must NOT re-run the handler (StepStarted count stays at 1)",
                    )
                }
                ReplayDecision.RERUN -> {
                    val second = ctx.run(pipeline(canonicalNode()), runId)
                    assertEquals(RunOutcome.Success, second, "RERUN replay must succeed")
                    assertEquals(
                        2,
                        ctx.eventsOf<StepStarted>(runId).size,
                        "RERUN must re-execute the handler (StepStarted count becomes 2)",
                    )
                }
                ReplayDecision.ABORT -> {
                    val second = ctx.run(pipeline(canonicalNode()), runId)
                    assertTrue(
                        second is RunOutcome.Failure,
                        "ABORT must fail closed on journaled history; got $second",
                    )
                    assertEquals(
                        1,
                        ctx.eventsOf<StepStarted>(runId).size,
                        "ABORT must NOT execute the handler again",
                    )
                }
            }
            val rows = ctx.journalRows(runId)
            assertEquals(1, rows.size, "replay reuses the SAME operation row (one row per op identity)")
            assertEquals(
                OperationStatus.SUCCEEDED,
                rows.single().status,
                "the terminal row stays SUCCEEDED after replay",
            )
        }
    }

    /** Row 17b — replay decision unit pin: the policy agrees with the pinned decision. */
    fun row17b_replayDecisionUnit() {
        assertEquals(
            expectedReplayDecision,
            DefaultEffectReplayPolicy().decide(
                replayPolicy = expectedReplayPolicy,
                effects = expectedEffects,
                hasJournalEntry = true,
                journaledOutcome = OperationStatus.SUCCEEDED,
            ),
            "the pinned decision MUST match DefaultEffectReplayPolicy for " +
                "(${expectedReplayPolicy}, ${expectedEffects}, journaled SUCCEEDED)",
        )
    }

    /** Row 18 — observability: every run emits a StepStarted/StepFinished pair. */
    fun row18_observability() {
        runBlocking {
            val ctx = freshContext()
            val runId = "${key.value.replace('.', '-')}-obs"
            ctx.run(pipeline(canonicalNode()), runId)
            val events = ctx.events(runId)
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    /**
     * Row 20 — real registry path: prepare → Ready(PreparedRegistryExecution) →
     * coordinator run success → boundary coexecute → typed output decodes.
     * [assertOutput] receives the decoded typed output for step-specific assertions.
     */
    fun row20_realRegistryPath(assertOutput: ((O) -> Unit)? = null) {
        runBlocking {
            val ctx = freshContext()
            val encoded = contract.inputCodec.encode(canonicalInput)

            val preparation = RegistryExecutionPreparation.prepare(
                registry = ctx.registry,
                key = key,
                encodedInput = encoded,
                availableCapabilities = expectedCapabilities,
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val runId = "${key.value.replace('.', '-')}-real"
            val outcome = ctx.run(pipeline(canonicalNode("real/${key.value}")), runId)
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Independently exercise the boundary coexecute path to prove the typed outcome.
            val boundaryEventStore = InMemoryEventStore()
            val boundaryCtx = runtimeContext(
                eventStore = boundaryEventStore,
                opId = "${key.value.replace('.', '-')}-real-boundary",
            )
            val result = RegistryExecutionBoundary.coexecute(ready.prepared as PreparedRegistryExecution, boundaryCtx)
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val typed = contract.outputCodec.decode(result.encodedOutput!!)
            assertOutput?.invoke(typed)
        }
    }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }
}

/**
 * Typed DSL entry point:
 *
 * ```kotlin
 * private val suite = certifyStep(CoreDeleteDirStep.definition, DeleteDirInput(path = ".")) {
 *     envelope("""{"kind":"deleteDir","path":"."}""")
 *     output(DeleteDirOutput(path = "/tmp/ws-a/workspace/test-0", deletedCount = 7, sha256 = "abc123"))
 *     expectEffects(Effect.WRITES_WORKSPACE)
 *     expectReplay(ReplayPolicy.MEMOIZED, ReplayDecision.RERUN)
 *     capability(DELETE_DIR_OPERATIONS_CAPABILITY)
 *     rejectInput(EncodedStepValue("""{"kind":"echo","path":"."}"""), because = "foreign kind")
 *     rejectOutput(EncodedStepValue("""{"kind":"echo","path":"/x","deletedCount":0,"sha256":"y"}"""), because = "non-deleteDir kind")
 * }
 * ```
 */
class StepCertificationBuilder<I : Any, O : Any>
internal constructor(
    private val definition: StepDefinition<I, O>,
    private val canonicalInput: I,
) {
    private var envelope: String = ""
    private var envelopeInitialized = false
    private var output: O? = null
    private var effects: Set<Effect>? = null
    private var replayPolicy: ReplayPolicy? = null
    private var replayDecision: ReplayDecision? = null
    private val capabilities = linkedSetOf<StepCapability>()
    private val inputRejections = mutableListOf<StepContractCertification.RejectionProbe>()
    private val outputRejections = mutableListOf<StepContractCertification.RejectionProbe>()

    /** Pins the expected byte-identical legacy dsl-v1 input envelope. REQUIRED. */
    fun envelope(expected: String) {
        envelope = expected
        envelopeInitialized = true
    }

    /** Pins the representative typed output for codec laws. REQUIRED. */
    fun output(sample: O) {
        output = sample
    }

    /** Pins the expected descriptor effects. REQUIRED. */
    fun expectEffects(expected: Set<Effect>) {
        effects = expected
    }

    /** Pins the expected descriptor replayPolicy and the journaled-replay decision. REQUIRED. */
    fun expectReplay(policy: ReplayPolicy, journaledDecision: ReplayDecision) {
        replayPolicy = policy
        replayDecision = journaledDecision
    }

    /** Declares one required capability (repeat for the full EXACT set). */
    fun capability(capability: StepCapability) {
        capabilities += capability
    }

    /** Pins an input envelope that MUST fail closed at decode (repeatable). */
    fun rejectInput(encoded: EncodedStepValue, because: String) {
        inputRejections += StepContractCertification.RejectionProbe(because, encoded)
    }

    /** Pins an output envelope that MUST fail closed at decode (repeatable). */
    fun rejectOutput(encoded: EncodedStepValue, because: String) {
        outputRejections += StepContractCertification.RejectionProbe(because, encoded)
    }

    internal fun build(): StepContractCertification<I, O> {
        val contract = definition.contract
        require(envelopeInitialized) { "envelope(...) is REQUIRED: pin the canonical dsl-v1 envelope" }
        check(output != null) { "output(...) is REQUIRED: pin a representative typed output" }
        check(effects != null) { "expectEffects(...) is REQUIRED: pin the descriptor effects" }
        check(replayPolicy != null && replayDecision != null) {
            "expectReplay(...) is REQUIRED: pin the replayPolicy and journaled decision"
        }
        require(
            contract.inputCodec.encode(canonicalInput).value == envelope,
        ) {
            "pinned envelope does not match inputCodec.encode(canonicalInput): " +
                "expected '${contract.inputCodec.encode(canonicalInput).value}', got '$envelope'"
        }
        return StepContractCertification(
            definition = definition,
            canonicalInput = canonicalInput,
            canonicalEnvelope = envelope,
            sampleOutput = output!!,
            expectedEffects = effects!!,
            expectedReplayPolicy = replayPolicy!!,
            expectedCapabilities = capabilities.toSet(),
            expectedReplayDecision = replayDecision!!,
            inputRejections = inputRejections.toList(),
            outputRejections = outputRejections.toList(),
        )
    }
}

fun <I : Any, O : Any> certifyStep(
    definition: StepDefinition<I, O>,
    canonicalInput: I,
    configure: StepCertificationBuilder<I, O>.() -> Unit,
): StepContractCertification<I, O> =
    StepCertificationBuilder(definition, canonicalInput).apply(configure).build()
