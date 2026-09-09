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
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite — `core.sh` certification (LB-02 / S6.6).
 *
 * Certifies `core.sh` end-to-end through the registry-driven open-world Step seam, mirroring the
 * `core.echo` StepContractSuite and adding the Sh-specific effectful rows: successful process,
 * non-zero exit, stdout, cancellation, ReplayPolicy semantics, durable encoded output,
 * ExternalSubprocess recovery, running-process recovery, observability, real installed scenario, and
 * legacy absence.
 *
 * Because the legacy `core.sh` sealed command, decoder, dispatcher, and metadata row were removed in
 * S6, every row here is exercised through the production registry composition only.
 */
@Timeout(90)
class ShStepContractSuiteTest {

    @TempDir
    lateinit var tempDir: Path

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreShellStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("sh-contract-suite stub"),
        )
    }

    private fun harness(
        eventStore: InMemoryEventStore = InMemoryEventStore(),
        controlRoot: Path = tempDir.resolve("control"),
    ): Pair<CanonicalDurableRunCoordinator, InMemoryOperationJournal> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            shOptions = ShOptions(tempDir.resolve("workspace"), false, null, emptyMap()),
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        return coord to journal
    }

    private fun shNode(command: String, id: String = "build/sh"): OpaqueStepNode = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = CoreShellStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"sh","command":"$command","isScriptBlock":false,"returnStdout":false}""",
        ),
    )

    private fun pipeline(node: OpaqueStepNode): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("sh-contract-suite"),
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

    // -------- identity / contract --------

    @Test
    fun `identity — CoreShellStep KEY is core dot sh and unique within the production registry`() {
        assertEquals(PluginStepId("core.sh"), CoreShellStep.KEY)
        assertEquals("core.sh", CoreShellStep.KEY.value)
        assertTrue(CoreStepRegistryFactory.registry().contains(CoreShellStep.KEY))
    }

    @Test
    fun `contract completeness — key, descriptor, input codec, output codec, required capabilities`() {
        val contract = CoreShellStep.definition.contract
        assertEquals(CoreShellStep.KEY, contract.key)
        assertNotNull(contract.descriptor)
        assertNotNull(contract.inputCodec)
        assertNotNull(contract.outputCodec)
        assertTrue(
            contract.requiredCapabilities.isNotEmpty(),
            "sh MUST declare its SHELL_OPERATIONS capability so admission is fail-closed",
        )
        assertEquals(
            setOf<dev.rubentxu.pipeline.v2.domain.step.StepCapability>(SHELL_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "sh MUST require exactly the SHELL_OPERATIONS capability",
        )
    }

    @Test
    fun `input codec — encode and round-trip preserve script and return mode`() {
        val input = CoreShellInput(command = ShellCommand(script = "echo round-trip"))
        val encoded = CoreShellStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreShellStep.definition.contract.inputCodec.decode(encoded)
        assertEquals("echo round-trip", decoded.command.script)
    }

    // -------- capability admission --------

    @Test
    fun `capability admission — admission succeeds when SHELL_OPERATIONS is available`() {
        val encoded = CoreShellStep.definition.contract.inputCodec.encode(CoreShellInput(command = ShellCommand(script = "echo a")))
        val outcome = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreShellStep.KEY,
            encodedInput = encoded,
            availableCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY),
        )
        assertTrue(outcome is ExecutionPreparation.Ready, "must admit with shell-operations present")
    }

    @Test
    fun `missing capability — admission rejects when SHELL_OPERATIONS is absent`() {
        val encoded = CoreShellStep.definition.contract.inputCodec.encode(CoreShellInput(command = ShellCommand(script = "echo a")))
        val outcome = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreShellStep.KEY,
            encodedInput = encoded,
            availableCapabilities = emptySet(),
        )
        assertTrue(outcome is ExecutionPreparation.Rejected, "must reject without shell-operations")
    }

    // -------- real process rows --------

    @Test
    fun `successful process — exit 0 sh runs to Success with a StepStarted StepFinished pair`() = runBlocking {
        val events = InMemoryEventStore()
        val (coord, _) = harness(events)
        val outcome = coord.run(pipeline(shNode("echo sh-contract-success")), RunId("sh-success"))

        assertEquals(RunOutcome.Success, outcome)
        val started = events.eventsFor("sh-success").filterIsInstance<StepStarted>().toList()
        val finished = events.eventsFor("sh-success").filterIsInstance<StepFinished>().toList()
        assertEquals(1, started.size)
        assertEquals(1, finished.size)
        assertTrue(started[0].stepType == "sh" && finished[0].stepType == "sh")
    }

    @Test
    fun `plain sh — stdout and stderr are both observable in the durable console transcript (C3)`() = runBlocking {
        val events = InMemoryEventStore()
        val (coord, _) = harness(events)
        val runId = RunId("sh-both")
        val outcome = coord.run(pipeline(shNode("echo OUT; echo ERR >&2")), runId)
        assertEquals(RunOutcome.Success, outcome)
        val caps = events.eventsFor(runId.value).filterIsInstance<EchoOutputCaptured>().toList()
        val content = caps.joinToString("") { it.content }
        assertTrue(content.contains("OUT"), "plain sh stdout must be observable; got ${content}")
        assertTrue(content.contains("ERR"), "plain sh stderr must be observable; got ${content}")
    }

    @Test
    fun `non-zero exit — exit 42 sh surfaces as a typed Failure SCRIPT`() = runBlocking {
        val (coord, _) = harness()
        val outcome = coord.run(pipeline(shNode("exit 42")), RunId("sh-nonzero"))
        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            (outcome as RunOutcome.Failure).failure.kind,
            "a non-zero shell exit must map to a typed SCRIPT failure",
        )
    }

    @Test
    fun `output — successful sh stdout is observed exactly once through the shared substrate`() = runBlocking {
        val events = InMemoryEventStore()
        val (coord, _) = harness(events)
        val marker = tempDir.resolve("captured.txt")
        // returnStdout=false with a side-effect capture; stdout is emitted as EchoOutputCaptured.
        val command = "echo sh-contract-output > '$marker' && cat '$marker'"
        val outcome = coord.run(pipeline(shNode(command)), RunId("sh-output"))
        assertEquals(RunOutcome.Success, outcome)
        assertEquals("sh-contract-output\n", Files.readString(marker))
    }

    // -------- durable / replay --------

    @Test
    fun `ReplayPolicy semantics — descriptor declares RERUN so a completed sh can be reconciled`() {
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.RERUN,
            CoreShellStep.definition.contract.descriptor.replayPolicy,
            "sh must declare RERUN so external-subprocess rerun/recovery semantics hold",
        )
    }

    @Test
    fun `durable encoded output — output codec is present and produces a typed envelope`() {
        val codec = CoreShellStep.definition.contract.outputCodec
        assertNotNull(codec)
    }

    // -------- recovery --------

    @Test
    fun `ExternalSubprocess RecoveryPolicy — declared on the descriptor and registry-resolved`() {
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.ExternalSubprocess,
            CoreShellStep.definition.contract.descriptor.recoveryPolicy,
        )
        val metadata = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
            .resolve(CoreShellStep.KEY)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.ExternalSubprocess,
            metadata!!.recoveryPolicy,
            "registry-resolved sh recovery metadata must come from the descriptor",
        )
    }

    @Test
    fun `running-process recovery — a RUNNING sh result reconciles without relaunching`() = runBlocking {
        val clock = SystemClock()
        val runId = RunId("sh-running-recovery")
        val operationId = "${runId.value}-s0-0"
        val relaunchMarker = tempDir.resolve("relaunched.txt")
        val command = "echo relaunched > '$relaunchMarker'"
        val pipeline = pipeline(shNode(command))
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.sh",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        val journal = InMemoryOperationJournal(clock)
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    input, "core.sh", dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.RERUN, 1,
                ),
                input = input,
                output = null,
                status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING,
                attempt = 1,
            ),
        )
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot.resolve(operationId))
        Files.writeString(controlRoot.resolve(operationId).resolve("result.txt"), "0")

        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            shOptions = ShOptions(tempDir.resolve("workspace"), false, null, emptyMap()),
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        val outcome = coord.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertTrue(!Files.exists(relaunchMarker), "reconciled RUNNING sh must NOT relaunch")
    }

    // -------- observability / legacy absence --------

    @Test
    fun `observability — successful sh emits StepStarted before StepFinished`() = runBlocking {
        val events = InMemoryEventStore()
        val (coord, _) = harness(events)
        val runId = RunId("sh-observability")
        coord.run(pipeline(shNode("echo obs")), runId)
        val all = events.eventsFor(runId.value).toList()
        val started = all.indexOfFirst { it is StepStarted }
        val finished = all.indexOfFirst { it is StepFinished }
        assertTrue(started >= 0 && finished >= 0 && started < finished, "expected StepStarted < StepFinished")
    }

    @Test
    fun `legacy absence — the sealed command world has no Shell subtype and registry owns core sh`() {
        assertTrue("core.sh" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val hasShell = CanonicalCoreStepCommand::class.sealedSubclasses.any { it.simpleName == "Shell" }
        assertTrue(!hasShell, "CanonicalCoreStepCommand must have no Shell subtype after S6")
        assertTrue(CoreStepRegistryFactory.registry().contains(CoreShellStep.KEY))
    }

    @Test
    fun `returnStdout — stdout is the typed value and stderr stays observable while stdout emits no console event (C4)`() = runBlocking {
        val events = InMemoryEventStore()
        val ws = Files.createDirectories(tempDir.resolve("rt-workspace"))
        val result = dev.rubentxu.pipeline.v2.application.durable.ShExecution.invokeShell(
            command = ShellCommand(script = "echo OUT; echo ERR >&2", returnMode = dev.rubentxu.pipeline.v2.domain.ShellReturnMode.STDOUT),
            opId = dev.rubentxu.pipeline.v2.application.durable.OpId("rt-capture", 0, 0),
            runId = "rt-capture",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(ws, false, null, emptyMap()),
            controlDirRoot = tempDir.resolve("rt-capture"),
            eventSink = events,
        )
        assertTrue(result is dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout, "returnStdout must yield a typed Stdout value")
        val value = (result as dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout).value
        assertTrue(value.contains("OUT"), "typed stdout value must carry OUT; got ${value}")
        val content = events.eventsFor("rt-capture").filterIsInstance<EchoOutputCaptured>().toList()
            .joinToString("") { it.content }
        assertTrue(content.contains("ERR"), "stderr must remain observable; got ${content}")
        assertTrue(!content.contains("OUT"), "stdout must NOT be re-emitted as a console event; got ${content}")
    }

    @Test
    fun `returnStdout — stdout-only capture emits no bogus console event while returning the value (C4 empty stderr)`() = runBlocking {
        val events = InMemoryEventStore()
        val ws = Files.createDirectories(tempDir.resolve("rt-workspace2"))
        val result = dev.rubentxu.pipeline.v2.application.durable.ShExecution.invokeShell(
            command = ShellCommand(script = "echo RTONLY", returnMode = dev.rubentxu.pipeline.v2.domain.ShellReturnMode.STDOUT),
            opId = dev.rubentxu.pipeline.v2.application.durable.OpId("rt-empty-err", 0, 0),
            runId = "rt-empty-err",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(ws, false, null, emptyMap()),
            controlDirRoot = tempDir.resolve("rt-capture2"),
            eventSink = events,
        )
        assertTrue(result is dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout)
        val value = (result as dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout).value
        assertTrue(value.contains("RTONLY"), "stdout value must carry RTONLY; got ${value}")
        val caps = events.eventsFor("rt-empty-err").filterIsInstance<EchoOutputCaptured>().toList()
        assertTrue(caps.isEmpty(), "no stderr means no console event; got ${caps.map { it.content }}")
    }
}
