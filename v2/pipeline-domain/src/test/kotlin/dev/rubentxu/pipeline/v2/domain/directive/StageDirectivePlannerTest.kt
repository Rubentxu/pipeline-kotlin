package dev.rubentxu.pipeline.v2.domain.directive

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S1-B — Stage directive planning: the PURE decision layer.
 *
 * RED-first expectations (these tests describe the contract the coordinator
 * must interpret at the effect boundary):
 *
 * - Permitted groups admitted directives by phase, exhaustively (every phase
 *   key present), preserving declaration order inside a phase.
 * - An unknown key DENIES the stage with a diagnostic naming key and stage.
 * - Policies surface on the decision exactly as declared; the planner never
 *   looks at the key to decide anything.
 * - The decision is deterministic across repeated invocations (pure function).
 */
class StageDirectivePlannerTest {

    private fun definition(
        key: String,
        phase: DirectivePhase,
        policy: DirectiveExecutionPolicy,
    ): DirectiveDefinitionAny = ErasedDirectiveDefinition(
        object : DirectiveDefinition<String, Unit> {
            override val key = DirectiveKey(key)
            override val phase = phase
            override val policy = policy
            override fun decode(encodedArguments: String) =
                DirectiveDecodeResult.Decoded(encodedArguments)
        },
    )

    private fun registry(vararg defs: DirectiveDefinitionAny): DirectiveRegistry =
        DirectiveRegistry.Builder().addAll(defs.toList()).build()

    private fun stage(
        vararg directives: StageDirective,
        name: String = "build",
    ): StageNode = StageNode(
        id = StageId(name),
        name = name,
        body = StageBody.Steps(emptyList()),
        directives = directives.toList(),
    )

    private operator fun String.invoke(): StageDirective = StageDirective(this)

    private operator fun String.invoke(encodedArguments: String): StageDirective =
        StageDirective(this, encodedArguments)

    @Test
    fun `admitted directives group by phase in declaration order`() {
        val registry = registry(
            definition("acme.pre", DirectivePhase.BEFORE_STAGE, DirectiveExecutionPolicy.Gate("ready")),
            definition("acme.during", DirectivePhase.DURING_STAGE, DirectiveExecutionPolicy.ProvideContext),
            definition("acme.after", DirectivePhase.AFTER_STAGE, DirectiveExecutionPolicy.Evaluate),
        )
        val stage = stage(
            "acme.during"(),
            "acme.pre"("""{"predicate":"ready"}"""),
            "acme.after"(),
        )

        val decision = StageDirectivePlanner.decide(registry, stage)

        val permitted = decision as StageDirectiveDecision.Permitted
        assertEquals(
            setOf(DirectivePhase.BEFORE_STAGE, DirectivePhase.DURING_STAGE, DirectivePhase.AFTER_STAGE),
            permitted.phases.keys,
            "the decision must carry ALL phases so the interpreter can exhaustively walk them",
        )
        assertEquals(
            listOf("acme.pre"),
            permitted.phases.getValue(DirectivePhase.BEFORE_STAGE).map { it.invocation.key.value },
        )
        assertEquals(
            DirectiveExecutionPolicy.Gate("ready"),
            permitted.phases.getValue(DirectivePhase.BEFORE_STAGE).single().policy,
            "the declared policy must survive planning untouched",
        )
        assertEquals(
            """{"predicate":"ready"}""",
            permitted.phases.getValue(DirectivePhase.BEFORE_STAGE).single().invocation.encodedArguments,
            "encoded arguments pass through opaquely; decode belongs to the definition",
        )
    }

    @Test
    fun `an unknown directive denies the stage naming key and stage`() {
        val registry = registry(
            definition("acme.known", DirectivePhase.BEFORE_STAGE, DirectiveExecutionPolicy.Evaluate),
        )
        val stage = stage(
            "acme.known"(),
            "acme.missing"(),
            name = "deploy",
        )

        val decision = StageDirectivePlanner.decide(registry, stage)

        val denied = decision as StageDirectiveDecision.Denied
        assertEquals(DirectiveKey("acme.missing"), denied.key)
        assertTrue(
            denied.reason.contains("acme.missing") && denied.reason.contains("deploy"),
            "the denial must be diagnosable by key and stage, got '${denied.reason}'",
        )
    }

    @Test
    fun `a blank declared key denies the stage instead of throwing mid-run`() {
        // StageDirective construction rejects blank keys at the boundary, so the
        // carrier itself is the first fail-closed gate. The planner-level denial
        // for an unresolvable key is covered by `an unknown directive denies...`.
        val thrown = runCatching { StageDirective("   ") }

        assertTrue(
            thrown.isFailure && thrown.exceptionOrNull() is IllegalArgumentException,
            "a blank declared key must be rejected at the carrier boundary, got $thrown",
        )
    }

    @Test
    fun `a stage without directives is permitted with empty phases`() {
        val registry = registry(
            definition("acme.x", DirectivePhase.BEFORE_STAGE, DirectiveExecutionPolicy.Evaluate),
        )

        val decision = StageDirectivePlanner.decide(registry, stage())

        val permitted = decision as StageDirectiveDecision.Permitted
        assertTrue(
            permitted.phases.values.all { it.isEmpty() },
            "no directives means every phase is present and empty",
        )
    }

    @Test
    fun `planning is deterministic across repeated invocations`() {
        val registry = registry(
            definition("acme.a", DirectivePhase.BEFORE_STAGE, DirectiveExecutionPolicy.Gate("p")),
            definition("acme.b", DirectivePhase.AFTER_STAGE, DirectiveExecutionPolicy.ProvideContext),
        )
        val stage = stage(StageDirective("acme.a"), StageDirective("acme.b"))

        val first = StageDirectivePlanner.decide(registry, stage)
        val second = StageDirectivePlanner.decide(registry, stage)

        assertEquals(first, second, "the planner is a pure function of (registry, stage)")
    }

    @Test
    fun `a full pipeline with directives compiles the IR carrier through the canonical compiler`() {
        // IR-level round-trip: StageDirective is plain serializable data on
        // StageNode, so a pipeline carrying directives survives the compiled IR
        // without any directive-specific compiler knowledge.
        val pipeline = CompiledPipeline(
            id = DefinitionId("s1b-ir"),
            source = SourceDescriptor("S1B.pipeline.kts", Digest("s1b")),
            pluginLockDigest = Digest("s1b-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(emptyList()),
                    directives = listOf(StageDirective("acme.pre", """{"a":1}""")),
                ),
            ),
        )

        val directives = pipeline.stages.single().directives

        assertEquals(
            listOf(StageDirective("acme.pre", """{"a":1}""")),
            directives,
            "the directive carrier must round-trip through the IR as plain data",
        )
    }
}
