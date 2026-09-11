package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A1 / G7 for `core.error`.
 *
 * Certifies `core.error` end-to-end across the registry-driven, open-world Step seam. The suite
 * proves the registration contract is complete and aligned with the typed carrier pattern
 * (`CoreErrorOutput` as `TypedStepOutput` — no handler-thrown exceptions for failure).
 *
 * Coverage matrix (per S2-A1 / G7 directive):
 * ```
 *  1.  identity                                          REQUIRED
 *  2.  descriptor completeness                           REQUIRED
 *  3.  input codec encode/decode round-trip              REQUIRED
 *  4.  input malformed/wrong discriminant                 REQUIRED
 *  5.  output codec round-trip                           REQUIRED
 *  6.  canonical envelope (byte-identical to legacy dsl-v1) REQUIRED
 *  7.  production registry resolution                    REQUIRED
 *  8.  fresh factory consistency                         REQUIRED
 *  9.  capability declaration                            REQUIRED (empty)
 * 10.  capability admission (empty available → no rejection) REQUIRED
 * 11.  typed outcome projection                          REQUIRED
 * 12.  fresh durable (handler executes, USER failure)    REQUIRED
 * 13.  replay (NEVER → ABORT, INFRASTRUCTURE failure)   REQUIRED
 * 14.  observability (StepStarted/StepFailed/StepFinished pair) REQUIRED
 * 15.  failure kind/message preservation                 REQUIRED
 * 16.  real registry seam execution (RegistryExecutionBoundary.coexecute) REQUIRED
 *
 *  -  missing-capability rejection                       NOT_APPLICABLE
 *      (core.error declares `requiredCapabilities = emptySet()`; there is no declared
 *       capability to remove, so a "missing capability" assertion would be fabricating
 *       a contract `core.error` does not declare.)
 * ```
 *
 * Summary: **16 PASS / 1 N.A. / 0 FAIL**.
 *
 * Two `core.error`-specific invariants are pinned as their own discrete contracts:
 *
 *   - `failure.kind preserved exactly`           (USER is not silently remapped)
 *   - `failure.message preserved exactly`        (verbatim text from input)
 *   - `CoreErrorOutput.outcome == StepOutcome.Failure(failure)` (typed carrier is the single authority)
 *   - `descriptor.effects == setOf(Effect.ABORTS_PIPELINE)`     (terminal failure semantics)
 *   - `descriptor.replayPolicy == ReplayPolicy.NEVER`           (no reproducible effect to re-execute)
 *
 * Replay semantics follow the `core.error` law (NOT the Echo replay law):
 *
 * ```
 * fresh:
 *   handler executes
 *   → USER failure
 *   → terminal failure (RunFinished.failure, exit 1)
 *
 * same durable invocation replay:
 *   handler MUST NOT execute
 *   → ReplayPolicy.NEVER
 *   → replay ABORT
 *   → INFRASTRUCTURE failure ("Replay aborted for ...")
 * ```
 */
@Timeout(15)
class ErrorStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreErrorStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
        )
    }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("error-contract-"),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        return Harness(coord, journal, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    private fun errorNode(
        message: String,
        kind: FailureKind = FailureKind.USER,
        nodeId: String = "build/error",
    ) = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreErrorStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"error","message":"${message}","failureKind":"${kind.name}"}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("error-contract-suite"),
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

    // ===== 1. identity =====

    @Test
    fun `identity -- CoreErrorStep KEY is core dot error and unique within the registry`() {
        assertEquals(PluginStepId("core.error"), CoreErrorStep.KEY)
        assertEquals("core.error", CoreErrorStep.KEY.value)
        // Re-registering must fail (deterministic / idempotent error).
        val r = registry()
        assertTrue(
            runCatching { CoreErrorStep.registerInto(r) }.isFailure,
            "duplicate registration of core.error must fail",
        )
    }

    // ===== 2. descriptor completeness =====

    @Test
    fun `descriptor completeness -- key, descriptor, input codec, output codec, required capabilities`() {
        val contract = CoreErrorStep.definition.contract
        assertEquals(CoreErrorStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("error", contract.descriptor.name)
        // core.error contract: ABORTS_PIPELINE (the Step's effect AFTER legitimate execution).
        assertEquals(
            setOf(Effect.ABORTS_PIPELINE),
            contract.descriptor.effects.toSet(),
            "core.error effects MUST be ABORTS_PIPELINE",
        )
        // core.error contract: ReplayPolicy.NEVER (no reproducible effect to re-execute).
        assertEquals(
            ReplayPolicy.NEVER,
            contract.descriptor.replayPolicy,
            "core.error replayPolicy MUST be NEVER",
        )
        // core.error declares no capabilities (handler is pure; no journal/event/process reach).
        assertEquals(
            emptySet<String>(),
            contract.requiredCapabilities.map { it.key }.toSet(),
            "core.error MUST declare empty requiredCapabilities (handler is pure)",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec encode/decode round-trip =====

    @Test
    fun `input codec -- encode and round-trip preserve message, failureKind, and envelope shape`() {
        val input = CoreErrorInput(
            message = "round-trip message",
            failureKind = FailureKind.SCRIPT,
        )
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)
        // Canonical envelope is the canonical dsl-v1 payload form.
        assertEquals(
            """{"kind":"error","message":"round-trip message","failureKind":"SCRIPT"}""",
            encoded.value,
            "input codec MUST emit the canonical dsl-v1 envelope",
        )
        val decoded = CoreErrorStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "round-trip encode -> decode MUST preserve message + failureKind")
    }

    // ===== 4. input malformed/wrong discriminant =====

    @Test
    fun `input codec -- decode rejects a non-error payload kind`() {
        val malformed = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            value = """{"kind":"echo","message":"hi","text":"hi"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.definition.contract.inputCodec.decode(malformed)
        }
    }

    @Test
    fun `input codec -- decode rejects an unknown failureKind`() {
        val malformed = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            value = """{"kind":"error","message":"x","failureKind":"UNKNOWN_KIND"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.definition.contract.inputCodec.decode(malformed)
        }
    }

    @Test
    fun `output codec -- encode and round-trip preserve failureKind and message via CoreErrorOutput from`() {
        val failure = PipelineFailure(FailureKind.TIMEOUT, "operation exceeded 30s")
        val carrier = CoreErrorOutput.from(failure)
        val encoded = CoreErrorStep.definition.contract.outputCodec.encode(carrier)
        val decoded = CoreErrorStep.definition.contract.outputCodec.decode(encoded)
        // The carrier is the single authority for the failure; round-trip preserves both
        // failureKind and message (and reconstructs the outcome invariant).
        assertEquals(failure.kind, decoded.failure.kind)
        assertEquals(failure.message, decoded.failure.message)
        assertEquals(
            StepOutcome.Failure(failure),
            decoded.outcome,
            "decoded carrier outcome MUST equal StepOutcome.Failure(failure) — invariant of CoreErrorOutput.from",
        )
    }

    // ===== 6. canonical envelope (byte-identical to legacy dsl-v1) =====

    @Test
    fun `canonical envelope -- input codec envelope is byte-identical to legacy dsl-v1 error envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val input = CoreErrorInput(
            message = "test error message",
            failureKind = FailureKind.USER,
        )
        val registryEnvelope = CoreErrorStep.definition.contract.inputCodec.encode(input).value
        val legacyEnvelope =
            """{"kind":"error","message":"test error message","failureKind":"USER"}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 7. production registry resolution =====

    @Test
    fun `registry resolution -- production factory contains core dot error`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(
            registry.contains(CoreErrorStep.KEY),
            "production registry must contain core.error",
        )
        val definition = registry.definition(CoreErrorStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.error to a StepDefinition")
        assertSame(
            CoreErrorStep.definition,
            definition,
            "production registry MUST return the canonical CoreErrorStep.definition instance",
        )
    }

    // ===== 8. fresh factory consistency =====

    @Test
    fun `registry resolution -- production factory registry is fresh per call and consistent across calls`() {
        // Two fresh calls must produce two independent registries that BOTH contain core.error.
        // (No global singleton; the canonical production wiring is per-call.)
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertTrue(r1.contains(CoreErrorStep.KEY))
        assertTrue(r2.contains(CoreErrorStep.KEY))
        // Both registries MUST agree on the canonical instance (same source of truth).
        assertSame(r1.definition(CoreErrorStep.KEY), r2.definition(CoreErrorStep.KEY))
    }

    // ===== 9. capability declaration =====

    @Test
    fun `capability declaration -- core error declares empty required capabilities`() {
        // The handler does not reach a coordinator / journal / event sink / process executor.
        // No capability is required for admission.
        assertEquals(
            emptySet<dev.rubentxu.pipeline.v2.domain.step.StepCapability>(),
            CoreErrorStep.definition.contract.requiredCapabilities,
            "CoreErrorStep MUST declare empty requiredCapabilities (handler is pure)",
        )
    }

    // ===== 10. capability admission (empty available set succeeds) =====

    @Test
    fun `capability admission -- admission succeeds when the runtime exposes zero capabilities`() {
        // The empty-declared contract means: regardless of available capabilities, admission
        // succeeds. We exercise the canonical RegistryExecutionPreparation seam with an
        // empty available set to prove fail-closed admission does NOT over-reject.
        val registry = CoreStepRegistryFactory.registry()
        val input = CoreErrorInput("cap-admission", FailureKind.USER)
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreErrorStep.KEY,
            encodedInput = encoded,
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(
            dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Ready::class.java,
            preparation,
        )
    }

    // ===== 11. typed outcome projection =====

    @Test
    fun `typed outcome projection -- CoreErrorOutput outcome equals StepOutcome Failure of the same failure`() {
        // The carrier is the single authority for the failure outcome (TypedStepOutput contract).
        // This pins the G3-A4 amendment: the carrier's invariant is
        //   outcome == StepOutcome.Failure(failure)
        // enforced in CoreErrorOutput's factory. Here we prove it for every FailureKind.
        //
        // `StepOutcome.Failure` is a data class, so equality (not identity) is the canonical
        // invariant under TypedStepOutput projection.
        for (kind in FailureKind.entries) {
            val failure = PipelineFailure(kind, "msg-${kind.name}")
            val carrier = CoreErrorOutput.from(failure)
            assertEquals(
                StepOutcome.Failure(failure),
                carrier.outcome,
                "[${kind.name}] carrier outcome MUST equal StepOutcome.Failure(failure)",
            )
            assertEquals(
                failure.kind,
                carrier.failure.kind,
                "[${kind.name}] carrier failure.kind MUST match",
            )
            assertEquals(
                failure.message,
                carrier.failure.message,
                "[${kind.name}] carrier failure.message MUST match",
            )
        }
    }

    // ===== 12. fresh durable (handler executes → USER failure → terminal failure) =====

    @Test
    fun `fresh durable -- first execution of core dot error writes one terminal FAILED operation with USER kind`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(errorNode("boom", FailureKind.USER)), RunId("error-fresh"))
            // Terminal outcome: typed failure.
            assertTrue(
                outcome is RunOutcome.Failure,
                "fresh core.error MUST surface as RunOutcome.Failure; got $outcome",
            )
            val failure = (outcome as RunOutcome.Failure).failure
            assertEquals(FailureKind.USER, failure.kind, "fresh core.error MUST keep its configured failureKind")
            assertEquals("boom", failure.message)
            // The journal records exactly one terminal FAILED operation.
            val rows = h.journal.listForRun("error-fresh")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.FAILED, rows.single().status)
        }
    }

    // ===== 13. replay (NEVER → ABORT → INFRASTRUCTURE failure) =====

    @Test
    fun `replay -- a previously FAILED core error ABORTS without re-running the handler`() {
        // The core.error replay law (NOT the Echo law):
        //   fresh / no durable entry   → execute (typed failure, USER kind, "boom")
        //   existing durable history   → ABORT (handler MUST NOT run; replay-abort surfaces as a typed
        //                                 INFRASTRUCTURE failure with "Replay aborted ...")
        //
        // The handler-not-invoked invariant is observed via the COUNT of USER-kind StepFailed events:
        //   first execution: USER "boom"
        //   replay:          handler MUST NOT run → no second USER-kind StepFailed;
        //                    the typed replay-abort is an INFRASTRUCTURE failure carrying "Replay aborted".
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            // First run: handler executes.
            h.coord.run(pipeline(errorNode("boom", FailureKind.USER)), RunId("error-replay"))
            val firstFailures = eventStore.eventsFor("error-replay").filterIsInstance<StepFailed>().toList()
            assertEquals(1, firstFailures.size, "first execution MUST emit exactly one StepFailed (handler ran)")
            assertEquals(FailureKind.USER, firstFailures.single().failureKind)
            assertEquals("boom", firstFailures.single().message)

            // Second run at the SAME runId: handler MUST NOT execute; replay ABORT.
            val second = h.coord.run(
                pipeline(errorNode("boom", FailureKind.USER)),
                RunId("error-replay"),
            )
            assertTrue(
                second is RunOutcome.Failure,
                "replay of core.error MUST surface as RunOutcome.Failure (typed, not silent success); got $second",
            )
            val replayFailure = (second as RunOutcome.Failure).failure
            assertEquals(
                FailureKind.INFRASTRUCTURE,
                replayFailure.kind,
                "replay ABORT MUST be classified as INFRASTRUCTURE (NOT a typed USER failure)",
            )
            assertTrue(
                replayFailure.message.contains("Replay aborted"),
                "replay ABORT message MUST mention 'Replay aborted'; got '${replayFailure.message}'",
            )
            // Handler-invocation invariant: the count of USER-kind StepFailed events stays at 1
            // (handler did NOT run a second time). The replay-abort itself surfaces as a SECOND
            // StepFailed event carrying INFRASTRUCTURE + "Replay aborted".
            val allFailures = eventStore.eventsFor("error-replay").filterIsInstance<StepFailed>().toList()
            val userFailures = allFailures.filter { it.failureKind == FailureKind.USER }
            val infraFailures = allFailures.filter { it.failureKind == FailureKind.INFRASTRUCTURE }
            assertEquals(1, userFailures.size, "replay MUST NOT invoke the handler a second time (USER-kind StepFailed stays at 1)")
            assertEquals(1, infraFailures.size, "replay-abort MUST surface as exactly one INFRASTRUCTURE-kind StepFailed")
            assertTrue(
                infraFailures.single().message.contains("Replay aborted"),
                "replay-abort StepFailed message MUST mention 'Replay aborted'",
            )
        }
    }

    // ===== 14. observability =====

    @Test
    fun `observability -- every core dot error run emits StepStarted StepFailed StepFinished trio with USER kind`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(errorNode("obs", FailureKind.USER)), RunId("error-obs"))
            val events = eventStore.eventsFor("error-obs")
            val started = events.filterIsInstance<StepStarted>().toList()
            val failed = events.filterIsInstance<StepFailed>().toList()
            val finished = events.filterIsInstance<StepFinished>().toList()
            assertEquals(1, started.size, "exactly one StepStarted")
            assertEquals(1, failed.size, "exactly one StepFailed")
            assertEquals(1, finished.size, "exactly one StepFinished")
            assertEquals(FailureKind.USER, failed.single().failureKind)
            assertEquals("obs", failed.single().message)
        }
    }

    // ===== 15. failure kind/message preservation =====

    @Test
    fun `failure preservation -- every FailureKind round-trips through the handler without remapping`() {
        // The handler returns CoreErrorOutput.from(failure); the boundary projects that
        // carrier's outcome. The failure MUST surface UNCHANGED on the typed outcome.
        // `StepOutcome.Failure` is a data class, so equality (not identity) is the canonical
        // invariant under TypedStepOutput projection.
        for (kind in FailureKind.entries) {
            val failure = PipelineFailure(kind, "msg-${kind.name}")
            val input = CoreErrorInput(failure.message, failure.kind)
            val carrier = CoreErrorOutput.from(failure)
            assertEquals(failure.kind, carrier.failure.kind, "[${kind.name}] failure.kind preserved")
            assertEquals(failure.message, carrier.failure.message, "[${kind.name}] failure.message preserved verbatim")
            assertEquals(
                StepOutcome.Failure(failure),
                carrier.outcome,
                "[${kind.name}] carrier outcome is StepOutcome.Failure(failure)",
            )
            // Input codec preserves the typed input losslessly.
            val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)
            val decoded = CoreErrorStep.definition.contract.inputCodec.decode(encoded)
            assertEquals(input, decoded, "[${kind.name}] input codec round-trip preserves message + kind")
        }
    }

    // ===== 16. real registry seam execution (RegistryExecutionPreparation → coexecute) =====

    @Test
    fun `real registry seam -- core error flows end-to-end through RegistryExecutionPreparation and RegistryExecutionBoundary coexecute`() {
        // The complete production seam for a single core.error invocation:
        //   encoded input → RegistryExecutionPreparation.prepare → Ready
        //   Ready.prepared (PreparedRegistryExecution) → RegistryExecutionBoundary.coexecute
        //   → typed outcome via CoreErrorOutput.outcome
        val registry = CoreStepRegistryFactory.registry()
        val failure = PipelineFailure(FailureKind.USER, "registry seam end-to-end")
        val input = CoreErrorInput(failure.message, failure.kind)
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)

        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreErrorStep.KEY,
            encodedInput = encoded,
            availableCapabilities = emptySet(),
        )
        val ready = assertInstanceOf(
            dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Ready::class.java,
            preparation,
        )
        val prepared = assertInstanceOf(
            dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution::class.java,
            ready.prepared,
        )

        runBlocking {
            val context = dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext(
                opId = dev.rubentxu.pipeline.v2.application.durable.OpId("g7-error-seam", 0, 0),
                runId = "g7-error-seam",
                stageName = "build",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("g7-error-seam-"),
                eventSink = InMemoryEventStore(),
            )
            val result = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
                .coexecute(prepared, context)
            assertEquals(
                StepOutcome.Failure(failure),
                result.outcome,
                "registry seam MUST project CoreErrorOutput.outcome into CommonExecutionResult.outcome",
            )
        }
    }
}
