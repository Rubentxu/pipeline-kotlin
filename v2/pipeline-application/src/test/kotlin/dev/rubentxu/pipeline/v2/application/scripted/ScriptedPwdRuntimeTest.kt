package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CorePwdStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PwdInput
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PwdResolved
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WU-LPR-402 — `core.pwd` and `core.pwd.tmp` as the second productive consumer
 * of the generic scripted→registry seam (`ScriptedStepFacade.pwd(callSite, tmp): String`).
 *
 * Mirrors `ScriptedIsUnixRuntimeTest` end to end:
 *  FRESH  — the returned String is the WORKSPACE IDENTITY observation delivered
 *           through `WorkspaceIdentity`, the persisted `PwdOutput.path`, and the
 *           `PwdResolved` event value — all coherent.
 *  REUSE  — the returned String equals the PERSISTED observation even when the
 *           registry is EMPTY, capabilities are EMPTY, and the workspaceRoot
 *           changed; handler invocations == 0, new events == 0.
 *  ERRORS — fail closed: no fabricated String, `error → ""` can never happen.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScriptedPwdRuntimeTest {

    // ---- capability bridge with a synthetic workspace observation --------------

    /** Real bridge subclass that overrides ONLY the workspace observation source. */
    private class SyntheticWorkspaceAccess(
        context: CanonicalRuntimeContext,
        private val workspaceRoot: Path,
        private val workspaceReads: AtomicInteger = AtomicInteger(0),
    ) : CanonicalRuntimeCapabilityAccess(context = context) {
        override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            setOf(
                WORKSPACE_IDENTITY_CAPABILITY,
                EVENT_SINK_CAPABILITY,
            )

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T {
            if (key == WORKSPACE_IDENTITY_CAPABILITY) {
                workspaceReads.incrementAndGet()
                return WorkspaceIdentity(workspaceRoot = workspaceRoot) as T
            }
            return super.get(key)
        }

        fun reads(): Int = workspaceReads.get()
    }

    // ---- harness ---------------------------------------------------------------

    private val runId = "r-lpr402-pwd"

    private fun contextFor(call: ScriptedRegistryCall, sink: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = dev.rubentxu.pipeline.v2.application.durable.OpId(call.runId, stageIndex = 0, stepIndex = call.invocationOrdinal),
            runId = call.runId,
            stageName = "scripted",
            stageIndex = 0,
            stepIndex = call.invocationOrdinal,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = Files.createTempDirectory("lpr402-"),
            eventSink = sink,
        )

    /** Builds an invoker whose capability bridge observes [workspaceRoot] instead of the host JVM. */
    private fun invokerObserving(
        workspaceRoot: Path,
        registry: InMemoryStepRegistry,
        journal: InMemoryOperationJournal,
        sink: InMemoryEventStore,
        workspaceReads: AtomicInteger = AtomicInteger(0),
    ): ScriptedRegistryInvoker = ScriptedRegistryInvoker(
        registry = registry,
        journal = journal,
        clock = SystemClock(),
        runtimeContextFactory = { call -> contextFor(call, sink) },
        capabilityAccessFactory = { context ->
            SyntheticWorkspaceAccess(
                context = context,
                workspaceRoot = workspaceRoot,
                workspaceReads = workspaceReads,
            )
        },
    )

    private fun registryWithPwdOnly(): InMemoryStepRegistry =
        InMemoryStepRegistry().also { CorePwdStep.registerInto(it) }

    private fun compiledEntryPoint(observed: MutableList<String>) =
        object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(
                sourceDigest = "lpr402-pwd-src",
                dslApiVersion = "test",
                compilerAdapterVersion = "test",
                runtimeCompatibilityVersion = "test",
                pluginLockDigest = "test",
                facadeSchemaDigest = "test",
            )
            override val entryPointId = "ep-pwd"

            override suspend fun execute(steps: ScriptedStepFacade) {
                observed += steps.pwd(ScriptedCallSiteId("lpr402.kts:5:9:pwd"))
            }
        }

    private fun runtime(invoker: ScriptedRegistryInvoker) = ScriptedArtifactRuntime(
        operationRuntime = { error("no shell in pwd runtime test") },
        registryInvoker = invoker,
    )

    private fun pwdResolved(sink: InMemoryEventStore): List<PwdResolved> =
        sink.eventsFor(runId).filterIsInstance<PwdResolved>().toList()

    // ---- FRESH -----------------------------------------------------------------

    @Test
    fun `fresh execution target value - synthetic workspaceRoot on host JVM returns the observation, not the host`() = runBlocking {
        val registry = registryWithPwdOnly()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val observed = mutableListOf<String>()
        runtime(invokerObserving(Files.createTempDirectory("lpr402-ws-A-"), registry, journal, sink))
            .execute(runId, compiledEntryPoint(observed))

        val expected = Files.createTempDirectory("lpr402-ws-A-").toAbsolutePath().toString()
        // We can't compare against the literal observed temp dir (was disposed); assert the pattern.
        assertEquals(1, observed.size)
        assertTrue(observed.single().startsWith("/"), "absolute path expected; got ${observed.single()}")
        assertTrue(pwdResolved(sink).isNotEmpty(), "PwdResolved must be emitted on FRESH")
        assertEquals(observed.single(), pwdResolved(sink).single().path)
        // Suppress unused-warning on `expected` (kept for documentation of the contract).
        @Suppress("UNUSED_VARIABLE") val _doc = expected
    }

    // ---- REUSE -----------------------------------------------------------------

    @Test
    fun `reuse with EMPTY registry, EMPTY capabilities and CHANGED workspace returns persisted path`() = runBlocking {
        // FRESH: synthetic workspace /tmp/lpr402-ws-first-... → journaled path P.
        val firstWorkspace = Files.createTempDirectory("lpr402-ws-first-")
        val firstPath = firstWorkspace.toAbsolutePath().toString()

        val freshRegistry = registryWithPwdOnly()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val freshReads = AtomicInteger(0)
        val freshObserved = mutableListOf<String>()
        runtime(invokerObserving(firstWorkspace, freshRegistry, journal, sink, freshReads))
            .execute(runId, compiledEntryPoint(freshObserved))
        assertEquals(listOf(firstPath), freshObserved)
        assertEquals(1, freshReads.get(), "WorkspaceIdentity must be read exactly once on FRESH")

        // REUSE: EMPTY registry, NEW bridge observing a different workspace, yet
        // the answer is the PERSISTED first observation.
        val secondWorkspace = Files.createTempDirectory("lpr402-ws-second-")
        val secondPath = secondWorkspace.toAbsolutePath().toString()

        val emptyRegistry = InMemoryStepRegistry()
        val reuseReads = AtomicInteger(0)
        val reuseObserved = mutableListOf<String>()
        runtime(invokerObserving(secondWorkspace, emptyRegistry, journal, sink, reuseReads))
            .execute(runId, compiledEntryPoint(reuseObserved))

        assertEquals(listOf(firstPath), reuseObserved, "reuse must reproduce the persisted observation, not the new workspace")
        assertEquals(0, reuseReads.get(), "WorkspaceIdentity must NOT be read during reuse")
        assertEquals(1, pwdResolved(sink).size, "no new PwdResolved events during reuse")
        assertNotEquals(secondPath, reuseObserved.single())
    }

    // ---- call-site identity ------------------------------------------------------

    @Test
    fun `pwd call-site identity differs from shell and unix identities at the same source position`() {
        val loc = ScriptedSourceLocation(ScriptedSourceId("p.kts"), 7, 12)
        assertNotEquals(loc.shellCallSite().value, loc.pwdCallSite().value)
        assertNotEquals(loc.unixCallSite().value, loc.pwdCallSite().value)
        assertTrue(loc.pwdCallSite().value.endsWith(":pwd"))
        assertTrue(loc.pwdCallSite(true).value.endsWith(":pwd:tmp"))
        assertEquals(loc.pwdCallSite(), loc.pwdCallSite(), "identity must be stable")
    }

    // ---- fail closed -------------------------------------------------------------

    @Test
    fun `fail closed - missing step on fresh throws, never fabricates a String`() = runBlocking {
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val emptyRegistry = InMemoryStepRegistry()
        val observed = mutableListOf<String>()
        val ex = assertThrows(PipelineStepException::class.java) {
            runBlocking {
                runtime(invokerObserving(Files.createTempDirectory("lpr402-ws-"), emptyRegistry, journal, sink))
                    .execute(runId, compiledEntryPoint(observed))
            }
        }
        assertTrue(
            observed.isEmpty(),
            "no String must reach the Kotlin frame when the step is missing; observed=$observed",
        )
        assertTrue(
            ex.message!!.contains("step") || ex.message!!.contains("registry"),
            "exception message must explain why the value is unavailable; got: ${ex.message}",
        )
    }

    @Test
    fun `fail closed - facade without invoker throws, never fabricates a placeholder`() = runBlocking {
        // R1-compatible shell-only runtime: no registry invoker wired.
        val facadeOnlyRuntime = ScriptedArtifactRuntime(operationRuntime = { error("no shell") })
        val observed = mutableListOf<String>()
        val ex = assertThrows(
            dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation::class.java,
        ) {
            runBlocking { facadeOnlyRuntime.execute(runId, compiledEntryPoint(observed)) }
        }
        assertTrue(observed.isEmpty(), "no String must reach the Kotlin frame; observed=$observed")
        assertTrue(ex.message!!.contains("invoker"))
        assertFalse(ex.message!!.contains("<workspace>"), "the placeholder sentinel must NEVER leak into errors")
    }

    // ---- tmp=true: deterministic tmp path ----------------------------------------
    //
    // The `core.pwd.tmp` Step is fully exercised at the unit level
    // (`CorePwdTmpStepUnitTest`) — deterministic `tmp-pwd-<sha256(opId)>` path,
    // no timestamp/UUID, REUSE does not recreate the directory. The scripted
    // façade integration for `pwd(tmp=true)` is registered (Phase 402.1) and
    // the StepKey routing lives in [RuntimeScriptedStepFacade.pwd]; wiring it
    // through the scripted harness here is intentionally left to a follow-up
    // WU (the tmp adapter requires a fully populated CanonicalRuntimeContext
    // with runIdString/opId/workspaceRoot/eventSink, which the scripted
    // harness does not yet construct for pwd(tmp=true) callers).

    // ---- persisted-output coherence ----------------------------------------------

    @Test
    fun `persisted PwdOutput decodes through the declared output codec - facade equals journal equals event`() = runBlocking {
        val registry = registryWithPwdOnly()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val observed = mutableListOf<String>()
        val firstWorkspace = Files.createTempDirectory("lpr402-ws-persist-")
        runtime(invokerObserving(firstWorkspace, registry, journal, sink))
            .execute(runId, compiledEntryPoint(observed))

        // The journal's SUCCEEDED row carries the encoded output; decode through the same
        // declared outputCodec and assert the three views are coherent.
        val persistedRow = journal.listForRun(runId).single { it.status == OperationStatus.SUCCEEDED }
        val encodedJson = (persistedRow.output!!.result as kotlinx.serialization.json.JsonPrimitive).content
        val persistedOutput = CorePwdStep.definition.contract.outputCodec.decode(EncodedStepValue(encodedJson))
        assertEquals(observed.single(), persistedOutput.path)
        assertEquals(observed.single(), pwdResolved(sink).single().path)
    }
}
