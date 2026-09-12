package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.application.stepcontract.certifyStep
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A7 / G6 for `core.deleteDir`, expressed through the
 * generic [dev.rubentxu.pipeline.v2.application.stepcontract.StepContractCertification]
 * harness. Each `@Test` maps 1:1 to a coverage-matrix row; generic rows are one-line
 * delegations to the harness (ONE implementation of the law), and only deleteDir-specific
 * semantics live here.
 *
 * deleteDir-specific semantics (S2-A7/G3-fix):
 *  - input envelope is `{"kind":"deleteDir","path":"."}` (path defaults to ".");
 *  - effects are `{ WRITES_WORKSPACE }`, replayPolicy is MEMOIZED → journaled decision RERUN;
 *  - the DELETE_DIR_OPERATIONS capability is conditionally exposed only when
 *    `controlDirRoot != null` (row 12b, bespoke);
 *  - replay idempotence: re-execution on the already-deleted workspace emits
 *    `DirDeleted` with `deletedCount=0` (row 17, bespoke payload assertions on top of
 *    the generic RERUN matrix row);
 *  - durable observation: one `DirDeleted` event per fresh execution (row 19, bespoke).
 *
 * Coverage matrix (row → harness builder):
 * ```
 *  1.  identity                                    → suite.row01_identity()
 *  2.  contract completeness                       → expectEffects/expectReplay/capability pins + row02
 *  3.  input codec round-trip (default path)       → row03
 *  3b. legacy envelope without path defaults '.'   → bespoke (deleteDir defaulting law)
 *  4.  input codec rejection (foreign envelope)    → rejectInput pin + row04
 *  5.  output codec round-trip                     → output pin + row05
 *  6.  output codec rejection (non-deleteDir kind) → rejectOutput pin + row06
 *  7.  canonical envelope (byte-identical dsl-v1)  → envelope pin (builder fail-fast) + row07
 *  8.  production registry resolution              → row08
 *  9.  fresh factory consistency                   → row09
 * 10.  capability declaration (exactly the 1 cap)  → capability pin + row10
 * 11.  capability admission (available → Ready)    → row11
 * 12.  missing DELETE_DIR_OPERATIONS rejects       → row12
 * 12b. conditional exposure (controlDirRoot=null)  → bespoke
 * 14.  success via canonical coordinator           → row14
 * 15.  typed failure (handler exception)           → row15
 * 16.  fresh durable (1 terminal SUCCEEDED row)    → row16
 * 17.  replay (MEMOIZED + WRITES_WORKSPACE RERUN)  → expectReplay pin + row17 + bespoke payload
 * 17b. replay decision unit pin                   → row17b
 * 18.  observability (StepStarted/Finished pair)   → row18
 * 19.  DirDeleted event payload                    → bespoke
 * 20.  real registry path scenario                 → row20
 * ```
 */
@Timeout(30)
class CoreDeleteDirStepContractSuiteTest {

    private val suite = certifyStep(CoreDeleteDirStep.definition, DeleteDirInput(path = ".")) {
        envelope("""{"kind":"deleteDir","path":"."}""")
        output(DeleteDirOutput(path = "/tmp/ws-a/workspace/test-0", deletedCount = 7, sha256 = "abc123"))
        expectEffects(setOf(Effect.WRITES_WORKSPACE))
        expectReplay(ReplayPolicy.MEMOIZED, ReplayDecision.RERUN)
        capability(DELETE_DIR_OPERATIONS_CAPABILITY)
        rejectInput(EncodedStepValue("""{"kind":"echo","path":"."}"""), because = "foreign envelope kind")
        rejectOutput(
            EncodedStepValue("""{"kind":"echo","path":"/x","deletedCount":0,"sha256":"y"}"""),
            because = "non-deleteDir kind",
        )
    }

    // ===== 1. identity =====

    @Test
    fun `identity — CoreDeleteDirStep KEY is core dot deleteDir and duplicate registration fails`() =
        suite.row01_identity()

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED`() =
        suite.row02_contractCompleteness()

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — default-path input encodes to the canonical legacy envelope and round-trips`() =
        suite.row03_inputCodecRoundTrip()

    // ===== 3b. input codec — legacy envelope without path (deleteDir-specific) =====

    @Test
    fun `codec input — decode accepts the legacy envelope without path and defaults to dot`() {
        val legacy = EncodedStepValue("""{"kind":"deleteDir"}""")
        val decoded = suite.contract.inputCodec.decode(legacy)
        assertEquals(DeleteDirInput(path = "."), decoded, "missing path MUST default to '.'")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() =
        suite.row04_inputCodecRejection(suite.inputRejections.single())

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — DeleteDirOutput round-trips byte-identically`() = suite.row05_outputCodecRoundTrip()

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-deleteDir kind`() =
        suite.row06_outputCodecRejection(suite.outputRejections.single())

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 deleteDir envelope`() =
        suite.row07_canonicalEnvelope()

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot deleteDir`() = suite.row08_registryResolution()

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() =
        suite.row09_registryFactoryFreshness()

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core deleteDir declares EXACTLY DELETE_DIR_OPERATIONS`() =
        suite.row10_capabilityDeclaration()

    // ===== 11. capability admission (available) =====

    @Test
    fun `capability admission — the capability available prepares Ready`() = suite.row11_capabilityAdmission()

    // ===== 12. missing DELETE_DIR_OPERATIONS =====

    @Test
    fun `missing capability — admission rejects when DELETE_DIR_OPERATIONS is absent`() =
        suite.row12_missingCapability(DELETE_DIR_OPERATIONS_CAPABILITY)

    // ===== 12b. conditional exposure (controlDirRoot == null) — deleteDir-specific =====

    @Test
    fun `conditional exposure — controlDirRoot null means DELETE_DIR_OPERATIONS absent and admission rejects`() {
        // Capability-scoped controlDirRoot: CanonicalRuntimeCapabilityAccess must not throw on
        // controlDirRoot=null; the capability is simply NOT registered and core.deleteDir
        // admission fails closed.
        val access = CanonicalRuntimeCapabilityAccess(
            suite.runtimeContext(controlDirRoot = null, opId = "deletedir-null-ctrl"),
        )
        val available = access.available()
        assertTrue(
            DELETE_DIR_OPERATIONS_CAPABILITY !in available,
            "DELETE_DIR_OPERATIONS_CAPABILITY must NOT be available when controlDirRoot is null",
        )
        runBlocking {
            val preparation = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = suite.key,
                encodedInput = suite.contract.inputCodec.encode(suite.canonicalInput),
                availableCapabilities = available,
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "admission MUST reject when the capability was not exposed (controlDirRoot=null)",
            )
        }
    }

    // ===== 14. success via canonical coordinator =====

    @Test
    fun `success — registry-routed deleteDir SUCCEEDS with one terminal SUCCEEDED operation and typed output`() =
        suite.row14_successViaCoordinator()

    // ===== 15. typed failure (handler exception) =====

    @Test
    fun `typed failure — a registry-routed deleteDir whose handler throws surfaces as RunOutcome Failure`() =
        suite.row15_typedFailure()

    // ===== 16. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot deleteDir writes one terminal SUCCEEDED operation`() =
        suite.row16_freshDurable()

    // ===== 17. replay (MEMOIZED, WRITES_WORKSPACE → RERUN) + deleteDir idempotence payload =====

    @Test
    fun `replay — MEMOIZED deleteDir with WRITES_WORKSPACE reruns idempotently (deletedCount collapses to 0)`() {
        // Generic RERUN matrix law first (observed execution: handler re-runs, same op row).
        suite.row17_replayMatrix()

        // deleteDir-specific durable law: IDEMPOTENCE. Re-execution on the already-deleted
        // workspace emits a NEW DirDeleted with deletedCount=0 (same path), run still SUCCEEDS.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val ctx = suite.freshContext(eventStore)
            val runId = "deletedir-replay"
            ctx.run(suite.pipeline(suite.canonicalNode()), runId)

            val firstDeleted = ctx.eventsOf<dev.rubentxu.pipeline.v2.events.DirDeleted>(runId)
            assertEquals(1, firstDeleted.size, "first execution emits exactly one DirDeleted")
            assertTrue(
                firstDeleted.single().deletedCount >= 0,
                "first execution deletes whatever the fresh workspace contained",
            )

            ctx.run(suite.pipeline(suite.canonicalNode()), runId)
            val allDeleted = ctx.eventsOf<dev.rubentxu.pipeline.v2.events.DirDeleted>(runId)
            assertEquals(2, allDeleted.size, "rerun emits exactly one additional DirDeleted")
            assertEquals(
                0,
                allDeleted.last().deletedCount,
                "re-execution on the already-deleted workspace MUST observe deletedCount=0 (idempotent)",
            )
            assertEquals(
                allDeleted.first().path,
                allDeleted.last().path,
                "both observations MUST target the same canonical stage workspace path",
            )
        }
    }

    // ===== 17b. replay decision — policy unit property =====

    @Test
    fun `replay decision — DefaultEffectReplayPolicy reruns MEMOIZED WRITES_WORKSPACE with a SUCCEEDED entry`() =
        suite.row17b_replayDecisionUnit()

    // ===== 18. observability =====

    @Test
    fun `observability — every core dot deleteDir run emits a StepStarted StepFinished pair`() =
        suite.row18_observability()

    // ===== 19. DirDeleted event payload — deleteDir-specific =====

    @Test
    fun `DirDeleted event payload — exactly one event with workspace path, non-negative deletedCount, 64-hex sha256`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val ctx = suite.freshContext(eventStore)
            val runId = "deletedir-event"
            ctx.run(suite.pipeline(suite.canonicalNode()), runId)
            val events = ctx.eventsOf<dev.rubentxu.pipeline.v2.events.DirDeleted>(runId)
            assertEquals(1, events.size, "exactly one DirDeleted event is emitted")
            val event = events.single()
            assertEquals("DirDeleted", event.kind)
            assertTrue(
                event.path.endsWith("workspace/build-0"),
                "DirDeleted path MUST be the canonical stage workspace (workspace/build-0); got ${event.path}",
            )
            assertTrue(
                event.deletedCount >= 0,
                "DirDeleted deletedCount MUST be non-negative; got ${event.deletedCount}",
            )
            assertEquals(
                64,
                event.sha256.length,
                "DirDeleted sha256 MUST be a 64-char hex SHA-256; got ${event.sha256}",
            )
            // eventId is a uuid — assert it parses as such.
            runCatching { UUID.fromString(event.eventId) }
                .onFailure { throw AssertionError("DirDeleted eventId MUST be a UUID: ${event.eventId}", it) }
        }
    }

    // ===== 20. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator + capability bridge exercises core dot deleteDir end-to-end`() =
        suite.row20_realRegistryPath { typed ->
            assertTrue(
                typed.path.isNotEmpty(),
                "DeleteDirOutput.path MUST carry the deleted workspace path",
            )
            assertTrue(typed.deletedCount >= 0)
            assertEquals(64, typed.sha256.length)
        }
}
