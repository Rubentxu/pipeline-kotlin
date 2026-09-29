package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * S1.1 — Directive Kernel contract.
 *
 * These tests assert the two halves of the S1 exit criterion, and they assert
 * them as PROPERTIES rather than as "it did not crash":
 *
 * - OPEN BY KEY: a directive contributed from outside the kernel resolves with
 *   zero engine changes, and an unknown key is rejected rather than ignored.
 * - CLOSED BY STRUCTURE: behaviour is carried by the closed
 *   [DirectiveExecutionPolicy] hierarchy, so a new directive is a new
 *   definition, never a new engine case.
 *
 * The negative rows matter more than the positive ones. A kernel that silently
 * ignores an unresolved key is worse than no kernel, because it turns an
 * unknown name into a no-op and reports success.
 */
class DirectiveKernelTest {

    private fun definition(
        key: String,
        phase: DirectivePhase = DirectivePhase.BEFORE_STAGE,
        policy: DirectiveExecutionPolicy = DirectiveExecutionPolicy.Evaluate,
    ): DirectiveDefinitionAny = ErasedDirectiveDefinition(
        object : DirectiveDefinition<String, String> {
            override val key = DirectiveKey(key)
            override val phase = phase
            override val policy = policy
            override fun decode(encodedArguments: String) =
                DirectiveDecodeResult.Decoded(encodedArguments)
        },
    )

    // --- OPEN BY KEY -------------------------------------------------------

    @Test
    fun `a directive contributed from outside the kernel resolves by key`() {
        val registry = DirectiveRegistry.Builder()
            .addContributors(
                object : DirectiveContributor {
                    override fun definitions() = listOf(
                        definition("acme.guard", policy = DirectiveExecutionPolicy.Gate("ready")),
                        definition("acme.context", policy = DirectiveExecutionPolicy.ProvideContext),
                    )
                },
            )
            .build()

        val gate = registry.admit(DirectiveInvocation(DirectiveKey("acme.guard"), "{}"))
        assertTrue(
            gate is DirectiveAdmission.Admitted && gate.policy is DirectiveExecutionPolicy.Gate,
            "an externally contributed directive must resolve and carry its own policy, got $gate",
        )

        val context = registry.admit(DirectiveInvocation(DirectiveKey("acme.context"), "{}"))
        assertTrue(
            context is DirectiveAdmission.Admitted &&
                context.policy is DirectiveExecutionPolicy.ProvideContext,
            "distinct policies must survive registration, got $context",
        )
    }

    @Test
    fun `an unknown key is rejected, never silently skipped`() {
        val registry = DirectiveRegistry.Builder()
            .add(definition("acme.known"))
            .build()

        val admission = registry.admit(DirectiveInvocation(DirectiveKey("acme.unknown"), "{}"))

        assertTrue(
            admission is DirectiveAdmission.Rejected,
            "an unresolved directive must be rejected, not treated as a no-op, got $admission",
        )
        assertTrue(
            (admission as DirectiveAdmission.Rejected).reason.contains("acme.unknown"),
            "the rejection must name the offending key, got '${admission.reason}'",
        )
    }

    @Test
    fun `duplicate keys fail closed instead of shadowing`() {
        val builder = DirectiveRegistry.Builder().add(definition("acme.dup"))

        val error = assertThrows<DirectiveRegistry.DuplicateKeyException> {
            builder.add(definition("acme.dup", policy = DirectiveExecutionPolicy.Gate("other")))
        }

        assertTrue(
            error.message!!.contains("acme.dup"),
            "the collision must be diagnosable by key, got '${error.message}'",
        )
    }

    @Test
    fun `duplicate keys are rejected even when they arrive through different contributors`() {
        val first = object : DirectiveContributor {
            override fun definitions() = listOf(definition("acme.shared"))
        }
        val second = object : DirectiveContributor {
            override fun definitions() = listOf(definition("acme.shared"))
        }

        assertThrows<DirectiveRegistry.DuplicateKeyException> {
            DirectiveRegistry.Builder().addContributors(first, second).build()
        }
    }

    // --- CLOSED BY STRUCTURE ----------------------------------------------

    @Test
    fun `the policy hierarchy is the closed structural contract`() {
        // A new directive reuses an existing case. It never widens the ADT, so
        // the engine's `when` stays exhaustive and needs no new branch.
        val policies: List<DirectiveExecutionPolicy> = listOf(
            DirectiveExecutionPolicy.Evaluate,
            DirectiveExecutionPolicy.Gate("predicate"),
            DirectiveExecutionPolicy.ProvideContext,
        )

        val described = policies.map { policy ->
            when (policy) {
                is DirectiveExecutionPolicy.Evaluate -> "evaluate"
                is DirectiveExecutionPolicy.Gate -> "gate:${policy.predicate}"
                is DirectiveExecutionPolicy.ProvideContext -> "context"
            }
        }

        assertEquals(
            listOf("evaluate", "gate:predicate", "context"),
            described,
            "every policy case must be interpretable without inspecting the directive key",
        )
    }

    @Test
    fun `admission exposes policy only, never the definition identity`() {
        val registry = DirectiveRegistry.Builder()
            .add(definition("acme.gate", policy = DirectiveExecutionPolicy.Gate("ready")))
            .build()

        val admission = registry.admit(DirectiveInvocation(DirectiveKey("acme.gate"), "{}"))

        assertEquals(
            DirectiveExecutionPolicy.Gate("ready"),
            (admission as DirectiveAdmission.Admitted).policy,
            "admission must yield the declared policy, so the engine interprets data rather than a name",
        )
    }

    // --- TYPED DECODE ------------------------------------------------------

    @Test
    fun `a definition that cannot read its own arguments reports Malformed, not an exception`() {
        val strict = object : DirectiveDefinition<Int, Unit> {
            override val key = DirectiveKey("acme.strict")
            override val phase = DirectivePhase.DURING_STAGE
            override val policy = DirectiveExecutionPolicy.Evaluate
            override fun decode(encodedArguments: String): DirectiveDecodeResult<Int> =
                encodedArguments.toIntOrNull()
                    ?.let { DirectiveDecodeResult.Decoded(it) }
                    ?: DirectiveDecodeResult.Malformed("not an int: '$encodedArguments'")
        }

        val ok = strict.decode("42")
        val bad = strict.decode("nope")

        assertEquals(DirectiveDecodeResult.Decoded(42), ok)
        assertTrue(
            bad is DirectiveDecodeResult.Malformed,
            "a malformed payload is a value, not a thrown control-flow exception, got $bad",
        )
    }

    @Test
    fun `a blank key is rejected at construction`() {
        assertThrows<IllegalArgumentException> { DirectiveKey("   ") }
    }
}
