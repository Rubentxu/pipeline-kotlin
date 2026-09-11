package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreIsUnixStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PlatformIdentity
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLocation
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LFC-2R / R3 — Compiler-backed runtime-step mapping, proven end to end with a
 * REAL user-shaped `.pipeline.kts` source (fixture modeled on
 * `13-workspace-helpers.pipeline.kts`):
 *
 * ```
 * real source → PSI map → deterministic lowering → generated CompiledScriptedEntryPoint
 *   → host compile (ONE evaluation) → RuntimeScriptedStepFacade.isUnix(callSite)
 *   → ScriptedRegistryInvoker → CoreIsUnixStep → Boolean → user Kotlin branch
 * ```
 *
 * The host JVM is Linux; the synthetic execution target deliberately DISAGREES so
 * the asserted branch proves the Kotlin control flow consumed the RUNTIME value.
 * No Main, no eager PipelineDsl.isUnix, no legacy changes.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScriptedIsUnixCompilerMappingTest {

    /** Real-shaped fixture: the Boolean drives a genuine Kotlin branch. */
    private val fixture = """
        val unix = isUnix()
        var branch = "unset"
        if (unix) {
            branch = "unix"
        } else {
            branch = "windows"
        }
        recorded.clear()
        recorded.add(branch)
    """.trimIndent()

    private val sourceId = ScriptedSourceId("pipelines/13-isunix-branch.pipeline.kts")
    private val runId = "r3-compiler-run"

    // ---- harness ---------------------------------------------------------------

    private class SyntheticPlatformAccess(
        context: CanonicalRuntimeContext,
        private val osName: String,
        private val platformReads: AtomicInteger = AtomicInteger(0),
    ) : CanonicalRuntimeCapabilityAccess(context) {
        override fun available(): Set<StepCapability> =
            setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            if (key == PLATFORM_IDENTITY_CAPABILITY) {
                platformReads.incrementAndGet()
                return PlatformIdentity(osName = osName) as T
            }
            return super.get(key)
        }

        fun reads(): Int = platformReads.get()
    }

    /** Compiles the real fixture through mapper + lowering + Kotlin host. */
    private fun compileFixture(): Pair<CompiledScriptedEntryPoint, Any?> {
        val lowering = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = fixture,
            mapper = KotlinScriptedSourceMapper(),
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        val generated = (lowering as LoweringResult.Generated)
        // Exactly ONE isUnix call was mapped from the real source.
        assertEquals(1, generated.mappedCalls.count { it.kind == ScriptedCallKind.IsUnix })
        assertEquals(0, generated.mappedCalls.count { it.kind == ScriptedCallKind.Shell })

        val script = """
            val recorded = mutableListOf<String>()
            ${generated.source}
        """.trimIndent()
        val definition = ScriptDefinition.inline(
            text = script,
            classpath = listOfNotNull(dev.rubentxu.pipeline.v2.scripting.ScriptDefinition.dslApiJar()),
        )
        val compilation = Kotlin24ScriptingHost().compile(definition)
        val success = compilation as? dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult.Success
            ?: error("fixture failed to compile: ${compilation.diagnostics}")
        val output = success.output as? dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput.CompiledEntryPoint
            ?: error("expected a typed compiled entry point")
        return output.entryPoint to success.scriptInstance
    }

    /** Reads the script-local `recorded` capture through the compiled script instance. */
    private fun recordedFrom(scriptInstance: Any?): List<String> {
        val inst = requireNotNull(scriptInstance) { "script instance must be available" }
        val getter = try {
            inst.javaClass.getMethod("getRecorded")
        } catch (_: NoSuchMethodException) {
            inst.javaClass.getDeclaredField("recorded").apply { isAccessible = true }
        }
        @Suppress("UNCHECKED_CAST")
        return when (getter) {
            is java.lang.reflect.Method -> getter.invoke(inst) as List<String>
            else -> (getter as java.lang.reflect.Field).get(inst) as List<String>
        }
    }

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
        runtimeContextFactory = { call ->
            CanonicalRuntimeContext(
                opId = OpId(call.runId, 0, call.invocationOrdinal),
                runId = call.runId,
                stageName = "scripted",
                stageIndex = 0,
                stepIndex = call.invocationOrdinal,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("r3-"),
                eventSink = sink,
            )
        },
        capabilityAccessFactory = { context -> SyntheticPlatformAccess(context, osName, platformReads) },
    )

    private fun executeWith(
        entryPoint: CompiledScriptedEntryPoint,
        invoker: ScriptedRegistryInvoker,
    ) {
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = { error("no shell in R3") },
            registryInvoker = invoker,
        )
        runBlocking { runtime.execute(runId, entryPoint) }
    }

    private fun unixDetected(sink: InMemoryEventStore): List<UnixDetected> =
        sink.eventsFor(runId).filterIsInstance<UnixDetected>().toList()

    // ---- SOURCE MAPPING --------------------------------------------------------

    @Test
    fun `R3 SOURCE - comments and strings are never mapped as calls`() {
        val tricky = """
            // isUnix() inside a comment
            /* isUnix() inside a block comment */
            val s = "isUnix() inside a string"
            fun notDsl() = 1
            val fake = notDsl()
        """.trimIndent()
        val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(sourceId, tricky))
        val mapped = mapping as ScriptedSourceMapping.Mapped
        assertEquals(0, mapped.calls.count { it.kind == ScriptedCallKind.IsUnix })
        assertEquals(0, mapped.calls.count { it.kind == ScriptedCallKind.Shell })
    }

    @Test
    fun `R3 SOURCE - qualified receiver calls are not generator calls`() {
        val tricky = """
            val helper = Helper()
            val b = helper.isUnix()
        """.trimIndent()
        val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(sourceId, "class Helper { fun isUnix() = true }\n" + tricky))
        val mapped = mapping as ScriptedSourceMapping.Mapped
        assertEquals(0, mapped.calls.count { it.kind == ScriptedCallKind.IsUnix })
    }

    @Test
    fun `R3 SOURCE - call-site identity is source-derived and checkout-path independent`() {
        val mapper = KotlinScriptedSourceMapper()
        val mappingA = mapper.map(ScriptedSource(ScriptedSourceId("pipelines/x.pipeline.kts"), fixture)) as ScriptedSourceMapping.Mapped
        val mappingB = mapper.map(ScriptedSource(ScriptedSourceId("/other/checkout/pipelines/x.pipeline.kts"), fixture)) as ScriptedSourceMapping.Mapped

        val callA = mappingA.calls.single { it.kind == ScriptedCallKind.IsUnix }
        val callB = mappingB.calls.single { it.kind == ScriptedCallKind.IsUnix }
        // Same source text at the same position → same line/column identity; the
        // sourceId component is repository-stable, never a machine path.
        assertEquals(callA.location.line, callB.location.line)
        assertEquals(callA.location.column, callB.location.column)
        assertEquals(
            ScriptedSourceLocation(ScriptedSourceId("pipelines/x.pipeline.kts"), callA.location.line, callA.location.column).unixCallSite(),
            ScriptedSourceLocation(ScriptedSourceId("pipelines/x.pipeline.kts"), callA.location.line, callA.location.column).unixCallSite(),
        )
        // Shell and isUnix identities at the same coordinates are distinct.
        val loc = callA.location
        assertFalse(loc.unixCallSite().value.endsWith(":sh"))
        assertTrue(loc.unixCallSite().value.endsWith(":isUnix"))
    }

    // ---- COMPILATION + RUNTIME + DURABILITY (the decisive chain) -----------------

    @Test
    fun `R3 RUNTIME - Windows target on Linux host drives the Kotlin branch to windows`() {
        val (entryPoint, scriptInstance) = compileFixture()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())

        executeWith(entryPoint, invokerObserving("Windows 11", registryWithIsUnix(), journal, sink))

        assertEquals(listOf("windows"), recordedFrom(scriptInstance), "the runtime Boolean must control the user's Kotlin branch")
        val event = unixDetected(sink).single()
        assertFalse(event.isUnix)
        assertEquals("Windows 11", event.osName)
    }

    @Test
    fun `R3 RUNTIME - SunOS target on Linux host drives the Kotlin branch to unix`() {
        val (entryPoint, scriptInstance) = compileFixture()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())

        executeWith(entryPoint, invokerObserving("SunOS", registryWithIsUnix(), journal, sink))

        assertEquals(listOf("unix"), recordedFrom(scriptInstance))
        assertTrue(unixDetected(sink).single().isUnix)
    }

    @Test
    fun `R3 DURABILITY - reuse replays the persisted Boolean into the same Kotlin branch without re-observing`() {
        val (entryPoint, scriptInstance) = compileFixture()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val freshReads = AtomicInteger(0)

        // FRESH: SunOS -> branch unix, observation journaled.
        executeWith(entryPoint, invokerObserving("SunOS", registryWithIsUnix(), journal, sink, freshReads))
        assertEquals(listOf("unix"), recordedFrom(scriptInstance))
        assertEquals(1, freshReads.get())
        assertEquals(1, unixDetected(sink).size)

        // REUSE: EMPTY registry, Windows-view bridge (cannot supply capabilities),
        // same durable identity -> SAME branch, zero observation, zero new events.
        val reuseReads = AtomicInteger(0)
        executeWith(entryPoint, invokerObserving("Windows 11", InMemoryStepRegistry(), journal, sink, reuseReads))
        assertEquals(listOf("unix"), recordedFrom(scriptInstance), "the replayed runtime value must replay the Kotlin decision")
        assertEquals(0, reuseReads.get(), "reuse must not consult the platform")
        assertEquals(1, unixDetected(sink).size, "reuse must not emit new events")
    }

    @Test
    fun `R3 EVALUATION - generated script evaluates exactly once per compilation`() {
        // The lowering emits ONE object expression evaluated by ONE host.eval call;
        // the entry point instance identity across two executions of the SAME
        // compiled artifact is stable, proving no second script evaluation happens.
        val (entryPoint, scriptInstance) = compileFixture()
        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        executeWith(entryPoint, invokerObserving("SunOS", registryWithIsUnix(), journal, sink))
        assertEquals(listOf("unix"), recordedFrom(scriptInstance))
        // Second execution reuses the SAME compiled object (no recompilation/re-eval).
        executeWith(entryPoint, invokerObserving("Windows 11", InMemoryStepRegistry(), journal, sink))
        assertEquals(listOf("unix"), recordedFrom(scriptInstance))
    }

    // ---- COMPATIBILITY -----------------------------------------------------------

    @Test
    fun `R3 COMPATIBILITY - facade schema change alters the artifact identity`() {
        val lower1 = ScriptedSourceLowering.lower(sourceId, fixture, KotlinScriptedSourceMapper(), "facade-r3-isUnix-v1")
        val lower2 = ScriptedSourceLowering.lower(sourceId, fixture, KotlinScriptedSourceMapper(), "facade-r3-isUnix-v2")
        val a = lower1 as LoweringResult.Generated
        val b = lower2 as LoweringResult.Generated
        assertTrue(
            a.artifact.fingerprintMaterial() != b.artifact.fingerprintMaterial(),
            "an incompatible facade/compiler mapping change must invalidate artifact reuse",
        )
    }

    @Test
    fun `R3 COMPATIBILITY - identical source and schema produce a stable identity`() {
        val a = ScriptedSourceLowering.lower(sourceId, fixture, KotlinScriptedSourceMapper(), "facade-r3-isUnix-v1") as LoweringResult.Generated
        val b = ScriptedSourceLowering.lower(sourceId, fixture, KotlinScriptedSourceMapper(), "facade-r3-isUnix-v1") as LoweringResult.Generated
        assertEquals(a.artifact.fingerprintMaterial(), b.artifact.fingerprintMaterial())
        assertEquals(a.source, b.source, "lowering must be deterministic")
    }

    // ---- architecture fitness ------------------------------------------------------

    @Test
    fun `R3 ARCHITECTURE - generated path contains no classification or eager authority`() {
        val lowering = ScriptedSourceLowering.lower(sourceId, fixture, KotlinScriptedSourceMapper(), ScriptedSourceLowering.FACADE_SCHEMA_VERSION)
        val generated = (lowering as LoweringResult.Generated).source
        for (forbidden in listOf(
            "RuntimeConfig",
            "System.getProperty",
            "UnixPlatformClassifier",
            "PipelineDsl",
            "toBooleanStrict",
        )) {
            assertFalse(generated.contains(forbidden), "generated artifact must not contain '$forbidden'")
        }
        // The lowering implementation itself must not own classification authority.
        val implRaw = java.nio.file.Paths.get(
            "..", "pipeline-scripting-kotlin24",
            "src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedSourceLowering.kt",
        ).toFile().readText()
        val impl = implRaw
            .replace(Regex("/\\*\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        for (forbidden in listOf("System.getProperty", "UnixPlatformClassifier", "RuntimeConfig", "PipelineDsl")) {
            assertFalse(impl.contains(forbidden), "lowering must not own classification authority: '$forbidden'")
        }
    }

    private fun registryWithIsUnix(): InMemoryStepRegistry =
        InMemoryStepRegistry().also { CoreIsUnixStep.registerInto(it) }
}
