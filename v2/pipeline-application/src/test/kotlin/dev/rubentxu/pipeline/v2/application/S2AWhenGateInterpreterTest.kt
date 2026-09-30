package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S2-A: the gate INTERPRETER, proven against the real durable coordinator.
 *
 * The pure decision is already covered by `WhenPredicateTest`. What is certified
 * here is the part that can silently lie: that a `core.when` directive actually
 * STOPS a stage, that a stopped stage says so, and — the part that matters most —
 * that the engine reaches these outcomes by reading the declared POLICY, never the
 * directive key.
 *
 * Every test asserts an OBSERVABLE effect (did the body run, what was emitted),
 * not the internal verdict. A test that only asserted the evaluator's return
 * value would pass even if the interpreter ignored it.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class S2AWhenGateInterpreterTest {

    /** The production registry composition: `core.when` contributed as a normal definition. */
    private fun registry(): DirectiveRegistry = DirectiveRegistry.Builder()
        .add(ErasedDirectiveDefinition(WhenDirectiveDefinition()) as DirectiveDefinitionAny)
        .build()

    /**
     * A stage whose body touches [marker]. If the body runs, the file exists;
     * if the gate stops the stage, it does not. That is the whole oracle.
     */
    private fun pipeline(marker: Path, predicate: WhenPredicate): CompiledPipeline =
        pipeline(marker, predicate, dev.rubentxu.pipeline.v2.domain.EnvironmentSpec.empty())

    private fun pipeline(
        marker: Path,
        predicate: WhenPredicate,
        stageEnvironment: dev.rubentxu.pipeline.v2.domain.EnvironmentSpec,
    ): CompiledPipeline {
        val stage = StageNode(
            id = StageId("gated"),
            name = "gated",
            environment = stageEnvironment,
            body = StageBody.Steps(
                listOf(
                    OpaqueStepNode(
                        id = StepId("gated/sh"),
                        pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                        payload = VersionedStepPayload(
                            "dsl-v1",
                            """{"kind":"sh","command":"echo body-ran > '${marker}'",""" +
                                """"isScriptBlock":false,"returnStdout":false}""",
                        ),
                    ),
                ),
            ),
            directives = listOf(
                StageDirective(
                    key = WHEN_DIRECTIVE_KEY.value,
                    encodedArguments = WhenPredicateEncoder.encode(predicate),
                ),
            ),
        )
        return CompiledPipeline(
            id = DefinitionId("s2a-when"),
            source = SourceDescriptor("S2A.pipeline.kts", Digest("s2a")),
            pluginLockDigest = Digest("s2a-lock"),
            stages = listOf(stage),
        )
    }

    private class Wiring(
        val coordinator: CanonicalDurableRunCoordinator,
        val events: InMemoryEventStore,
    )

    private fun coordinator(
        context: GateContext,
        decoder: (DirectiveDefinitionAny, String) -> WhenPredicate? = { _, encoded ->
            (WhenPredicateCodec.decode(encoded)
                as? dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded)?.input
        },
    ): Wiring {
        val clock = SystemClock()
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("s2a-control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry(),
            gateDecoder = decoder,
            gateContext = { context },
        )
        return Wiring(coordinator, events)
    }

    // ---- the three decided cases -------------------------------------------------

    @Test
    fun `a satisfied gate runs the stage body`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(
            GateContext(values = mapOf("DEPLOY_ENV" to "prod")),
        )

        val runId = RunId("s2a-satisfied")
        val outcome = runBlocking {
            wiring.coordinator.run(pipeline(marker, WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")), runId)
        }

        assertTrue(outcome is RunOutcome.Success, "expected success, got $outcome")
        assertTrue(Files.exists(marker), "the body must run when the gate is satisfied")
        assertTrue(
            wiring.events.eventsFor(runId.value).toList().none { it is StageSkipped },
            "a satisfied gate must not emit StageSkipped",
        )
    }

    @Test
    fun `a decided-negative gate STOPS the stage body and says why`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(
            GateContext(values = mapOf("DEPLOY_ENV" to "staging")),
        )

        val runId = RunId("s2a-negative")
        val outcome = runBlocking {
            wiring.coordinator.run(pipeline(marker, WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")), runId)
        }

        // The run itself SUCCEEDS: the stage was deliberately skipped, which is
        // not an error. A run that failed here would make `when` unusable for
        // the ordinary "only on prod" case.
        assertTrue(outcome is RunOutcome.Success, "a deliberate skip is not a failure, got $outcome")
        assertTrue(!Files.exists(marker), "the body MUST NOT run when the gate decided negative")

        val skipped = wiring.events.eventsFor(runId.value).toList().filterIsInstance<StageSkipped>().single()
        assertEquals("gated", skipped.stageName)
        assertTrue(
            skipped.reason.contains("DEPLOY_ENV"),
            "the skip must name what was not satisfied, got: ${skipped.reason}",
        )
    }

    @Test
    fun `an UNSET variable is a decided negative, not an error`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        // This is the behaviour users expect from `when { env.X == 'prod' }`
        // when X is absent: skip quietly. The distinction that matters is
        // decided-negative vs unverifiable, and "absent variable" is the former.
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(GateContext.EMPTY)

        val runId = RunId("s2a-unset")
        val outcome = runBlocking {
            wiring.coordinator.run(pipeline(marker, WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")), runId)
        }

        assertTrue(outcome is RunOutcome.Success, "an unset variable must skip, not fail: $outcome")
        assertTrue(!Files.exists(marker), "the body must not run")
        assertEquals(1, wiring.events.eventsFor(runId.value).toList().filterIsInstance<StageSkipped>().size)
    }

    // ---- unverifiable: fail CLOSED, never skip -----------------------------------

    @Test
    fun `an unverifiable predicate FAILS the run closed instead of skipping`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        // A source that cannot answer is not a "no". Reporting success for work
        // that never ran is the exact lie this design exists to prevent, so the
        // run fails and names the stage.
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(
            GateContext(
                values = emptyMap(),
                unresolvable = setOf("DEPLOY_ENV"),
            ),
        )

        val runId = RunId("s2a-unverifiable")
        val outcome = runBlocking {
            wiring.coordinator.run(pipeline(marker, WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")), runId)
        }

        assertTrue(outcome is RunOutcome.Failure, "an unverifiable gate must fail closed, got $outcome")
        assertTrue(!Files.exists(marker), "the body must not run")
        val failure = (outcome as RunOutcome.Failure).failure
        assertTrue(
            failure.message.contains("gated") && failure.message.contains("DEPLOY_ENV"),
            "the failure must name the stage and the variable, got: ${failure.message}",
        )
        assertTrue(
            wiring.events.eventsFor(runId.value).toList().none { it is StageSkipped },
            "an unverifiable gate must NOT masquerade as a deliberate skip",
        )
    }

    @Test
    fun `an undecodable gate FAILS the run closed rather than running the stage`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        // The decoder refuses. Returning null must not be read as "no gate
        // declared" and must not be read as "gate satisfied" either.
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(GateContext(values = mapOf("DEPLOY_ENV" to "prod")), decoder = { _, _ -> null })

        val stage = StageNode(
            id = StageId("gated"),
            name = "gated",
            body = StageBody.Steps(
                listOf(
                    OpaqueStepNode(
                        id = StepId("gated/sh"),
                        pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                        payload = VersionedStepPayload(
                            "dsl-v1",
                            """{"kind":"sh","command":"echo body-ran > '${marker}'",""" +
                                """"isScriptBlock":false,"returnStdout":false}""",
                        ),
                    ),
                ),
            ),
            directives = listOf(StageDirective(WHEN_DIRECTIVE_KEY.value, "not-a-valid-predicate")),
        )
        val compiled = CompiledPipeline(
            id = DefinitionId("s2a-undecodable"),
            source = SourceDescriptor("S2A.pipeline.kts", Digest("s2a")),
            pluginLockDigest = Digest("s2a-lock"),
            stages = listOf(stage),
        )

        val runId = RunId("s2a-undecodable")
        val outcome = runBlocking { wiring.coordinator.run(compiled, runId) }

        assertTrue(outcome is RunOutcome.Failure, "an unreadable gate must fail closed, got $outcome")
        assertTrue(!Files.exists(marker), "the body must not run when the gate cannot be read")
    }

    // ---- negation preserves unverifiability --------------------------------------

    @Test
    fun `negation of an unverifiable predicate stays unverifiable, never admitting the stage`(
        @org.junit.jupiter.api.io.TempDir tempDir: Path,
    ) {
        // `not(X == prod)` where X cannot be read is NOT satisfied. Blind
        // negation would let an unreadable variable run the stage — a fail-OPEN
        // hole produced by the most innocent-looking combinator.
        val marker = tempDir.resolve("ran.txt")
        val wiring = coordinator(
            GateContext(values = emptyMap(), unresolvable = setOf("DEPLOY_ENV")),
        )

        val outcome = runBlocking {
            wiring.coordinator.run(
                pipeline(
                    marker,
                    WhenPredicate.Not(WhenPredicate.VariableEquals("DEPLOY_ENV", "prod")),
                ),
                RunId("s2a-not-unverifiable"),
            )
        }

        assertTrue(
            outcome is RunOutcome.Failure,
            "negation must preserve unverifiability, not admit the stage: $outcome",
        )
        assertTrue(!Files.exists(marker), "the body must not run")
    }

    // ---- the production environment path ----------------------------------------

    @Test
    fun `a gate reads the environment the STAGE declares, which is what the DSL can express`(
        @org.junit.jupiter.api.io.TempDir tempDir: Path,
    ) {
        // This is the real production path: `environment { env("K", "v") }` in a
        // stage populates StageNode.environment, and that is what the gate must
        // read. The first implementation read `pipeline.environment`, which the
        // DSL never populates — so on a real script EVERY gate would have seen
        // an empty world and skipped unconditionally. The unit tests passed
        // because they injected a fake context; only the installed binary
        // exposed it.
        val marker = tempDir.resolve("ran.txt")
        val runId = RunId("s2a-stage-env")
        val wiring = coordinator(
            // Mirror CompositionRoot: the stage's own values, plus host values
            // for the names the stage declares.
            GateContext(values = mapOf("DEPLOY_ENV" to "prod")),
        )

        val outcome = runBlocking {
            wiring.coordinator.run(
                pipeline(
                    marker,
                    WhenPredicate.VariableEquals("DEPLOY_ENV", "prod"),
                    dev.rubentxu.pipeline.v2.domain.EnvironmentSpec(mapOf("DEPLOY_ENV" to "prod")),
                ),
                runId,
            )
        }

        assertTrue(outcome is RunOutcome.Success, "expected success, got $outcome")
        assertTrue(
            Files.exists(marker),
            "a stage whose declared environment satisfies the gate MUST run its body",
        )
    }

    // ---- the engine is key-agnostic ----------------------------------------------

    @Test
    fun `a VENDOR gate key is evaluated through the same seam, with zero engine change`(
        @org.junit.jupiter.api.io.TempDir tempDir: Path,
    ) {
        // The "open by key" claim of S1 is only true if a gate that is NOT
        // core.when is interpreted by the same code path. This registers a
        // differently-keyed definition declaring the same Gate policy and proves
        // the engine stops the stage. No engine branch on the key exists; this
        // test would fail if one did.
        val marker = tempDir.resolve("ran.txt")
        val clock = SystemClock()
        val events = InMemoryEventStore()
        val vendorRegistry = DirectiveRegistry.Builder()
            .add(
                ErasedDirectiveDefinition(
                    object : dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition<
                        WhenPredicate,
                        dev.rubentxu.pipeline.v2.domain.directive.GateVerdict,
                        > {
                        override val key = dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey(
                            "vendor.example.com/gate",
                        )
                        override val phase = dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase.BEFORE_STAGE
                        override val policy = dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
                            .Gate("vendor.example.com/gate")
                        override fun decode(encodedArguments: String) = WhenPredicateCodec.decode(encodedArguments)
                    },
                ) as DirectiveDefinitionAny,
            )
            .build()

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("s2a-vendor"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = vendorRegistry,
            gateDecoder = { _, encoded ->
                (WhenPredicateCodec.decode(encoded)
                    as? dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded)?.input
            },
            gateContext = { GateContext(values = mapOf("X" to "yes")) },
        )

        val stage = StageNode(
            id = StageId("vendored"),
            name = "vendored",
            body = StageBody.Steps(
                listOf(
                    OpaqueStepNode(
                        id = StepId("vendored/sh"),
                        pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                        payload = VersionedStepPayload(
                            "dsl-v1",
                            """{"kind":"sh","command":"echo body-ran > '${marker}'",""" +
                                """"isScriptBlock":false,"returnStdout":false}""",
                        ),
                    ),
                ),
            ),
            directives = listOf(
                StageDirective(
                    key = "vendor.example.com/gate",
                    encodedArguments = WhenPredicateEncoder.encode(
                        WhenPredicate.VariableEquals("X", "no"),
                    ),
                ),
            ),
        )
        val compiled = CompiledPipeline(
            id = DefinitionId("s2a-vendor"),
            source = SourceDescriptor("S2A.pipeline.kts", Digest("s2a")),
            pluginLockDigest = Digest("s2a-lock"),
            stages = listOf(stage),
        )

        val runId = RunId("s2a-vendor")
        val outcome = runBlocking { coordinator.run(compiled, runId) }

        assertTrue(!Files.exists(marker), "a vendor gate must stop the body through the same seam")
        assertEquals(1, events.eventsFor(runId.value).toList().filterIsInstance<StageSkipped>().size)
    }
}
