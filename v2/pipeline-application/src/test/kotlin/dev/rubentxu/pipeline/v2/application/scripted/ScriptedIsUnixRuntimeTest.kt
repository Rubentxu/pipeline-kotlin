package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreIsUnixStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PlatformIdentity
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLocation
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LFC-2R / R2 — `core.isUnix` as the FIRST productive consumer of the generic
 * scripted→registry seam (`ScriptedStepFacade.isUnix(callSite): Boolean`).
 *
 * Proven end to end:
 *  FRESH  — the returned Boolean is the EXECUTION TARGET observation delivered
 *           through PlatformIdentity (synthetic SunOS/Windows on a Linux host JVM),
 *           the canonical classifier value, the persisted IsUnixOutput value, and
 *           the UnixDetected event value — all coherent.
 *  REUSE  — the returned Boolean equals the PERSISTED observation even when the
 *           registry is EMPTY, capabilities are EMPTY, and the platform changed;
 *           handler invocations == 0, new events == 0.
 *  ERRORS — fail closed: no fabricated Boolean, `error → false` can never happen.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScriptedIsUnixRuntimeTest {

    // ---- capability bridge with a synthetic platform observation ---------------

    /** Real bridge subclass that overrides ONLY the platform observation source. */
    private class SyntheticPlatformAccess(
        context: CanonicalRuntimeContext,
        private val osName: String,
        private val platformReads: AtomicInteger = AtomicInteger(0),
    ) : CanonicalRuntimeCapabilityAccess(context) {
        override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T {
            if (key == PLATFORM_IDENTITY_CAPABILITY) {
                platformReads.incrementAndGet()
                return PlatformIdentity(osName = osName) as T
            }
            return super.get(key)
        }

        fun reads(): Int = platformReads.get()
    }

    // ---- harness ---------------------------------------------------------------

    private val runId = "r2-isunix-run"

    private fun contextFor(call: ScriptedRegistryCall, sink: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId(call.runId, 0, call.invocationOrdinal),
            runId = call.runId,
            stageName = "scripted",
            stageIndex = 0,
            stepIndex = call.invocationOrdinal,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = Files.createTempDirectory("r2-"),
            eventSink = sink,
        )

    /** Builds an invoker whose capability bridge observes [osName] instead of the host JVM. */
    private fun invokerObserving(
        osName: String,
        registry: InMemoryStepRegistry,
        journal: InMemoryOperationJournal,
        sink: InMemoryEventStore,
        platformReads: AtomicInteger = AtomicInteger(0),
    ): ScriptedRegistryInvoker = ScriptedRegistryInvoker(
        registry = registry,
        journal = journal,
        clock = SystemClock(),
        runtimeContextFactory = { call -> contextFor(call, sink) },
        capabilityAccessFactory = { context -> SyntheticPlatformAccess(context, osName, platformReads) },
    )

    private fun registryWithIsUnix(): InMemoryStepRegistry =
        InMemoryStepRegistry().also { CoreIsUnixStep.registerInto(it) }

    private fun compiledEntryPoint(observed: MutableList<Boolean>) = object : CompiledScriptedEntryPoint {
        override val artifact = ScriptedArtifactIdentity(
            sourceDigest = "r2-src",
            dslApiVersion = "test",
            compilerAdapterVersion = "test",
            runtimeCompatibilityVersion = "test",
            pluginLockDigest = "test",
            facadeSchemaDigest = "test",
        )
        override val entryPointId = "ep-isunix"

        override suspend fun execute(steps: ScriptedStepFacade) {
            observed += steps.isUnix(ScriptedCallSiteId("r2.kts:5:9:isUnix"))
        }
    }

    private fun runtime(invoker: ScriptedRegistryInvoker) = ScriptedArtifactRuntime(
        operationRuntime = { error("no shell in R2") },
        registryInvoker = invoker,
    )

    private fun unixDetected(sink: InMemoryEventStore): List<UnixDetected> =
        sink.eventsFor(runId).filterIsInstance<UnixDetected>().toList()

    // ---- FRESH -----------------------------------------------------------------

    @Test
    fun `fresh execution target value - SunOS on a Linux host returns true, not the host observation`() = runBlocking {
        val registry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val observed = mutableListOf<Boolean>()
        runtime(invokerObserving("SunOS", registry, journal, sink))
            .execute(runId, compiledEntryPoint(observed))

        assertEquals(listOf(true), observed, "execution-target observation must win over the Linux host JVM")
        assertEquals(1, unixDetected(sink).size)
        assertEquals("SunOS", unixDetected(sink).single().osName)
        assertTrue(unixDetected(sink).single().isUnix)
    }

    @Test
    fun `canonical classifier matrix through the full chain - Windows 11, Mac OS X, OpenBSD, empty`() = runBlocking {
        data class Row(val osName: String, val expected: Boolean)

        for (row in listOf(Row("Windows 11", false), Row("Mac OS X", true), Row("OpenBSD", true), Row("", false))) {
            val rowRunId = runId + "-row-" + row.osName.hashCode()
            val registry = registryWithIsUnix()
            val sink = InMemoryEventStore()
            val journal = InMemoryOperationJournal(SystemClock())
            val observed = mutableListOf<Boolean>()
            runtime(invokerObserving(row.osName, registry, journal, sink))
                .execute(rowRunId, compiledEntryPoint(observed))

            assertEquals(listOf(row.expected), observed, "osName=" + row.osName)
            val event = unixDetectedRow(sink, rowRunId).single()
            assertEquals(row.expected, event.isUnix)
            // Event/value coherence: facade return == persisted IsUnixOutput == UnixDetected.
            val persistedRow = journal.listForRun(rowRunId).single {
                it.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED
            }
            val persistedOutput = CoreIsUnixStep.definition.contract.outputCodec.decode(
                EncodedStepValue((persistedRow.output!!.result as kotlinx.serialization.json.JsonPrimitive).content),
            )
            assertEquals(persistedOutput.isUnix, observed.single())
            assertEquals(persistedOutput.isUnix, event.isUnix)
        }
    }

    private fun unixDetectedRow(sink: InMemoryEventStore, rowRunId: String): List<UnixDetected> =
        sink.eventsFor(rowRunId).filterIsInstance<UnixDetected>().toList()

    // ---- REUSE (the decisive proof) --------------------------------------------

    @Test
    fun `reuse with EMPTY registry, EMPTY capabilities and CHANGED platform returns persisted true`() = runBlocking {
        // FRESH: SunOS -> true, journaled.
        val freshRegistry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val freshReads = AtomicInteger(0)
        val freshInvoker = invokerObserving("SunOS", freshRegistry, journal, sink, freshReads)
        val freshObserved = mutableListOf<Boolean>()
        runtime(freshInvoker).execute(runId, compiledEntryPoint(freshObserved))
        assertEquals(listOf(true), freshObserved)
        assertEquals(1, freshReads.get())

        // REUSE: EMPTY registry, invoker cannot consult ANY capability (Windows view),
        // yet the answer is the PERSISTED SunOS observation.
        val reuseReads = AtomicInteger(0)
        val emptyRegistry = InMemoryStepRegistry()
        val reuseInvoker = invokerObserving("Windows 11", emptyRegistry, journal, sink, reuseReads)
        val reuseObserved = mutableListOf<Boolean>()
        runtime(reuseInvoker).execute(runId, compiledEntryPoint(reuseObserved))

        assertEquals(listOf(true), reuseObserved, "reuse must reproduce the persisted observation, not the new platform")
        assertEquals(0, reuseReads.get(), "PlatformIdentity must NOT be read during reuse")
        assertEquals(1, unixDetected(sink).size, "no new UnixDetected events during reuse")
    }

    @Test
    fun `reuse after platform change - Windows persisted false stays false when host would say Unix`() = runBlocking {
        val freshRegistry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val freshObserved = mutableListOf<Boolean>()
        runtime(invokerObserving("Windows 11", freshRegistry, journal, sink))
            .execute(runId, compiledEntryPoint(freshObserved))
        assertEquals(listOf(false), freshObserved)

        val reuseObserved = mutableListOf<Boolean>()
        val emptyRegistry = InMemoryStepRegistry()
        runtime(invokerObserving("Linux", emptyRegistry, journal, sink))
            .execute(runId, compiledEntryPoint(reuseObserved))
        assertEquals(listOf(false), reuseObserved, "reuse must NOT re-observe even when the new platform flips the answer")
        assertEquals(1, unixDetected(sink).size)
    }

    // ---- call-site identity ------------------------------------------------------

    @Test
    fun `multiple call sites and loop ordinals are distinct durable operations`() = runBlocking {
        val registry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val observed = mutableListOf<Boolean>()

        val ep = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(
                sourceDigest = "r2-multi",
                dslApiVersion = "t", compilerAdapterVersion = "t",
                runtimeCompatibilityVersion = "t", pluginLockDigest = "t", facadeSchemaDigest = "t",
            )
            override val entryPointId = "ep-multi"

            override suspend fun execute(steps: ScriptedStepFacade) {
                val a = steps.isUnix(ScriptedCallSiteId("m.kts:3:5:isUnix"))
                val b = steps.isUnix(ScriptedCallSiteId("m.kts:4:5:isUnix"))
                repeat(3) { i ->
                    steps.scoped(dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId("loop:m.kts:5:9[$i]")) {
                        observed += steps.isUnix(ScriptedCallSiteId("m.kts:6:9:isUnix"))
                    }
                }
                observed += listOf(a, b)
            }
        }
        runtime(invokerObserving("SunOS", registry, journal, sink)).execute(runId, ep)

        assertEquals(5, observed.size)
        assertTrue(observed.all { it })
        assertEquals(5, unixDetected(sink).size, "each durable call-site/ordinal executes its handler once")

        // A second execution of the same entry point reuses every operation: no new events.
        val before = unixDetected(sink).size
        runtime(invokerObserving("Windows 11", InMemoryStepRegistry(), journal, sink)).execute(runId, ep)
        assertEquals(before, unixDetected(sink).size, "repeat execution must reuse all five durable operations")
    }

    @Test
    fun `unix call-site identity differs from shell identity at the same source position`() {
        val loc = ScriptedSourceLocation(ScriptedSourceId("p.kts"), 7, 12)
        assertNotEquals(loc.shellCallSite().value, loc.unixCallSite().value)
        assertTrue(loc.unixCallSite().value.endsWith(":isUnix"))
        assertEquals(loc.unixCallSite(), loc.unixCallSite(), "identity must be stable")
    }

    // ---- fail closed -------------------------------------------------------------

    @Test
    fun `fail closed - missing step on fresh throws, never fabricates a Boolean`() {
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val emptyRegistry = InMemoryStepRegistry()
        val observed = mutableListOf<Boolean>()
        val ex = assertThrows(PipelineStepException::class.java) {
            runBlocking {
                runtime(invokerObserving("SunOS", emptyRegistry, journal, sink))
                    .execute(runId, compiledEntryPoint(observed))
            }
        }
        assertEquals(FailureKind.SCHEMA, ex.failure.kind)
        assertTrue(observed.isEmpty(), "no Boolean may reach the Kotlin frame on failure")
        assertEquals(0, unixDetected(sink).size)
    }

    @Test
    fun `fail closed - missing PLATFORM_IDENTITY on fresh rejects admission before any effect`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registryWithIsUnix(),
            key = CoreIsUnixStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
    }

    @Test
    fun `fail closed - SUCCEEDED row without output throws REPLAY_COMPATIBILITY, never false`() = runBlocking {
        val registry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val fresh = mutableListOf<Boolean>()
        runtime(invokerObserving("SunOS", registry, journal, sink)).execute(runId, compiledEntryPoint(fresh))
        assertEquals(listOf(true), fresh)

        // Corrupt the journal: SUCCEEDED with no persisted output.
        val succeededRows = journal.listForRun(runId)
            .filter { it.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED }
        assertTrue(succeededRows.isNotEmpty())
        succeededRows.forEach { original ->
            journal.append(
                dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation(
                    id = original.id,
                    fingerprint = original.fingerprint,
                    input = original.input,
                    output = null,
                    status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
                    attempt = 1,
                    cachedOutput = null,
                ),
            )
        }

        val reuse = mutableListOf<Boolean>()
        val ex = assertThrows(PipelineStepException::class.java) {
            runBlocking {
                runtime(invokerObserving("SunOS", InMemoryStepRegistry(), journal, sink))
                    .execute(runId, compiledEntryPoint(reuse))
            }
        }
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, ex.failure.kind)
        assertTrue(reuse.isEmpty())
    }

    @Test
    fun `fail closed - corrupt persisted output throws REPLAY_COMPATIBILITY, never false`() = runBlocking {
        val registry = registryWithIsUnix()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val fresh = mutableListOf<Boolean>()
        runtime(invokerObserving("SunOS", registry, journal, sink)).execute(runId, compiledEntryPoint(fresh))

        val rows = journal.listForRun(runId)
            .filter { it.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED }
        rows.forEach { original ->
            journal.append(
                dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation(
                    id = original.id,
                    fingerprint = original.fingerprint,
                    input = original.input,
                    output = dev.rubentxu.pipeline.v2.domain.durable.OperationOutput(
                        result = kotlinx.serialization.json.JsonPrimitive("not-a-isunix-payload"),
                        durationMs = 1,
                        finishedAt = 1,
                    ),
                    status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
                    attempt = 1,
                    cachedOutput = null,
                ),
            )
        }

        val reuse = mutableListOf<Boolean>()
        val ex = assertThrows(PipelineStepException::class.java) {
            runBlocking {
                runtime(invokerObserving("SunOS", InMemoryStepRegistry(), journal, sink))
                    .execute(runId, compiledEntryPoint(reuse))
            }
        }
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, ex.failure.kind)
        assertTrue(reuse.isEmpty(), "a corrupted durable value must abort, not decode to false")
    }

    @Test
    fun `fail closed - facade without invoker fails loud, never returns a fabricated value`() = runBlocking {
        // R1-compatible shell-only runtime: no registry invoker wired.
        val facadeOnlyRuntime = ScriptedArtifactRuntime(operationRuntime = { error("no shell") })
        val observed = mutableListOf<Boolean>()
        val ex = assertThrows(dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation::class.java) {
            runBlocking { facadeOnlyRuntime.execute(runId, compiledEntryPoint(observed)) }
        }
        assertTrue(observed.isEmpty())
        assertTrue(ex.message!!.contains("invoker"))
    }

    // ---- architecture fitness ------------------------------------------------------

    @Test
    fun `architecture fitness - facade isUnix contains no classification or observation authority`() {
        val raw = java.nio.file.Paths.get(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/CompiledScriptedEntryPoint.kt",
        ).toFile().readText()
        val source = raw
            .replace(Regex("/\\*\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        for (forbidden in listOf(
            "System.getProperty",
            "classifyUnix",
            "UnixPlatformClassifier",
            "PlatformIdentity",
            "RuntimeConfig",
            "os.name",
        )) {
            assertFalse(source.contains(forbidden), "facade must not own classification authority: '$forbidden'")
        }
    }

    @Test
    fun `architecture fitness - facade must not hand-decode the payload as a parallel contract`() {
        val raw = java.nio.file.Paths.get(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/CompiledScriptedEntryPoint.kt",
        ).toFile().readText()
        val source = raw
            .replace(Regex("/\\*\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        for (forbidden in listOf("toBooleanStrict", "jsonObject[", "getValue(\"isUnix\")")) {
            assertFalse(source.contains(forbidden), "parallel hand-written decoder forbidden: '$forbidden'")
        }
    }
}
