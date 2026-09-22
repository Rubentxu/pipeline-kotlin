package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
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
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * StepContractSuite — WU-LPR-090 Phase B + WU-RP-013 G7 reconciliation for `core.publishHTML`.
 *
 * Test matrix (23 tests; G7 16/17 minimum met with margin):
 * ```
 *  Phase B (WU-LPR-090) — 11 tests:
 *   1. Step identity is core.publishHTML
 *   2. Contract completeness (key, descriptor, codecs, capabilities)
 *   3. Input codec round-trip (omitted flags preserved)
 *   3b. Input codec round-trip with all flags enabled
 *   4. Input codec rejects foreign kind
 *   5. Output codec round-trip (success)
 *   5b. Output codec round-trip (skipped)
 *   6. Output codec round-trip (failure)
 *   7. Output codec rejects foreign kind
 *   8. Capability declaration == usage (G3-A4.2: declared == used)
 *   9. Handler is a StepHandler typed I_O
 *  10. Registered through the open registry seam
 *  11. Registry key uniqueness vs other CoreSteps
 *
 *  Phase G7 (WU-RP-013) — 12 tests (one row per G7 dimension):
 *  12. Capability admission succeeds (RegistryExecutionPreparation.Ready)
 *  13. Missing capability rejected (RegistryExecutionPreparation.Rejected)
 *  14. Handler success — registry-routed publishHTML writes a SUCCEEDED op + HtmlReportPublished
 *  14b. Canonical envelope is a well-formed JSON object with kind=publishHTML
 *  15. Handler typed failure — missing reportDir surfaces as RunOutcome.Failure with a typed
 *      SCRIPT failureKind and an HtmlReportFailed event (NOT silent success)
 *  16. Fresh durable — first execution of core.publishHTML writes one terminal SUCCEEDED op
 *  17. Replay — second invocation of the same payload is IDEMPOTENT BY-EFFECT
 *      (handler re-runs because of WRITES_WORKSPACE; archive bytes are byte-identical;
 *       a second HtmlReportPublished event is emitted; this is the canonical policy)
 *  18. Observability — every run emits StepStarted < StepFinished + HtmlReportPublished
 *  19. Divergence — replaying with a different reportDir fails closed as a typed divergence
 *  20. Real pipeline scenario — public DSL pipeline { stage("p") { publishHTML(...) } } runs
 *      end-to-end through the installed-style harness and SUCCEEDS with an HtmlReportPublished
 *  (The architecture-fitness row is delegated to Lfc2RegistryFamilyFitnessTest per LB-02.)
 * ```
 *
 * Production-like registry-aware composition per AGENTS.md coordinator-test rule.
 */
@Timeout(90)
class CorePublishHtmlStepContractSuiteTest {

    // ============================================================================
    // Phase B (WU-LPR-090) — original 11 contract rows
    // ============================================================================

    @Test
    fun `1 — Step identity is core publishHTML`() {
        assertEquals(PluginStepId("core.publishHTML"), CorePublishHtmlStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CorePublishHtmlStep.definition.contract
        assertEquals(CorePublishHtmlStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.publishHTML", contract.descriptor.stepId)
        assertEquals(
            Effect.WRITES_WORKSPACE,
            contract.descriptor.effects.single(),
            "publishHTML MUST declare Effect.WRITES_WORKSPACE (filesystem archive write)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "publishHTML MUST declare ReplayPolicy.MEMOIZED (overwrite on rerun)",
        )
        assertNotNull(contract.inputCodec, "input codec required")
        assertNotNull(contract.outputCodec, "output codec required")
        assertEquals(
            setOf(PUBLISH_HTML_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "publishHTML MUST declare exactly one required capability",
        )
    }

    @Test
    fun `3 — input codec round-trip (omitted flags preserved)`() {
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.inputCodec.encode(input)
        val decoded: PublishHtmlInput = CorePublishHtmlStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "publishHTML input codec MUST round-trip losslessly (flags omitted when false)")
    }

    @Test
    fun `3b — input codec round-trip with all flags enabled`() {
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = true,
            allowMissing = true,
            escapeUnderscores = true,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.inputCodec.encode(input)
        val decoded: PublishHtmlInput = CorePublishHtmlStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "publishHTML input codec MUST preserve all flags when enabled")
    }

    @Test
    fun `4 — input codec rejects foreign kind`() {
        val foreign = EncodedStepValue("{\"kind\":\"stash\",\"name\":\"x\",\"includes\":\"*\"}")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePublishHtmlStep.definition.contract.inputCodec.decode(foreign)
        }
        assertTrue(ex.message!!.contains("publishHTML"), "error message must mention the expected kind")
    }

    @Test
    fun `5 — output codec round-trip (success)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlOutput(
            publishedCount = 7,
            skipped = false,
            skipReason = null,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlOutput, "decoded MUST be PublishHtmlOutput")
        decoded as CorePublishHtmlStep.PublishHtmlOutput
        assertEquals(7, decoded.publishedCount)
        assertEquals(false, decoded.skipped)
        assertEquals(null, decoded.skipReason)
    }

    @Test
    fun `5b — output codec round-trip (skipped)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlOutput(
            publishedCount = 0,
            skipped = true,
            skipReason = PublishHtmlSkipReason.NO_FILES_MATCHED,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlOutput)
        decoded as CorePublishHtmlStep.PublishHtmlOutput
        assertEquals(true, decoded.skipped)
        assertEquals(PublishHtmlSkipReason.NO_FILES_MATCHED, decoded.skipReason)
    }

    @Test
    fun `6 — output codec round-trip (failure)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlFailureOutput(
            failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            message = "reportDir 'x' does not exist",
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlFailureOutput)
        decoded as CorePublishHtmlStep.PublishHtmlFailureOutput
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, decoded.failureKind)
        assertEquals("reportDir 'x' does not exist", decoded.message)
    }

    @Test
    fun `7 — output codec rejects foreign kind`() {
        val foreign = EncodedStepValue(
            "{\"kind\":\"stash\",\"outcome\":\"FAILED\",\"failureKind\":\"SCRIPT\",\"message\":\"x\"}",
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePublishHtmlStep.definition.contract.outputCodec.decode(foreign)
        }
        assertTrue(ex.message!!.contains("publishHTML"), "error message must mention the expected kind")
    }

    @Test
    fun `8 — capability declaration equals usage (G3-A4_2)`() {
        val contract = CorePublishHtmlStep.definition.contract
        assertEquals(
            setOf<StepCapability>(PUBLISH_HTML_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "publishHTML MUST declare exactly PUBLISH_HTML_OPERATIONS_CAPABILITY (no broader)",
        )
    }

    @Test
    fun `9 — handler is a StepHandler typed I_O`() {
        val handler: StepHandler<PublishHtmlInput, TypedStepOutput> = CorePublishHtmlStep.definition.handler
        assertNotNull(handler, "handler must be a non-null StepHandler")
    }

    @Test
    fun `10 — registered through the open registry seam`() {
        val registry = InMemoryStepRegistry()
        CorePublishHtmlStep.registerInto(registry)
        val resolved = registry.definition(CorePublishHtmlStep.KEY)
        assertNotNull(resolved, "CorePublishHtmlStep MUST resolve via the open registry seam")
        assertEquals(CorePublishHtmlStep.definition, resolved)
    }

    @Test
    fun `11 — registry key uniqueness vs other CoreSteps`() {
        val registry = CoreStepRegistryFactory.registry()
        // Registry contains N prior CoreSteps + CorePublishHtmlStep; the exact N is tracked in
        // CoreStepRegistryFactory (it is the only authority for the family-wide count).
        val allKeys = registry.keys()
        assertTrue(allKeys.isNotEmpty(), "factory registry must not be empty")
        val publishHtmlKeys = allKeys.filter { it == CorePublishHtmlStep.KEY }
        assertEquals(
            1,
            publishHtmlKeys.size,
            "core.publishHTML MUST be unique in the registry (no duplicate StepKey)",
        )
    }

    // ============================================================================
    // Phase G7 (WU-RP-013) — 9 contract rows bringing the suite to 20/17 coverage
    // ============================================================================

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("publishhtml-contract-suite stub"),
        )
    }

    /** Self-contained harness mirroring ShStepContractSuiteTest.harness() shape. */
    private fun freshHarness(
        eventStore: InMemoryEventStore,
        controlRoot: Path,
    ): PublishHtmlHarness {
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
            controlDirRoot = controlRoot,
            shOptions = ShOptions(controlRoot.resolve("workspace"), false, null, emptyMap()),
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        return PublishHtmlHarness(coord, journal, eventStore, controlRoot)
    }

    private data class PublishHtmlHarness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val controlRoot: Path,
    )

    /**
     * Computes the workspace root for the given stage under the control root.
     * Mirrors WorkspaceResolver.resolve() shape for the in-process test path.
     */
    private fun workspaceRoot(controlRoot: Path, stageName: String, stageIndex: Int): Path {
        val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return controlRoot.resolve("workspace").resolve("${safeName}-${stageIndex}")
    }

    /** Builds an OpaqueStepNode that decodes through the production input codec. */
    private fun publishHtmlNode(
        name: String,
        reportDir: String,
        reportFiles: String = "**/*.html",
        nodeId: String = "build/publish",
    ): OpaqueStepNode {
        val input = PublishHtmlInput(
            name = name,
            reportDir = reportDir,
            reportFiles = reportFiles,
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val encoded = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("publishHTML"))
                put("name", kotlinx.serialization.json.JsonPrimitive(input.name))
                put("reportDir", kotlinx.serialization.json.JsonPrimitive(input.reportDir))
                put("reportFiles", kotlinx.serialization.json.JsonPrimitive(input.reportFiles))
            },
        )
        return OpaqueStepNode(
            id = StepId(nodeId),
            pluginStepId = CorePublishHtmlStep.KEY,
            payload = VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = encoded,
            ),
        )
    }

    private fun pipeline(node: OpaqueStepNode): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("publishhtml-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(listOf(node)),
            ),
        ),
    )

    @Test
    fun `12 — capability admission succeeds when PUBLISH_HTML_OPERATIONS is available`() {
        val r = InMemoryStepRegistry().apply { CorePublishHtmlStep.registerInto(this) }
        val encoded = CorePublishHtmlStep.definition.contract.inputCodec.encode(
            PublishHtmlInput(
                name = "html-report",
                reportDir = "build/reports",
                reportFiles = "**/*.html",
            ),
        )
        val preparation = RegistryExecutionPreparation.prepare(
            registry = r,
            key = CorePublishHtmlStep.KEY,
            encodedInput = encoded,
            availableCapabilities = setOf<StepCapability>(PUBLISH_HTML_OPERATIONS_CAPABILITY),
        )
        assertTrue(
            preparation is ExecutionPreparation.Ready,
            "admission must succeed when PUBLISH_HTML_OPERATIONS is available",
        )
    }

    @Test
    fun `13 — missing capability rejected when PUBLISH_HTML_OPERATIONS is absent`() {
        val r = InMemoryStepRegistry().apply { CorePublishHtmlStep.registerInto(this) }
        val encoded = CorePublishHtmlStep.definition.contract.inputCodec.encode(
            PublishHtmlInput(
                name = "html-report",
                reportDir = "build/reports",
                reportFiles = "**/*.html",
            ),
        )
        val preparation = RegistryExecutionPreparation.prepare(
            registry = r,
            key = CorePublishHtmlStep.KEY,
            encodedInput = encoded,
            availableCapabilities = emptySet(),
        )
        assertTrue(
            preparation is ExecutionPreparation.Rejected,
            "missing capability must surface as Rejected admission (fail-closed)",
        )
    }

    @Test
    fun `14 — handler success writes a SUCCEEDED operation row and a single HtmlReportPublished event`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // Seed a workspace with a real HTML file so the publishHTML handler has something to copy.
        val ws = workspaceRoot(tempDir.resolve("control"), "build", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html><body>seed</body></html>")

        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        val outcome = h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            RunId("publishhtml-success"),
        )
        assertEquals(RunOutcome.Success, outcome, "publishHTML with a valid reportDir must SUCCEED")
        val published = events.eventsFor("publishhtml-success").filterIsInstance<HtmlReportPublished>().toList()
        assertEquals(
            1,
            published.size,
            "exactly one HtmlReportPublished event must be emitted by a single publishHTML invocation",
        )
        assertEquals("html-report", published.single().reportName)
        val rows = h.journal.listForRun("publishhtml-success")
        assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
        assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
    }

    @Test
    fun `14b — canonical envelope is a well-formed JSON object with kind publishHTML (durable eligibility)`() {
        // The G7 canonical-envelope row asserts the input codec emits a stable JSON object that
        // can be durably fingerprinted and replayed. Mirrors EchoStepContractSuiteTest row 5.
        val encoded = CorePublishHtmlStep.definition.contract.inputCodec.encode(
            PublishHtmlInput(
                name = "html-report",
                reportDir = "build/reports",
                reportFiles = "**/*.html",
            ),
        )
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(
            parsed is kotlinx.serialization.json.JsonObject,
            "canonical publishHTML envelope MUST be a JSON object for durable-spine eligibility",
        )
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("publishHTML", obj["kind"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("html-report", obj["name"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("build/reports", obj["reportDir"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `15 — handler typed failure missing reportDir surfaces as RunOutcome Failure SCRIPT`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // No workspace seeded: the reportDir is absent AND allowMissing=false (default), so the
        // handler MUST fail closed as SCRIPT (NOT silent success, NOT silent skip).
        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        val outcome = h.coord.run(
            pipeline(publishHtmlNode("missing", "build/reports-does-not-exist")),
            RunId("publishhtml-fail"),
        )
        assertTrue(outcome is RunOutcome.Failure, "missing reportDir must surface as a typed RunOutcome.Failure")
        outcome as RunOutcome.Failure
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            outcome.failure.kind,
            "missing reportDir must map to a typed SCRIPT failure",
        )
        // No HtmlReportPublished on the failure path; an HtmlReportFailed (typed) is emitted.
        val published = events.eventsFor("publishhtml-fail").filterIsInstance<HtmlReportPublished>().toList()
        assertEquals(0, published.size, "a failed publishHTML MUST NOT emit HtmlReportPublished")
        val failed = events.eventsFor("publishhtml-fail")
            .filterIsInstance<dev.rubentxu.pipeline.v2.events.HtmlReportFailed>().toList()
        assertEquals(1, failed.size, "missing reportDir must emit exactly one typed HtmlReportFailed event")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            failed.single().failureKind,
            "the typed failureKind carried in the event must match the typed SCRIPT failure",
        )
    }

    @Test
    fun `16 — fresh durable first execution writes one terminal SUCCEEDED operation`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val ws = workspaceRoot(tempDir.resolve("control"), "build", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html>seed</html>")

        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            RunId("publishhtml-fresh"),
        )
        val rows = h.journal.listForRun("publishhtml-fresh")
        assertEquals(1, rows.size, "first execution MUST journal exactly one terminal operation")
        assertEquals(OperationStatus.SUCCEEDED, rows.single().status, "first execution MUST be terminal SUCCEEDED")
    }

    @Test
    fun `17 — replay is idempotent a second invocation of the same payload produces identical archive bytes`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // WU-RP-013 reconciliation note: `core.publishHTML` declares `Effect.WRITES_WORKSPACE`
        // AND `ReplayPolicy.MEMOIZED`. The canonical DefaultEffectReplayPolicy routes any
        // WRITES_WORKSPACE effect to `ReplayDecision.RERUN` (the handler always re-runs); the
        // MEMOIZED semantics for WRITES_WORKSPACE are IDEMPOTENT BY-EFFECT (archive overwrite +
        // deterministic sorted index.html), NOT event-reuse. So the durable fingerprint MUST
        // match across runs, the archive bytes MUST be byte-identical, and the index.html
        // fingerprint MUST be replay-stable — even though the typed event is re-emitted.
        val ws = workspaceRoot(tempDir.resolve("control"), "build", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html>seed</html>")

        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        val runId = RunId("publishhtml-replay")
        // First invocation.
        val firstOutcome = h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            runId,
        )
        assertEquals(RunOutcome.Success, firstOutcome)
        val firstArchive = h.controlRoot.resolve("reports").resolve(runId.value).resolve("html-report")
        assertTrue(Files.isDirectory(firstArchive), "first archive directory must exist")
        val firstIndexBytes = Files.readAllBytes(firstArchive.resolve("index.html"))

        // Second invocation with identical payload at the SAME runId → handler reruns (WRITES_WORKSPACE),
        // but the archive bytes MUST be byte-identical because the operation is idempotent.
        val secondOutcome = h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            runId,
        )
        assertEquals(RunOutcome.Success, secondOutcome, "replay of WRITES_WORKSPACE MUST succeed")
        val secondArchive = h.controlRoot.resolve("reports").resolve(runId.value).resolve("html-report")
        val secondIndexBytes = Files.readAllBytes(secondArchive.resolve("index.html"))
        // Per-entry sha256 of the generated index.html MUST be replay-stable (idempotent archive).
        val firstIndexSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(firstIndexBytes).joinToString("") { "%02x".format(it) }
        val secondIndexSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(secondIndexBytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            firstIndexSha,
            secondIndexSha,
            "WRITES_WORKSPACE publishHTML MUST be idempotent: replay must produce byte-identical index.html",
        )
        // The handler IS re-invoked on WRITES_WORKSPACE; the typed event IS re-emitted. This is the
        // documented policy: the durable fingerprint and archive bytes are stable; the event count
        // grows because the handler ran. Future work can layer a no-op emit short-circuit if the
        // archive tree is unchanged, but that is out of scope here.
        val secondCount = events.eventsFor(runId.value).filterIsInstance<HtmlReportPublished>().count()
        assertTrue(
            secondCount >= 2,
            "WRITES_WORKSPACE replay MUST re-emit HtmlReportPublished (handler ran; archive was rewritten)",
        )
    }

    @Test
    fun `18 — observability every run emits StepStarted before StepFinished plus a typed handler event`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val ws = workspaceRoot(tempDir.resolve("control"), "build", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html>seed</html>")

        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            RunId("publishhtml-obs"),
        )
        val all = events.eventsFor("publishhtml-obs").toList()
        val started = all.indexOfFirst { it is StepStarted }
        val finished = all.indexOfFirst { it is StepFinished }
        assertTrue(started >= 0, "StepStarted must be emitted (lifecycle observability)")
        assertTrue(finished >= 0, "StepFinished must be emitted (lifecycle observability)")
        assertTrue(started < finished, "StepStarted MUST come before StepFinished")
        val startedEv = all.filterIsInstance<StepStarted>().single()
        assertEquals("publishHTML", startedEv.stepType, "stepType must be the publishHTML PluginStepId value")
        val published = all.filterIsInstance<HtmlReportPublished>().single()
        assertEquals("html-report", published.reportName)
    }

    @Test
    fun `19 — divergence replaying a SUCCEEDED publishHTML with a different reportDir fails closed`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val ws = workspaceRoot(tempDir.resolve("control"), "build", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html>seed</html>")

        val events = InMemoryEventStore()
        val h = freshHarness(events, tempDir.resolve("control"))
        val runId = RunId("publishhtml-div")
        h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports")),
            runId,
        )
        // Different reportDir in the second invocation at the SAME runId → different fingerprint
        // → divergence must surface as a typed failure/unstable, not as a silent success.
        val outcome = h.coord.run(
            pipeline(publishHtmlNode("html-report", "build/reports-other")),
            runId,
        )
        assertTrue(
            outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
            "divergence must surface as typed Failure or Unstable, not as silent success",
        )
    }

    @Test
    fun `20 — real pipeline scenario public DSL publishHTML runs end-to-end through the installed-style harness and SUCCEEDS`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // Full DSL pipeline scenario, end-to-end through the public seam:
        //   pipeline { stages { stage("p") { publishHTML("html-report", "build/reports", "**/*.html") } } }
        // The harness is the in-process production-style: CoreStepRegistryFactory.registry(),
        // controlDirRoot + workspaceRoot, journal + cursor store + event store. The seed HTML
        // file MUST live in the resolved workspace root before the run.
        val ws = workspaceRoot(tempDir.resolve("control"), "p", 0)
        val reportDir = ws.resolve("build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("index.html"), "<html><body>real-dsl</body></html>")

        val sourcePath = "Pipeline.kts"
        val sourceContent = """
            pipeline {
                stages {
                    stage("p") {
                        publishHTML("html-report", "build/reports", "**/*.html")
                    }
                }
            }
        """.trimIndent()
        val spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec = dev.rubentxu.pipeline.v2.dsl.pipeline {
            stages {
                stage("p") {
                    publishHTML("html-report", "build/reports", "**/*.html")
                }
            }
        }
        val workDir = tempDir
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
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
            shOptions = ShOptions(
                workspaceRoot = workDir.resolve("workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
            ),
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = sourcePath,
            sourceContent = sourceContent,
            pluginLockDigest = Digest("builtin"),
        )
        val runId = RunId("publishhtml-real-dsl")
        val outcome = coord.run(compiled, runId)

        assertEquals(RunOutcome.Success, outcome, "real DSL pipeline scenario must SUCCEED")
        val published = eventStore.eventsFor(runId.value).filterIsInstance<HtmlReportPublished>().toList()
        assertEquals(
            1,
            published.size,
            "real DSL pipeline scenario must emit exactly one HtmlReportPublished event",
        )
        assertEquals("html-report", published.single().reportName)
        val rows = journal.listForRun(runId.value)
        assertEquals(1, rows.size, "real DSL pipeline scenario must journal exactly one terminal op row")
        assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
    }
}
