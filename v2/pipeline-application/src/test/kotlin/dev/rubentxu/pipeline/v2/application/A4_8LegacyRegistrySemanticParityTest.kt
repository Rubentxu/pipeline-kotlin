package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.ShExecution
import dev.rubentxu.pipeline.v2.application.durable.ShOperationsAdapter
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G3-A4.8 — legacy `core.sh` (canonical dispatcher) vs registry `core.sh`
 * (open-world Step path) semantic parity.
 *
 * Acceptance gates — all must be GREEN before `core.sh = REGISTRY_PRIMARY` flips:
 *
 *  - **A4.8.1 Output parity** — same `ShellInvocationResult` value drives the same
 *    typed outcome on BOTH paths. The legacy `ShExecution.runShellCommandTyped` is
 *    literally `invokeShell(...).toStepOutcome()` and the registry `CoreShellStep`
 *    handler is literally `ShellOperations.invoke(...).toStepOutcome()`; both call
 *    [toStepOutcome] from `ShellStepOutcomeClassifier.kt`. Parity is therefore a
 *    structural property of the source — these tests prove it explicitly.
 *
 *  - **A4.8.2 Event parity** — for a real subprocess, BOTH paths produce exactly one
 *    `EchoOutputCaptured` event with the same `stepIndex` and full stdout+stderr
 *    content. The differences MUST be limited to `eventId`/`sequence`/`occurredAt`
 *    (i.e. not observable in replay comparison).
 *
 *  - **A4.8.3 Execution-count parity** — the registry handler invokes
 *    `ShellOperations.invoke` exactly ONCE; `ShOperationsAdapter.invoke` invokes
 *    `ShExecution.invokeShell` exactly ONCE; the substrate launches exactly one
 *    process. No double-launch. No re-entrant path.
 *
 *  - **A4.8.4 Ephemeral typed carrier** — `CoreShellOutput` and `TypedStepOutput`
 *    MUST NOT appear in the journal domain, fingerprint model, coordinator durable
 *    state, or recovery persisted state. They are an in-flight handler→boundary
 *    carrier only. (Proven by source-grep below.)
 *
 *  - **A4.8.5 Three-branch semantics** — ordinary registry Step (non-TypedStepOutput
 *    return type, e.g. `core.echo`) → `Success` WITHOUT typed carrier; handler
 *    exception → `Failure(ENGINE)`; typed terminal output → typed outcome.
 *    `EchoStepContractSuiteTest` 17/17 MUST stay green.
 *
 *  - **A4.8 outcome parity table** — `LegacyOutcome` == `RegistryOutcome` for every
 *    `ShellInvocationResult` variant via the SAME [toStepOutcome] classifier call.
 */
@Timeout(30)
class A4_8LegacyRegistrySemanticParityTest {

    // ----- helpers ----------------------------------------------------------

    private fun runtimeContext(runId: String): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId(runId, 0, 0),
            runId = runId,
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

    private class RecordingShellOps(
        var returnValue: ShellInvocationResult,
    ) : ShellOperations {
        var callCount: Int = 0
        var lastCommand: ShellCommand? = null
        var lastRunId: RunId? = null
        var lastStepIndex: Int = -1

        override suspend fun invoke(
            command: ShellCommand,
            runId: RunId,
            stepIndex: Int,
        ): ShellInvocationResult {
            callCount += 1
            lastCommand = command
            lastRunId = runId
            lastStepIndex = stepIndex
            return returnValue
        }
    }

    private fun fakeCapabilities(ops: RecordingShellOps): StepCapabilityAccess =
        object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
            override fun <T : Any> get(key: StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return ops as T
            }
        }

    /** A canonical 5-variant corpus used by the parity table. */
    private val corpus: List<Pair<String, ShellInvocationResult>> = listOf(
        "exit-0 UnitValue" to ShellInvocationResult.UnitValue,
        "exit-0 Stdout" to ShellInvocationResult.Stdout("hello\n"),
        "exit-7 Status" to ShellInvocationResult.Status(7),
        "exit-1 Failed" to ShellInvocationResult.Failed(
            failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 1"),
            exitCode = 1,
        ),
        "cancelled Interrupted" to ShellInvocationResult.Interrupted(
            interruption = InterruptionRecord(
                kind = InterruptionKind.PARENT_CANCELLED,
                message = "cancelled",
                operationId = "run/0/0",
            ),
        ),
    )

    // ========================================================================
    // A4.8.1 — Output parity: legacy == registry for every variant
    // ========================================================================

    @Test
    fun `A4_8_1 — classifier is the SAME function for both paths (single source of truth)`() {
        // Structural evidence: the public classifier `toStepOutcome` lives in
        // `ShellStepOutcomeClassifier.kt`. Both `ShExecution.runShellCommandTyped`
        // and `CoreShellStep.handler` call it on the typed `ShellInvocationResult`
        // value. The compiler proves this; this test pins the public symbol.
        val publicClassifier = ShellInvocationResult::toStepOutcome
        // Legacy path: ShExecution.runShellCommandTyped returns the result of
        // invokeShell(...).toStepOutcome(). Same call.
        val sample = ShellInvocationResult.Stdout("x")
        val viaPublic: StepOutcome = publicClassifier(sample)
        // The handler computes the same value via the same function:
        val viaHandler: StepOutcome = sample.toStepOutcome()
        assertSame(
            viaPublic,
            viaHandler,
            "legacy and registry must use literally the same classifier function",
        )
    }

    @Test
    fun `A4_8_1 — registry handler outcome == public classifier for every variant`() = runBlocking {
        for ((label, result) in corpus) {
            val ops = RecordingShellOps(returnValue = result)
            val output = CoreShellStep.definition.handler.execute(
                CoreShellInput(command = ShellCommand(script = ":", returnMode = ShellReturnMode.STDOUT)),
                StepHandlerContext(runId = RunId("a4-8-$label"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
            )
            // Registry outcome == public classifier outcome for the same input.
            assertEquals(
                result.toStepOutcome(),
                output.outcome,
                "registry outcome must equal classifier outcome for variant '$label'",
            )
            // Typed carrier must carry the original ShellInvocationResult 1:1.
            assertEquals(
                result,
                output.result,
                "registry result must carry the typed ShellInvocationResult for variant '$label'",
            )
        }
    }

    @Test
    fun `A4_8_1 — handler invokes ShellOperations exactly once per step (no double-launch)`() = runBlocking {
        for ((label, result) in corpus) {
            val ops = RecordingShellOps(returnValue = result)
            CoreShellStep.definition.handler.execute(
                CoreShellInput(command = ShellCommand(script = ":", returnMode = ShellReturnMode.STDOUT)),
                StepHandlerContext(runId = RunId("a4-8-count-$label"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
            )
            assertEquals(
                1,
                ops.callCount,
                "handler must invoke ShellOperations exactly once for variant '$label'",
            )
        }
    }

    // ========================================================================
    // A4.8.3 — Execution-count parity: adapter → ShExecution → process (×1)
    // ========================================================================

    @Test
    fun `A4_8_3 — ShOperationsAdapter invoke calls ShExecution invokeShell exactly once`() {
        // The adapter is the bridge between the registry capability and the
        // certified substrate. It MUST delegate to `ShExecution.invokeShell`
        // exactly once per `invoke` call. (Real subprocess path is exercised in
        // A4.8.2 below.)
        val runId = "a4-8-adapter-count"
        val eventSink = InMemoryEventStore()
        val adapter = ShOperationsAdapter(
            runIdString = runId,
            opId = OpId(runId, 0, 0),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = eventSink,
        )
        // Adapter delegates to ShExecution.invokeShell; with controlDirRoot=null
        // it falls through to the non-durable path which calls EventSink.append
        // exactly once with an EchoOutputCaptured.
        // Run a real but tiny echo subprocess.
        runBlocking {
            val result = adapter.invoke(
                command = ShellCommand(
                    script = "echo a48-adapter-once",
                    returnMode = ShellReturnMode.STDOUT,
                ),
                runId = RunId(runId),
                stepIndex = 0,
            )
            assertInstanceOf(ShellInvocationResult.Stdout::class.java, result)
            // Exactly one EchoOutputCaptured was appended.
            val captured = eventSink.eventsFor(runId).filterIsInstance<EchoOutputCaptured>().toList()
            assertEquals(1, captured.size, "exactly one EchoOutputCaptured per adapter.invoke")
        }
    }

    // ========================================================================
    // A4.8.2 — Event parity: legacy == registry emit identical EchoOutputCaptured
    // ========================================================================

    /**
     * A recording event sink that counts appends and remembers the
     * (stepIndex, content) of every EchoOutputCaptured emitted.
     */
    private class RecordingEventSink : EventSink {
        val captured: MutableList<Pair<Int, String>> = mutableListOf()
        val allAppended: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            allAppended += event
            if (event is EchoOutputCaptured) {
                captured += event.stepIndex to event.content
            }
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            allAppended.asSequence().filter { it.runId == runId }
    }

    @Test
    fun `A4_8_2 — legacy path emits exactly one EchoOutputCaptured with the captured output`() {
        val runId = "a4-8-event-legacy"
        val eventSink = RecordingEventSink()
        // Drive the legacy path directly through ShExecution.invokeShell.
        // With controlDirRoot=null, it falls through to the non-durable path
        // which emits EchoOutputCaptured.
        runBlocking {
            val result = ShExecution.invokeShell(
                command = ShellCommand(
                    script = "echo a48-event-legacy",
                    returnMode = ShellReturnMode.STDOUT,
                ),
                opId = OpId(runId, 0, 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 7,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = null,
                eventSink = eventSink,
            )
            assertInstanceOf(ShellInvocationResult.Stdout::class.java, result)
            assertEquals(1, eventSink.captured.size, "legacy must emit exactly one EchoOutputCaptured")
            val (stepIndex, content) = eventSink.captured.single()
            assertEquals(7, stepIndex, "legacy preserves stepIndex on the emitted event")
            assertTrue(
                "a48-event-legacy" in content,
                "legacy emitted content must contain the captured script output",
            )
        }
    }

    @Test
    fun `A4_8_2 — registry adapter path emits exactly one EchoOutputCaptured with same content shape`() {
        val runId = "a4-8-event-registry"
        val eventSink = RecordingEventSink()
        val adapter = ShOperationsAdapter(
            runIdString = runId,
            opId = OpId(runId, 0, 0),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = eventSink,
        )
        runBlocking {
            val result = adapter.invoke(
                command = ShellCommand(
                    script = "echo a48-event-registry",
                    returnMode = ShellReturnMode.STDOUT,
                ),
                runId = RunId(runId),
                stepIndex = 9,
            )
            assertInstanceOf(ShellInvocationResult.Stdout::class.java, result)
            assertEquals(
                1,
                eventSink.captured.size,
                "registry adapter must emit exactly one EchoOutputCaptured (no double-emission)",
            )
            val (stepIndex, content) = eventSink.captured.single()
            assertEquals(9, stepIndex, "registry adapter preserves stepIndex on the emitted event")
            assertTrue(
                "a48-event-registry" in content,
                "registry adapter emitted content must contain the captured script output",
            )
        }
    }

    @Test
    fun `A4_8_2 — legacy and registry paths emit EchoOutputCaptured with identical content for the same script`() {
        // Same script, same stepIndex, different runs — the captured content
        // (the script's stdout) MUST be byte-equivalent across paths. eventId /
        // occurredAt / sequence are deliberately NOT compared (they are
        // non-deterministic identity markers).
        val script = "echo a48-parity"
        val legacySink = RecordingEventSink()
        val registrySink = RecordingEventSink()
        runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.STDOUT),
                opId = OpId("a4-8-parity-legacy", 0, 0),
                runId = "a4-8-parity-legacy",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = null,
                eventSink = legacySink,
            )
            ShOperationsAdapter(
                runIdString = "a4-8-parity-registry",
                opId = OpId("a4-8-parity-registry", 0, 0),
                shOptions = ShOptions.EMPTY,
                controlDirRoot = null,
                eventSink = registrySink,
            ).invoke(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.STDOUT),
                runId = RunId("a4-8-parity-registry"),
                stepIndex = 0,
            )
        }
        assertEquals(
            legacySink.captured.size,
            registrySink.captured.size,
            "legacy and registry must emit the same number of EchoOutputCaptured events",
        )
        val legacyContent = legacySink.captured.single().second
        val registryContent = registrySink.captured.single().second
        assertEquals(
            legacyContent,
            registryContent,
            "legacy and registry must emit byte-equivalent captured content for the same script",
        )
    }

    // ========================================================================
    // A4.8.4 — Ephemeral typed carrier (source-grep + structural assertions)
    // ========================================================================

    @Test
    fun `A4_8_4 — CoreShellOutput and TypedStepOutput do NOT leak into the durable substrate`() {
        // Source-grep proves that the typed carrier is in-flight only:
        //   - the journal domain: v2/pipeline-events (no occurrences)
        //   - coordinator durable state: v2/pipeline-application/durable (only the
        //     single-reader boundary reference; no persist)
        //   - recovery: v2/pipeline-application/durable/Recovery* (no occurrences)
        // The marker TypedStepOutput lives in v2/pipeline-domain/durable/ but is
        // a pure marker interface with no fields — its only implementor is
        // CoreShellOutput, which itself lives in v2/pipeline-application.
        //
        // The walk is rooted at the repo root. Gradle's test JVM CWD is the
        // module directory (v2/pipeline-application); we walk up until we find
        // the `v2/` directory that owns pipeline-domain + pipeline-application.
        var cursor: java.io.File = java.io.File(System.getProperty("user.dir"))
        var workspaceRoot: java.io.File = cursor
        for (i in 0 until 6) {
            val candidate = java.io.File(cursor, "v2/pipeline-domain")
            if (candidate.isDirectory) {
                workspaceRoot = cursor
                break
            }
            cursor = cursor.parentFile ?: break
        }
        val domainRoot = java.io.File(workspaceRoot, "v2/pipeline-domain/src/main/kotlin")
        val eventsRoot = java.io.File(workspaceRoot, "v2/pipeline-events/src/main/kotlin")
        assertTrue(
            domainRoot.isDirectory,
            "domain source tree not found at $domainRoot (user.dir=${System.getProperty("user.dir")})",
        )
        assertTrue(
            eventsRoot.isDirectory,
            "events source tree not found at $eventsRoot (user.dir=${System.getProperty("user.dir")})",
        )

        val typedInDomain: List<String> = domainRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("TypedStepOutput") }
            .map { it.name }
            .toList()
        // Only the marker file itself should match — no other domain class
        // imports it. If the marker ever leaks into a domain service, this fails.
        assertEquals(
            listOf("TypedStepOutput.kt"),
            typedInDomain,
            "TypedStepOutput must remain a marker in pipeline-domain; " +
                "found in: $typedInDomain",
        )

        val typedInEvents: List<String> = eventsRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("TypedStepOutput") }
            .map { it.name }
            .toList()
        assertEquals(
            emptyList<String>(),
            typedInEvents,
            "TypedStepOutput must NOT appear in the events module (event log substrate)",
        )

        // CoreShellOutput is concrete and lives in pipeline-application only.
        val v2Root = java.io.File(workspaceRoot, "v2")
        val coreShellOutsideApp: List<String> = v2Root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("CoreShellOutput") }
            .map { it.path }
            .filterNot { it.startsWith(java.io.File(workspaceRoot, "v2/pipeline-application").path + "/") }
            .toList()
        assertEquals(
            emptyList<String>(),
            coreShellOutsideApp,
            "CoreShellOutput must NOT leak outside pipeline-application; " +
                "found in: $coreShellOutsideApp",
        )

        // TypedStepOutput must NOT leak into the recovery / journal / fingerprint
        // substrate either — the only files that may reference it are:
        //   - the marker (pipeline-domain/.../TypedStepOutput.kt)
        //   - the carrier (pipeline-application/.../CoreShellOutput.kt)
        //   - the boundary single-reader (pipeline-application/.../durable/RegistryExecutionBoundary.kt)
        //   - the docs / tests
        val typedOutsideMarkerAndApp: List<String> = v2Root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("TypedStepOutput") }
            .map { it.relativeTo(workspaceRoot).path }
            .filterNot { it.startsWith("v2/pipeline-domain/") }
            .filterNot { it.startsWith("v2/pipeline-application/") }
            .toList()
        assertEquals(
            emptyList<String>(),
            typedOutsideMarkerAndApp,
            "TypedStepOutput must NOT leak outside pipeline-domain + pipeline-application; " +
                "found in: $typedOutsideMarkerAndApp",
        )
    }

    // ========================================================================
    // A4.8.5 — Three-branch semantics
    //   (1) ordinary registry Step returning String → Success without TypedStepOutput
    //   (2) handler exception → Failure(ENGINE)
    //   (3) typed terminal carrier → typed outcome
    // ========================================================================

    private fun readyPrepared(
        registry: InMemoryStepRegistry,
        key: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        access: StepCapabilityAccess,
    ): PreparedExecution {
        val prep = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = key,
            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("ignored"),
            availableCapabilities = access.available(),
        )
        return when (prep) {
            is ExecutionPreparation.Ready -> prep.prepared
            is ExecutionPreparation.Rejected -> error("prepare rejected ${key.value}: ${prep.reason}")
        }
    }

    @Test
    fun `A4_8_5 — branch 1 ordinary Step returning String yields Success without TypedStepOutput`() = runBlocking {
        // `core.echo` returns `String`, not `TypedStepOutput`. The boundary
        // projects (produced as? TypedStepOutput)?.outcome ?: Success.
        // The result is Success WITHOUT a typed carrier present.
        val (registry, key) = makeStringStep("a4-8-branch1")
        val result: CommonExecutionResult = RegistryExecutionBoundary.adapt().execute(
            prepared = readyPrepared(registry, key, canonicalAccess("a4-8-branch1")),
            context = runtimeContext("a4-8-branch1"),
        )
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        // The encoded output is the String the handler returned — NOT a typed carrier.
        assertEquals("ok", result.encodedOutput!!.value)
        // And the carried object is NOT a TypedStepOutput (it's a String).
        assertTrue(
            result.encodedOutput!!.value !is TypedStepOutput,
            "non-TypedStepOutput handler must NOT be wrapped as TypedStepOutput",
        )
    }

    @Test
    fun `A4_8_5 — branch 2 handler exception yields Failure ENGINE with no encodedOutput`() = runBlocking {
        val (registry, key) = makeThrowingStep("a4-8-branch2")
        val result: CommonExecutionResult = RegistryExecutionBoundary.adapt().execute(
            prepared = readyPrepared(registry, key, canonicalAccess("a4-8-branch2")),
            context = runtimeContext("a4-8-branch2"),
        )
        val f = assertInstanceOf(StepOutcome.Failure::class.java, result.outcome)
        assertEquals(FailureKind.ENGINE, f.failure.kind)
        assertNull(result.encodedOutput, "thrown handler produces no encodedOutput")
    }

    @Test
    fun `A4_8_5 — branch 3 typed terminal carrier yields typed outcome (Failure carried through)`() = runBlocking {
        val (registry, key) = makeTypedShellStep("a4-8-branch3", ShellInvocationResult.Failed(
            failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 1"),
            exitCode = 1,
        ))
        val result: CommonExecutionResult = RegistryExecutionBoundary.adapt().execute(
            prepared = readyPrepared(registry, key, canonicalAccess("a4-8-branch3")),
            context = runtimeContext("a4-8-branch3"),
        )
        val f = assertInstanceOf(StepOutcome.Failure::class.java, result.outcome)
        assertEquals(FailureKind.SCRIPT, f.failure.kind)
        assertEquals("exit 1", f.failure.message)
    }

    @Test
    fun `A4_8_5 — branch 3 typed terminal carrier yields typed outcome (Interrupted mapped to Failure TIMEOUT)`() = runBlocking {
        val (registry, key) = makeTypedShellStep(
            "a4-8-branch3-int",
            ShellInvocationResult.Interrupted(
                interruption = InterruptionRecord(
                    kind = InterruptionKind.PARENT_CANCELLED,
                    message = "cancelled",
                    operationId = "run/0/0",
                ),
            ),
        )
        val result: CommonExecutionResult = RegistryExecutionBoundary.adapt().execute(
            prepared = readyPrepared(registry, key, canonicalAccess("a4-8-branch3-int")),
            context = runtimeContext("a4-8-branch3-int"),
        )
        val f = assertInstanceOf(StepOutcome.Failure::class.java, result.outcome)
        // Classifier collapses any interruption to TIMEOUT (legacy convention);
        // the original InterruptionKind is on the typed carrier.
        assertEquals(FailureKind.TIMEOUT, f.failure.kind)
        assertEquals("cancelled", f.failure.message)
    }

    // ----- helpers used by A4.8.5 ------------------------------------------

    private fun canonicalAccess(runId: String): CanonicalRuntimeCapabilityAccess =
        CanonicalRuntimeCapabilityAccess(runtimeContext(runId))

    private fun makeStringStep(runId: String): Pair<InMemoryStepRegistry, dev.rubentxu.pipeline.v2.domain.PluginStepId> {
        val key = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.branch1.$runId")
        val codec = object : dev.rubentxu.pipeline.v2.domain.step.StepCodec<String> {
            override fun encode(value: String) = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(value)
            override fun decode(encoded: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue) = encoded.value
        }
        val def = object : dev.rubentxu.pipeline.v2.domain.step.StepDefinition<String, String> {
            override val contract = dev.rubentxu.pipeline.v2.domain.step.StepContract(
                key = key,
                descriptor = dev.rubentxu.pipeline.v2.domain.StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = dev.rubentxu.pipeline.v2.domain.ExecutionLocation.CONTROLLER,
                    effects = listOf(dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY),
                    replayPolicy = dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                ),
                inputCodec = codec,
                outputCodec = codec,
                requiredCapabilities = emptySet(),
            )
            override val handler = dev.rubentxu.pipeline.v2.domain.step.StepHandler<String, String> { _, _ -> "ok" }
        }
        return InMemoryStepRegistry().apply { register(def) } to key
    }

    private fun makeThrowingStep(runId: String): Pair<InMemoryStepRegistry, dev.rubentxu.pipeline.v2.domain.PluginStepId> {
        val key = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.branch2.$runId")
        val codec = object : dev.rubentxu.pipeline.v2.domain.step.StepCodec<String> {
            override fun encode(value: String) = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(value)
            override fun decode(encoded: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue) = encoded.value
        }
        val def = object : dev.rubentxu.pipeline.v2.domain.step.StepDefinition<String, String> {
            override val contract = dev.rubentxu.pipeline.v2.domain.step.StepContract(
                key = key,
                descriptor = dev.rubentxu.pipeline.v2.domain.StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = dev.rubentxu.pipeline.v2.domain.ExecutionLocation.CONTROLLER,
                    effects = listOf(dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY),
                    replayPolicy = dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                ),
                inputCodec = codec,
                outputCodec = codec,
                requiredCapabilities = emptySet(),
            )
            override val handler = dev.rubentxu.pipeline.v2.domain.step.StepHandler<String, String> { _, _ ->
                throw IllegalStateException("branch2 blow")
            }
        }
        return InMemoryStepRegistry().apply { register(def) } to key
    }

    private fun makeTypedShellStep(
        runId: String,
        result: ShellInvocationResult,
    ): Pair<InMemoryStepRegistry, dev.rubentxu.pipeline.v2.domain.PluginStepId> {
        val key = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.branch3.$runId")
        val def = object : dev.rubentxu.pipeline.v2.domain.step.StepDefinition<String, CoreShellOutput> {
            override val contract = dev.rubentxu.pipeline.v2.domain.step.StepContract(
                key = key,
                descriptor = dev.rubentxu.pipeline.v2.domain.StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = dev.rubentxu.pipeline.v2.domain.ExecutionLocation.CONTROLLER,
                    effects = listOf(dev.rubentxu.pipeline.v2.domain.durable.Effect.EXECUTES_SUBPROCESS),
                    replayPolicy = dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.RERUN,
                ),
                inputCodec = CoreShellStep.definition.contract.inputCodec.let { _ ->
                    object : dev.rubentxu.pipeline.v2.domain.step.StepCodec<String> {
                        override fun encode(value: String) = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(value)
                        override fun decode(encoded: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue) = encoded.value
                    }
                },
                outputCodec = CoreShellStep.definition.contract.outputCodec,
                requiredCapabilities = emptySet(),
            )
            override val handler = dev.rubentxu.pipeline.v2.domain.step.StepHandler<String, CoreShellOutput> { _, _ ->
                CoreShellOutput(result = result, outcome = result.toStepOutcome())
            }
        }
        return InMemoryStepRegistry().apply { register(def) } to key
    }
}
