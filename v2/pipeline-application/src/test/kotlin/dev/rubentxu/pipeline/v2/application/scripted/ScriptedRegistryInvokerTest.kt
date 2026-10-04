package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PlatformIdentity
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
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
 * LFC-2R / R1 — generic scripted→registry seam proof.
 *
 * The fixture Step is deliberately NEUTRAL ("scripted.fixture.inc", "41" -> "42"): the
 * generic invoker must work for ANY typed registry Step and must never be written
 * around a concrete Step (architecture fitness pins step-name absence).
 *
 * Core law under test:
 *  FRESH — handler exactly once, encoded output persisted, typed value returned.
 *  REUSE — same durable identity: handler 0 invocations, capabilities NOT consulted
 *          (proven by reusing through an EMPTY registry: the durable decision happens
 *          before registry/capability resolution), reuse_value == persisted_value.
 *  Errors — fail closed; never a fabricated output.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScriptedRegistryInvokerTest {

    // ---- neutral fixture step -------------------------------------------------

    private object FixtureCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private open class FixtureStep : StepDefinition<String, String> {
        val handlerInvocations = AtomicInteger(0)
        val probeCapability: StepCapability = StepCapability("test.fixture-probe")

        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = FixtureCodec,
            outputCodec = FixtureCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ ->
            handlerInvocations.incrementAndGet()
            (input.toIntOrNull() ?: throw IllegalStateException("fixture input must be numeric"))
                .plus(1).toString()
        }

        companion object {
            val KEY = PluginStepId("scripted.fixture.inc")
        }
    }

    // ---- harness --------------------------------------------------------------

    private fun contextFor(call: ScriptedRegistryCall): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId(call.runId, 0, call.invocationOrdinal),
        runId = call.runId,
        stageName = "scripted",
        stageIndex = 0,
        stepIndex = call.invocationOrdinal,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = Files.createTempDirectory("r1-"),
        eventSink = InMemoryEventStore(),
    )

    /**
     * @param admitNoCapabilities route the observation source to one that admits NOTHING. The
     *   Step's DECLARATION is still resolvable, which is the distinction that matters after
     *   ADR-0103 RPL-4: the replay decision needs the descriptor, and needs no capability.
     */
    private fun invokerOver(
        registry: InMemoryStepRegistry,
        journal: InMemoryOperationJournal,
        admitNoCapabilities: Boolean = false,
    ): ScriptedRegistryInvoker =
        dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            capabilityAccessFactory = { context ->
                object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(context) {
                    override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                        if (admitNoCapabilities) emptySet() else super.available()
                }
            },
        )

    private fun newCall(input: String = "41", ordinal: Int = 0): ScriptedRegistryCall = ScriptedRegistryCall(
        runId = "r1-run",
        entryPointId = "ep-1",
        callSiteId = ScriptedCallSiteId("src.kts:10:5:fixture"),
        dynamicScopePath = listOf("loop:src.kts:9:3[0]"),
        invocationOrdinal = ordinal,
        stepKey = FixtureStep.KEY,
        encodedInput = EncodedStepValue(input),
        definitionDigest = "s4-test-artifact-v1",
    )

    // ---- FRESH ----------------------------------------------------------------

    @Test
    fun `fresh - registry resolution, handler exactly once, output persisted and returned`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val result = invokerOver(registry, journal).invoke(newCall())

        assertTrue(result is ScriptedRegistryResult.Success)
        assertEquals("42", (result as ScriptedRegistryResult.Success).encodedOutput.value)
        assertEquals(1, fixture.handlerInvocations.get())

        val op = journal.get(newCall().operationId())!!
        assertEquals(OperationStatus.SUCCEEDED, op.status)
        assertEquals("42", (op.output!!.result as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // ---- REUSE ----------------------------------------------------------------

    @Test
    fun `reuse - restores persisted value with zero handler invocations`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = invokerOver(registry, journal)

        val fresh = invoker.invoke(newCall())
        assertEquals("42", (fresh as ScriptedRegistryResult.Success).encodedOutput.value)
        assertEquals(1, fixture.handlerInvocations.get())

        val reuse = invoker.invoke(newCall())
        assertTrue(reuse is ScriptedRegistryResult.Success)
        assertEquals("42", (reuse as ScriptedRegistryResult.Success).encodedOutput.value)
        assertEquals(1, fixture.handlerInvocations.get(), "reuse must NOT execute the handler")
    }

    @Test
    fun `reuse - durable decision happens BEFORE capability admission and handler execution`() = runBlocking {
        // ADR-0103 RPL-4 NARROWED this law, and the narrowing is the point.
        //
        // It used to be proved by handing the reuse an invoker with an EMPTY REGISTRY, on the
        // theory that the SUCCEEDED journal hit short-circuits before any lookup at all. That
        // is no longer true and cannot be: the reuse decision consumes the Step's pre-decode
        // metadata (effects, replay policy, recovery policy), that metadata IS the descriptor,
        // and the descriptor is the single authority canonical and scripted now share. A Step
        // with no declaration has no policy to obey, so asking for reuse without one is a
        // typed SCHEMA failure rather than a silent pass.
        //
        // The capability half of the law survives intact and is what has teeth: a resume must
        // not need to OBSERVE anything. This test now proves it with the declaration present
        // and the capability absent, which is the shape production actually has.
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        invokerOver(registry, journal).invoke(newCall())
        assertEquals(1, fixture.handlerInvocations.get())

        // No capability at all: admission would reject this invocation if the durable decision
        // did not come first.
        val reuseOnlyInvoker = invokerOver(registry, journal, admitNoCapabilities = true)
        val reuse = reuseOnlyInvoker.invoke(newCall())
        assertTrue(reuse is ScriptedRegistryResult.Success)
        assertEquals("42", (reuse as ScriptedRegistryResult.Success).encodedOutput.value)
        assertEquals(1, fixture.handlerInvocations.get())
    }

    @Test
    fun `distinct ordinal or input position is a distinct durable operation (loop safety)`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = invokerOver(registry, journal)

        invoker.invoke(newCall(ordinal = 0))
        val second = invoker.invoke(newCall(input = "100", ordinal = 1))
        assertEquals("101", (second as ScriptedRegistryResult.Success).encodedOutput.value)
        assertEquals(2, fixture.handlerInvocations.get())
    }

    // ---- NEGATIVE (fail closed, never fabricated) ------------------------------

    @Test
    fun `negative - unknown stepKey fails closed before any effect`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val result = invokerOver(registry, journal).invoke(
            newCall().copy(stepKey = PluginStepId("scripted.does-not-exist")),
        )
        assertTrue(result is ScriptedRegistryResult.Failed)
        assertEquals(FailureKind.SCHEMA, (result as ScriptedRegistryResult.Failed).failure.kind)
        assertEquals(0, fixture.handlerInvocations.get())
    }

    /** Variant fixture that DECLARES a capability the real bridge cannot supply. */
    private class CapRequiringFixture : FixtureStep() {
        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = FixtureCodec,
            outputCodec = FixtureCodec,
            requiredCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, probeCapability),
        )
    }

    @Test
    fun `negative - missing capability fails closed with handler 0`() = runBlocking {
        val fixture = CapRequiringFixture()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val result = invokerOver(registry, journal).invoke(newCall())
        assertTrue(result is ScriptedRegistryResult.Failed)
        assertEquals(0, fixture.handlerInvocations.get())
    }

    @Test
    fun `negative - SUCCEEDED row without persisted output fails closed, never fabricates`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = invokerOver(registry, journal)
        invoker.invoke(newCall())

        val opId = newCall().operationId()
        val original = journal.get(opId)!!
        journal.append(
            MemoizedOperation(
                id = opId,
                fingerprint = original.fingerprint,
                input = original.input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
                cachedOutput = null,
            ),
        )
        val result = invoker.invoke(newCall())
        assertTrue(result is ScriptedRegistryResult.Failed)
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, (result as ScriptedRegistryResult.Failed).failure.kind)
        assertFalse((result as ScriptedRegistryResult.Failed).failure.message.contains("42"))
    }

    @Test
    fun `negative - diverged input on an existing operation fails closed`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = invokerOver(registry, journal)
        invoker.invoke(newCall(input = "41"))
        val result = invoker.invoke(newCall(input = "999"))
        assertTrue(result is ScriptedRegistryResult.Failed)
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, (result as ScriptedRegistryResult.Failed).failure.kind)
    }

    @Test
    fun `negative - FAILED history re-executes, because the descriptor policy says RERUN`() = runBlocking {
        val fixture = FixtureStep()
        val registry = InMemoryStepRegistry().also { it.register(fixture) }
        val journal = InMemoryOperationJournal(SystemClock())
        val invoker = invokerOver(registry, journal)

        // Handler throws for non-numeric input -> FAILED outcome, no encoded output.
        val first = invoker.invoke(newCall(input = "abc"))
        assertTrue(first is ScriptedRegistryResult.Failed)
        val afterFirst = fixture.handlerInvocations.get()

        // ADR-0103 RPL-4: this expectation INVERTED, deliberately.
        //
        // The fixture declares `replayPolicy = MEMOIZED` with `effects = [READ_ONLY]`, and under
        // the ADR-0103 D1 table a journaled FAILED row is not a reuse. Before R1-A the invoker
        // latched REPLAY_COMPATIBILITY on any non-SUCCEEDED status — a second, stricter policy
        // than the canonical one, which would have re-observed and repaired the row. Now both
        // surfaces read the same declaration, so the scripted one retries too.
        //
        // The assertion is stated against the FIRST run's count so that it pins the retry, not a
        // hard-coded 2, and so a future policy change shows up as a diff here rather than as a
        // mysterious count.
        val again = invoker.invoke(newCall(input = "abc"))
        assertTrue(again is ScriptedRegistryResult.Failed, "the retried handler fails again on the same input")
        assertEquals(
            afterFirst + 1,
            fixture.handlerInvocations.get(),
            "a FAILED row must be RETRIED under the descriptor's own policy — the same number of " +
                "times canonical would retry it",
        )
    }

    // ---- architecture fitness ---------------------------------------------------

    @Test
    fun `architecture fitness - generic invoker contains no concrete step names`() {
        val raw = java.nio.file.Paths.get(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/ScriptedRegistryInvoker.kt",
        ).toFile().readText()
        // Known gotcha: strip comments BEFORE scanning — historical kdoc legitimately
        // mentions removed identifiers; here it must not shadow the fitness.
        val source = raw
            .replace(Regex("/\\*\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        for (forbidden in listOf("core.isUnix", "core.pwd", "core.readFile", "core.fileExists", "core.sh")) {
            assertFalse(source.contains(forbidden), "generic seam must not special-case '$forbidden'")
        }
    }
}
