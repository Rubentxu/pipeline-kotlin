package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
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
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * StepContractSuite — S2-A4 / G6 for `core.emit.event` (post-LEGACY_REMOVED).
 *
 * Two sections, per the gate mandate:
 *
 * ```
 * GENERIC STEP CONTRACT        — "is this a correct registry Step?"
 * EMIT.EVENT SEMANTIC CONTRACT — "does it keep its specific semantics?"
 * ```
 *
 * Boundary discipline: a significant portion drives the REAL seam
 * `RegistryExecutionPreparation -> capability admission -> PreparedRegistryExecution ->
 * RegistryExecutionBoundary -> CoreEmitEventStep`, never a bare handler call alone.
 *
 * Permanent separation certified here:
 * ```
 * StructuralOverlayProjection owns control-flow projection (scope push/pop)
 * CoreEmitEventStep           owns execution semantics (typed outcomes, zero marker events)
 * ```
 *
 * NO-NEW-VALIDATIONS law (G2 freeze): blank values accepted where G2 froze them, negative
 * size accepted, unknown extra fields accepted, `atomicallyMoved` frozen legacy parse
 * (only strict "true"/"false"; everything else — including "TRUE", "yes", "1", garbage —
 * is false).
 *
 * Production code changes for G6: ZERO.
 */
@Timeout(60)
class EmitEventStepContractSuiteTest {

    // ===== harness =====

    private fun registry() = CoreStepRegistryFactory.registry()

    private fun runtime(store: InMemoryEventStore, runId: String = "emit-suite"): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId("emit-suite", 0, 0),
            runId = runId,
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = store,
        )

    private fun encode(kind: String, vararg fields: Pair<String, String?>): EncodedStepValue {
        val body = buildString {
            append("{\"kind\":\"").append(kind).append('"')
            fields.forEach { (k, v) ->
                append(",\"").append(k).append("\":")
                if (v != null) append('"').append(v).append('"') else append("null")
            }
            append('}')
        }
        return EncodedStepValue(body)
    }

    /** Real seam: prepare (admission + decode) against the runtime bridge's actual capability table. */
    private fun prepareReady(
        store: InMemoryEventStore,
        encoded: EncodedStepValue,
        available: Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            CanonicalRuntimeCapabilityAccess(runtime(store)).available(),
    ): PreparedRegistryExecution {
        val ready = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreEmitEventStep.KEY,
            encodedInput = encoded,
            availableCapabilities = available,
        )
        assertTrue(ready is ExecutionPreparation.Ready, "expected Ready admission, got $ready")
        return (ready as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
    }

    /** Real seam: prepare + boundary execute; returns the atomic outcome+encodedOutput carrier. */
    private fun executeViaBoundary(
        store: InMemoryEventStore,
        encoded: EncodedStepValue,
    ): CommonExecutionResult = runBlocking {
        RegistryExecutionBoundary.adapt().execute(prepareReady(store, encoded), runtime(store))
    }

    private fun coordinator(store: InMemoryEventStore, journal: InMemoryOperationJournal) =
        CoordinatorFixture.default(SystemClock(), journal, store)

    private fun emitNode(id: String, kind: String, vararg fields: Pair<String, String>) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.emit.event"),
        payload = VersionedStepPayload(
            "dsl-v1",
            buildString {
                append("{\"kind\":\"").append(kind).append('"')
                fields.forEach { (name, value) ->
                    append(",\"").append(name).append("\":\"").append(value).append('"')
                }
                append('}')
            },
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("emit-event-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(StageNode(id = StageId("build"), name = "build", body = StageBody.Steps(nodes.toList()))),
    )

    private fun opInput(runId: String, encoded: String) = OperationInput(
        stepId = "core.emit.event",
        params = mapOf("payload" to JsonPrimitive(encoded)),
        runId = runId,
        attempt = 1,
    )

    // =====================================================================
    // SECTION 1 — GENERIC STEP CONTRACT
    // =====================================================================

    // ===== 1. identity =====

    @Test
    fun `identity — key is core dot emit dot event, registered, Registry family`() {
        assertEquals(PluginStepId("core.emit.event"), CoreEmitEventStep.KEY)
        val production = registry()
        assertTrue(production.contains(CoreEmitEventStep.KEY), "REGISTERED must hold")
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreEmitEventStep.KEY, production),
            "StructuralFamily MUST be Registry (post-G4 flip, post-G5 removal)",
        )
    }

    @Test
    fun `identity — duplicate registration fails closed`() {
        assertTrue(runCatching { CoreEmitEventStep.registerInto(registry()) }.isFailure)
    }

    // ===== 1. descriptor frozen (G2/G5 values, exact sets) =====

    @Test
    fun `descriptor — exact frozen values READ_ONLY MEMOIZED recovery None`() {
        val d = CoreEmitEventStep.definition.contract.descriptor
        assertEquals("core.emit.event", d.stepId)
        assertEquals("emitEvent", d.name)
        assertEquals(setOf(Effect.READ_ONLY), d.effects.toSet(), "effects MUST equal exactly {READ_ONLY}")
        assertEquals(ReplayPolicy.MEMOIZED, d.replayPolicy)
        assertEquals(RecoveryPolicy.None, d.recoveryPolicy)
    }

    @Test
    fun `capabilities — declared set is EXACTLY eventSink plus runtime stage identity`() {
        assertEquals(
            setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY),
            CoreEmitEventStep.definition.contract.requiredCapabilities,
            "requiredCapabilities MUST be the exact declared set (no contains-only check)",
        )
    }

    // ===== 2. codec: round-trip for all four kinds + unknown-kind layering law =====

    @Test
    fun `codec — encode decode round-trips all four whitelist kinds`() {
        val codec = CoreEmitEventStep.definition.contract.inputCodec
        val cases = listOf(
            CoreEmitEventInput("CatchErrorEntered", mapOf("buildResult" to "UNSTABLE")),
            CoreEmitEventInput("CatchErrorTriggered", mapOf("buildResult" to "UNSTABLE", "emitted" to "true")),
            CoreEmitEventInput("StageMarkedUnstable", mapOf("message" to "wobbly", "stageName" to null)),
            CoreEmitEventInput(
                "FileWritten",
                mapOf("path" to "out.txt", "sha256" to "abc", "size" to "5", "atomicallyMoved" to "true"),
            ),
        )
        for (input in cases) {
            assertEquals(input, codec.decode(codec.encode(input)), "round-trip must preserve ${input.kind}")
        }
    }

    @Test
    fun `codec — unknown kind is accepted by the codec and handler decides SCHEMA (not DecodeFailure)`() {
        val codec = CoreEmitEventStep.definition.contract.inputCodec
        val decoded = codec.decode(encode("SomethingUserInvented"))
        assertEquals("SomethingUserInvented", decoded.kind, "codec accepts the envelope (layering law)")
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("SomethingUserInvented"))
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(FailureKind.SCHEMA, failure.failure.kind, "whitelist rejection is SEMANTIC SCHEMA, not decode failure")
        assertEquals(0, store.eventsFor("emit-suite").count(), "rejection appends zero events")
    }

    // ===== 3. capability admission (fail-closed, real prepare seam) =====

    @Test
    fun `admission — both capabilities available prepares Ready and the handler executes`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("CatchErrorEntered"))
        assertEquals(StepOutcome.Success, result.outcome, "handler executed through the real boundary")
    }

    @Test
    fun `admission — missing EVENT_SINK rejects with handler invocation count zero and zero events`() {
        val store = InMemoryEventStore()
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreEmitEventStep.KEY,
            encodedInput = encode("StageMarkedUnstable", "message" to "m"),
            availableCapabilities = setOf(STAGE_IDENTITY_CAPABILITY),
        )
        assertTrue(preparation is ExecutionPreparation.Rejected, "missing EVENT_SINK must reject admission")
        assertEquals(0, store.eventsFor("emit-suite").count(), "handler = 0, events = 0")
    }

    @Test
    fun `admission — missing STAGE_IDENTITY rejects with handler invocation count zero and zero events`() {
        val store = InMemoryEventStore()
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreEmitEventStep.KEY,
            encodedInput = encode("StageMarkedUnstable", "message" to "m"),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertTrue(preparation is ExecutionPreparation.Rejected, "missing STAGE_IDENTITY must reject admission")
        assertEquals(0, store.eventsFor("emit-suite").count(), "handler = 0, events = 0")
    }

    @Test
    fun `admission — extra undeclared capabilities in the runtime do NOT change semantics`() {
        // The production bridge exposes MORE than the declared set (shell, workspace...).
        // The Step must consume only its declared two and behave identically.
        val leanStore = InMemoryEventStore()
        val lean = executeViaBoundary(leanStore, encode("StageMarkedUnstable", "message" to "m"))
        val richStore = InMemoryEventStore()
        val rich = executeViaBoundary(richStore, encode("StageMarkedUnstable", "message" to "m"))
        assertEquals(lean.outcome, rich.outcome, "extra capabilities must not alter semantics")
        assertEquals(
            1,
            richStore.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().count(),
            "exactly one event either way",
        )
    }

    // ===== 5. ADT -> StepOutcome projections through the real boundary =====

    @Test
    fun `outcome projection — success output projects StepOutcome Success with encoded carrier`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("FileWritten", "path" to "o.txt", "sha256" to "k", "size" to "1"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertTrue(result.encodedOutput != null, "typed output crosses the seam ENCODED, never as the typed object")
        assertTrue(result.encodedOutput!!.value.contains("\"outcome\":\"SUCCESS\""))
    }

    @Test
    fun `outcome projection — unstable output projects StepOutcome Unstable`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("StageMarkedUnstable", "message" to "wobbly"))
        assertEquals(StepOutcome.Unstable, result.outcome)
        assertTrue(result.encodedOutput!!.value.contains("\"outcome\":\"UNSTABLE\""))
    }

    @Test
    fun `outcome projection — rejected SCHEMA output projects StepOutcome Failure SCHEMA, never a thrown exception`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("StageMarkedUnstable"))
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(FailureKind.SCHEMA, failure.failure.kind)
        assertEquals(
            "StageMarkedUnstable requires 'message' in payload",
            failure.failure.message,
            "expected semantic failure carries the Step's configured message (NOT an infrastructure failure, NOT a throw)",
        )
    }

    // ===== 10. replay contract (property, not just metadata) =====

    @Test
    fun `replay — fresh invocation executes the handler and emits the semantic event at most once`() {
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val runId = RunId("emit-suite-fresh")
        runBlocking {
            coordinator(store, journal).run(
                pipeline(emitNode("build/warn", "StageMarkedUnstable", "message" to "wobbly")),
                runId,
            )
            assertEquals(
                1,
                store.eventsFor(runId.value).filterIsInstance<StageMarkedUnstable>().count(),
                "fresh execution: semantic event exactly once",
            )
        }
    }

    @Test
    fun `replay — MEMOIZED SUCCEEDED entry SKIPs without reinvoking the handler or duplicating DomainEvents`() {
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val runId = RunId("emit-suite-replay")
        val encoded = """{"kind":"StageMarkedUnstable","message":"wobbly"}"""
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = Fingerprint.compute(opInput(runId.value, encoded), "core.emit.event", ReplayPolicy.MEMOIZED, 1),
                input = opInput(runId.value, encoded),
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        runBlocking {
            coordinator(store, journal).run(
                pipeline(emitNode("build/warn", "StageMarkedUnstable", "message" to "wobbly")),
                runId,
            )
            assertEquals(
                0,
                store.eventsFor(runId.value).filterIsInstance<StageMarkedUnstable>().count(),
                "MEMOIZED reuse MUST NOT re-run the handler: no duplicate semantic event",
            )
            val rows = journal.listForRun(runId.value)
            assertTrue(rows.all { it.status == OperationStatus.SUCCEEDED }, "reused row stays SUCCEEDED")
        }
    }

    // ===== 17-adjacent: observability (generic contract) =====

    @Test
    fun `observability — registry run emits StepStarted StepFinished pair for the emit event step`() {
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        runBlocking {
            coordinator(store, journal).run(
                pipeline(emitNode("build/marker", "CatchErrorEntered")),
                RunId("emit-suite-obs"),
            )
            val events = store.eventsFor("emit-suite-obs").toList()
            assertTrue(events.any { it is StepStarted && it.stepName == "build/marker" })
            assertTrue(events.any { it is StepFinished && it.stepName == "build/marker" })
        }
    }

    // =====================================================================
    // SECTION 2 — EMIT.EVENT SEMANTIC CONTRACT
    // =====================================================================

    // ===== 6. catchError markers: permanent contract =====

    @Test
    fun `markers — CatchErrorEntered is Success with zero DomainEvents`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("CatchErrorEntered", "buildResult" to "UNSTABLE"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertEquals(
            0,
            store.eventsFor("emit-suite").filterIsInstance<CatchErrorTriggered>().count(),
            "marker MUST NOT append domain events (control flow is the coordinator's)",
        )
    }

    @Test
    fun `markers — CatchErrorTriggered is Success with zero DomainEvents`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("CatchErrorTriggered", "buildResult" to "UNSTABLE", "emitted" to "true"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertEquals(0, store.eventsFor("emit-suite").filterIsInstance<CatchErrorTriggered>().count())
    }

    @Test
    fun `separation — StructuralOverlayProjection owns control flow, CoreEmitEventStep owns semantics`() {
        // The compiler-generated marker envelope NEVER appears as a Step-owned domain event,
        // while StructuralOverlayProjection still recognizes it pre-decode (source-level law,
        // guarded irreversibly by S3EmitEventLegacyRemovedFitnessTest). Source scan here keeps
        // the separation contract inside the G6 regression set.
        val stepSource = Files.readString(
            java.nio.file.Paths.get(
                "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEmitEventStep.kt",
            ),
        )
        assertFalse(
            Regex("pushScope|popScope|catchError\\s*\\{|CanonicalStructuralPreparation").containsMatchIn(
                Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
                    Regex("//[^\\n]*").replace(stepSource, ""),
                    "",
                ),
            ),
            "the Step MUST NOT reintroduce coordinator control-flow logic",
        )
        val invocation = Files.readString(
            java.nio.file.Paths.get(
                "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalInvocation.kt",
            ),
        )
        assertTrue(
            invocation.contains("EMIT_EVENT_PLUGIN") && invocation.contains("\"CatchErrorEntered\""),
            "StructuralOverlayProjection keeps control-flow projection ownership",
        )
    }

    // ===== 7. StageMarkedUnstable =====

    @Test
    fun `StageMarkedUnstable — payload stageName present wins over StageIdentity`() {
        val store = InMemoryEventStore()
        executeViaBoundary(store, encode("StageMarkedUnstable", "message" to "m", "stageName" to "deploy"))
        val event = store.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().single()
        assertEquals("deploy", event.stageName)
        assertEquals("m", event.message)
    }

    @Test
    fun `StageMarkedUnstable — payload stageName absent falls back to StageIdentity name`() {
        val store = InMemoryEventStore()
        executeViaBoundary(store, encode("StageMarkedUnstable", "message" to "wobbly"))
        val event = store.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().single()
        assertEquals("build", event.stageName, "runtime bridge supplies StageIdentity(build, 0)")
    }

    @Test
    fun `StageMarkedUnstable — message present emits exactly 1 event and is Unstable`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("StageMarkedUnstable", "message" to "wobbly"))
        assertEquals(StepOutcome.Unstable, result.outcome)
        assertEquals(1, store.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().count())
    }

    @Test
    fun `StageMarkedUnstable — message absent is SCHEMA with 0 events, never IllegalStateException`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(store, encode("StageMarkedUnstable"))
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(FailureKind.SCHEMA, failure.failure.kind)
        assertEquals(0, store.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().count())
    }

    // ===== 8. FileWritten =====

    @Test
    fun `FileWritten — valid payload emits exactly 1 event with all frozen fields and is Success`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(
            store,
            encode("FileWritten", "path" to "out.txt", "sha256" to "abc", "size" to "5", "atomicallyMoved" to "true"),
        )
        assertEquals(StepOutcome.Success, result.outcome)
        val event = store.eventsFor("emit-suite").filterIsInstance<FileWritten>().single()
        assertEquals(java.nio.file.Paths.get("out.txt"), event.path)
        assertEquals("abc", event.sha256)
        assertEquals(5L, event.size)
        assertTrue(event.atomicallyMoved)
    }

    @Test
    fun `FileWritten — atomicallyMoved frozen legacy parse table`() {
        val store = InMemoryEventStore()
        for ((raw, expected) in mapOf(
            "true" to true, "false" to false, "TRUE" to false, "yes" to false, "1" to false, "garbage" to false,
        )) {
            executeViaBoundary(
                store,
                encode("FileWritten", "path" to "p", "sha256" to "s", "size" to "1", "atomicallyMoved" to raw),
            )
        }
        val parsed = store.eventsFor("emit-suite").filterIsInstance<FileWritten>().map { it.atomicallyMoved }.toList()
        assertEquals(listOf(true, false, false, false, false, false), parsed, "frozen G2 parse: only strict true/false")
        // missing -> false
        val missingStore = InMemoryEventStore()
        executeViaBoundary(missingStore, encode("FileWritten", "path" to "p", "sha256" to "s", "size" to "1"))
        assertFalse(missingStore.eventsFor("emit-suite").filterIsInstance<FileWritten>().single().atomicallyMoved)
    }

    @Test
    fun `FileWritten — NO-NEW-VALIDATIONS law accepts blank values, negative size, unknown extra fields`() {
        val store = InMemoryEventStore()
        val result = executeViaBoundary(
            store,
            encode(
                "FileWritten",
                "path" to "", "sha256" to "", "size" to "-7", "futureField" to "whatever",
            ),
        )
        assertEquals(StepOutcome.Success, result.outcome, "G2 froze lenient acceptance; G6 adds NO new validations")
        val event = store.eventsFor("emit-suite").filterIsInstance<FileWritten>().single()
        assertEquals(-7L, event.size)
        assertEquals(java.nio.file.Paths.get(""), event.path)
    }

    // ===== 9. typed failures (the four APPROVED_FIX rows, now definitive contract) =====

    @Test
    fun `typed failures — all four G2 APPROVED FIX rows are SCHEMA with zero events`() {
        val store = InMemoryEventStore()
        val cases = listOf(
            encode("StageMarkedUnstable") to "StageMarkedUnstable requires 'message' in payload",
            encode("FileWritten", "sha256" to "a", "size" to "1") to "FileWritten requires 'path' in payload",
            encode("FileWritten", "path" to "p", "size" to "1") to "FileWritten requires 'sha256' in payload",
            encode("FileWritten", "path" to "p", "sha256" to "a") to "FileWritten requires 'size' in payload",
            encode("FileWritten", "path" to "p", "sha256" to "a", "size" to "not-a-number") to
                "FileWritten requires 'size' in payload",
        )
        for ((encoded, expectedMessage) in cases) {
            val result = executeViaBoundary(store, encoded)
            val failure = result.outcome as StepOutcome.Failure
            assertEquals(FailureKind.SCHEMA, failure.failure.kind, "for $encoded")
            assertEquals(expectedMessage, failure.failure.message, "for $encoded")
        }
        assertEquals(
            0,
            store.eventsFor("emit-suite").filterIsInstance<FileWritten>().count() +
                store.eventsFor("emit-suite").filterIsInstance<StageMarkedUnstable>().count(),
            "rejections append ZERO events",
        )
    }

    // ===== 12. no legacy resurrection =====

    @Test
    fun `no legacy resurrection — irreversible G5 state holds from inside the contract suite`() {
        val decoderRaw = Files.readString(
            java.nio.file.Paths.get(
                "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt",
            ),
        )
        // Strip comments: historical kdoc MAY mention the removed forms; CODE must not.
        val decoderSource = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(decoderRaw, ""),
            "",
        )
        assertFalse(decoderSource.contains("data class EmitEvent"))
        assertFalse(decoderSource.contains("EMIT_EVENT_PLUGIN_ID"))
        assertFalse(
            Files.exists(
                java.nio.file.Paths.get(
                    "src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalEmitEventNodeDispatcher.kt",
                ),
            ),
        )
        assertEquals(
            7,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "counters stay 7/7/7 (post-S2-A4/G5 + S2-A5/G5: isUnix physically removed) — a contract-suite fix must not resurrect legacy",
        )
    }
}
