package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
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
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
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

/**
 * B4 — `agent { capabilities(...) }` is granted exactly when the RUN's composition supplies the
 * capability, and refused otherwise.
 *
 * ## What the DSL surface manifest said, and why this test exists
 *
 * The manifest carried this construct as **PARTIAL**: carrier, codec, resolver and the fail-closed
 * refusal all existed and were proven, but the resolver's `grantedCapabilities` defaulted to the
 * empty set on the production composition, so **no production path could ever succeed**. A
 * construct that always refuses has not demonstrated the surface it advertises, and the row said so
 * rather than claiming STABLE.
 *
 * The promotion condition the row demanded was specific: compose the run's ACTUALLY-GRANTED
 * capability set generically, with a static table of "capabilities PipelineK can provide" explicitly
 * rejected as the substitute. The fix therefore does not invent a list — it hands the resolver the
 * keys of the `RuntimeCapabilityContributor` this coordinator already composes, the same authority
 * the execution boundary consults.
 *
 * ## Why the two rows must be read together
 *
 * The positive row alone could pass for the wrong reason (the resolver degenerating into "always
 * grant"). The negative row alone could pass because the construct still refuses everything, which
 * is exactly the PARTIAL state this removes. The pair differs in ONE input — the set the composition
 * contributes — and each asserts a different observable, so neither can be satisfied by the other's
 * defect.
 *
 * ## Fidelity and hermeticity
 *
 * HF1: both rows cross the PRODUCTION coordinator (`CanonicalDurableRunCoordinator.run`), not the
 * engine in isolation, because the defect being removed lived in the coordinator's construction site
 * and a test that built the engine itself would have missed it. `@TempDir` supplies the workspace and
 * the body marker; no network, no ambient state, no assertion on a duration or a size.
 */
@Timeout(60)
class AgentCapabilitiesTargetWitnessTest {

    private val declaredCapability = StepCapability("acme.widgets")

    private fun registry(vararg definitions: DirectiveDefinitionAny): DirectiveRegistry =
        DirectiveRegistry.Builder().addAll(definitions.toList()).build()

    private fun compiled(
        id: String,
        bodyMarker: Path,
        vararg directives: StageDirective,
    ): CompiledPipeline = CompiledPipeline(
        id = DefinitionId(id),
        source = SourceDescriptor("AgentCapabilitiesTargetWitness.pipeline.kts", Digest(id)),
        pluginLockDigest = Digest("$id-lock"),
        stages = listOf(
            StageNode(
                id = StageId("acct"),
                name = "acct",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("acct/sh"),
                            pluginStepId = PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo body-ran > '${bodyMarker}'",""" +
                                    """"isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
                directives = directives.toList(),
            ),
        ),
    )

    private class Wiring(val coordinator: CanonicalDurableRunCoordinator, val events: InMemoryEventStore)

    /**
     * The production coordinator, composed with the capabilities this run supplies.
     *
     * `granted` is the ONLY variable between the two rows: it is the set the composition
     * contributes, which is what the resolver must read.
     */
    private fun coordinator(granted: Set<StepCapability>, registry: DirectiveRegistry): Wiring {
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
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry,
            capabilityContributor = RuntimeCapabilityContributor {
                // The map VALUE is irrelevant to this law: what the resolver reads is which
                // capabilities the composition claims to supply, i.e. the KEYS.
                granted.associateWith { "witness-provider" }
            },
        )
        return Wiring(coordinator, events)
    }

    private fun run(wiring: Wiring, compiled: CompiledPipeline, runId: String): RunOutcome =
        runBlocking { wiring.coordinator.run(compiled, RunId(runId)) }

    private fun encodedCapabilityRequirement(): String =
        ExecutionTargetRequirementCodec.encode(
            ExecutionTargetRequirement.CapabilitySet(setOf(declaredCapability)),
        )

    @Test
    fun `a capability the run supplies is granted and the stage body runs`() {
        val wiring = coordinator(
            granted = setOf(declaredCapability),
            registry = registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())),
        )
        val marker = Files.createTempDirectory("acct-granted").resolve("body.txt")
        val runId = "acct-granted"
        val compiled = compiled(
            "acct-granted",
            marker,
            StageDirective("core.agent", encodedCapabilityRequirement()),
        )

        val outcome = run(wiring, compiled, runId)

        assertEquals(
            RunOutcome.Success,
            outcome,
            "a capability the composition supplies must be granted; outcome=$outcome",
        )
        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertTrue(
            stream.any { it is ExecutionTargetResolved },
            "the grant must be observable as ExecutionTargetResolved: " +
                stream.map { it::class.simpleName },
        )
        assertTrue(
            stream.any { it is StageStarted },
            "the stage must have started once its target was granted",
        )
        assertTrue(
            Files.exists(marker),
            "the body must have RUN: without this, the row would pass on a stage that resolved a " +
                "target and then did nothing",
        )
    }

    @Test
    fun `a capability the run does NOT supply is refused before any effect`() {
        val wiring = coordinator(
            granted = emptySet(),
            registry = registry(ErasedDirectiveDefinition(AgentDirectiveDefinition())),
        )
        val marker = Files.createTempDirectory("acct-refused").resolve("body.txt")
        val runId = "acct-refused"
        val compiled = compiled(
            "acct-refused",
            marker,
            StageDirective("core.agent", encodedCapabilityRequirement()),
        )

        val outcome = run(wiring, compiled, runId)

        assertTrue(
            outcome is RunOutcome.Failure,
            "a capability no composition supplies must fail the run closed; outcome=$outcome",
        )
        val stream = wiring.events.eventsFor(RunId(runId).value).toList()
        assertTrue(
            stream.none { it is ExecutionTargetResolved },
            "nothing may be granted: " + stream.map { it::class.simpleName },
        )
        assertTrue(
            stream.any { it is DirectiveDenied },
            "the refusal must be observable as DirectiveDenied, not a silent no-op",
        )
        assertTrue(
            stream.none { it is StageStarted },
            "the stage must never start, because the target was never granted",
        )
        assertTrue(Files.exists(marker.parent) && !Files.exists(marker), "the body must not have run")
    }
}
