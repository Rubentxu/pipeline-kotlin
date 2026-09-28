package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the step-level retry capability contract declared by [StepSpec].
 *
 * The capability used to live as a hand-maintained `when` over every subtype
 * inside `StageScope.retry`. Adding a new step meant remembering to edit that
 * list in a different file, and forgetting it produced a silent no-op: no
 * compile error, no test failure, and `retry { }` simply did nothing at
 * runtime. These tests exist so that a new subtype cannot be added without
 * making a deliberate statement about its retry capability.
 *
 * The hierarchy is read reflectively rather than by listing instances, so that
 * a subtype added tomorrow is covered by today's assertions.
 */
class StepSpecRetryCapabilityTest {

    /**
     * The exact set of retryable steps. This is the Jenkins top-step set; the
     * remaining steps use stage-level `options { retry(n) }` or a
     * `retry { }` block because the catalog has no per-step retry for them.
     */
    private val expectedRetryable = setOf(
        "Checkout",
        "Echo",
        "Error",
        "Parallel",
        "RegistryBlockSpec",
        "RegistryStepSpec",
        "Shell",
        "Sleep",
        "WithCredentialsBlock",
    )

    /**
     * Every direct `StepSpec` subtype, discovered from the sealed hierarchy.
     * Used to check that [allSubtypes] stays in step with the hierarchy.
     */
    private val subtypeNames: Set<String> = StepSpec::class.java.permittedSubclasses
        .map { it.simpleName }
        .toSet()

    @Test
    fun `every subtype of the sealed hierarchy is covered by an instance`() {
        val covered = allSubtypes().map { it.javaClass.simpleName }.toSet()
        val uncovered = subtypeNames.filterNot { it in covered }
        assertTrue(
            uncovered.isEmpty(),
            "these StepSpec subtypes have no instance in allSubtypes(): $uncovered. " +
                "Add one so the retry-capability assertions cover them",
        )
    }

    @Test
    fun `the retryable set is exactly the nine Jenkins top-steps`() {
        // Built from real instances rather than reflection. Reflection over a
        // Kotlin interface property default does not report the per-subtype
        // override — `Method.defaultValue` reflects the interface default, not
        // the subclass implementation, so it reads `false` for every subtype
        // including the ones that override it. Constructing the steps and
        // asking the instance is the only reading that reflects what the DSL
        // actually does.
        val actual = allSubtypes()
            .filter { it.supportsStepLevelRetry }
            .map { it.javaClass.simpleName }
            .toSet()

        assertEquals(
            expectedRetryable,
            actual,
            "the set of steps supporting step-level retry changed",
        )
    }

    @Test
    fun `the declared retryable set is covered by the sealed hierarchy`() {
        // Guards the test itself: if the hierarchy gains a subtype, the
        // expected set must be revisited rather than silently passing.
        val missing = expectedRetryable - allSubtypes().map { it.javaClass.simpleName }
        assertTrue(
            missing.isEmpty(),
            "expected retryable steps not constructed by this test: $missing",
        )
    }

    /** One instance of every step subtype, so the assertions see real values. */
    private fun allSubtypes(): List<StepSpec> = listOf(
        StepSpec.Echo("x"),
        StepSpec.Shell("x"),
        StepSpec.Error("x"),
        StepSpec.Sleep(1),
        StepSpec.Parallel(emptyList()),
        StepSpec.Checkout(dev.rubentxu.pipeline.v2.domain.scm.GitScm("https://example.invalid/r.git", "main")),
        StepSpec.RegistryStepSpec(
            stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("probe.step"),
            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}"),
        ),
        StepSpec.RegistryBlockSpec(
            stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("probe.block"),
            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}"),
            body = emptyList(),
        ),
        StepSpec.WithCredentialsBlock(
            credentialsId = dev.rubentxu.pipeline.v2.domain.CredentialsId("cred"),
            purpose = "probe",
            bindings = emptyList(),
            steps = emptyList(),
        ),
        StepSpec.IsUnix(),
        StepSpec.WriteFile("f", "t"),
        StepSpec.ReadFile("f"),
        StepSpec.FileExists("f"),
        StepSpec.WithEnv(listOf("A=B"), emptyList()),
        StepSpec.Dir("d", emptyList()),
        StepSpec.ArchiveArtifacts("logs/**"),
        StepSpec.ArtifactQuery("a"),
        StepSpec.DeleteDir(),
        StepSpec.CleanWs(),
        StepSpec.CatchError(),
        StepSpec.WarnError("w"),
        StepSpec.Unstable("u"),
        StepSpec.Pwd(),
        StepSpec.Load("l"),
        StepSpec.WaitUntilBlock(),
        StepSpec.Timestamps(emptyList()),
        StepSpec.AnsiColor(steps = emptyList()),
        StepSpec.NodeNoOp(steps = emptyList()),
        StepSpec.Milestone(1),
        StepSpec.TimeoutBlock(1, "SECONDS", null, emptyList()),
        StepSpec.RetryBlock(1, null, emptyList()),
    )

    @Test
    fun `a non-retryable step is left untouched by retry`() {
        val scope = StageScope("build")
        scope.writeFile("out.txt", "content")
        scope.retry(count = 3)

        val step = scope.steps().single()
        assertFalse(
            step.supportsStepLevelRetry,
            "WriteFile must not claim step-level retry support",
        )
        assertNull(
            step.retry,
            "a non-retryable step must keep retry unset rather than accept a dropped policy",
        )
    }

    @Test
    fun `a retryable step receives the requested policy`() {
        val scope = StageScope("build")
        scope.echo("hello")
        scope.retry(count = 4, delaySeconds = 2)

        val step = scope.steps().single()
        assertTrue(step.supportsStepLevelRetry, "Echo supports step-level retry")
        val policy = step.retry
        assertNotNull(policy, "retry(count) must attach a policy to Echo")
        assertEquals(4, policy!!.maxAttempts)
        assertEquals(2000L, policy.baseMs)
        assertEquals(1000L, policy.jitterMs, "jitter is 50% of the base delay")
    }

    @Test
    fun `retry before any step is a no-op`() {
        val scope = StageScope("build")
        scope.retry(count = 3)
        assertTrue(scope.steps().isEmpty(), "retry must not add a step of its own")
    }

    /**
     * The block form and the step form are different operations. `retry(n) { }`
     * wraps nested steps; `retry(n)` retrofits the previous step. Conflating
     * them would make a RetryBlock retryable, which is recursive.
     */
    @Test
    fun `the block form wraps steps and is not itself step-retryable`() {
        val scope = StageScope("build")
        scope.retry(count = 2) { echo("inside") }

        val block = scope.steps().single()
        assertTrue(
            block is StepSpec.RetryBlock,
            "expected a RetryBlock, got ${block::class.simpleName}",
        )
        assertFalse(
            block.supportsStepLevelRetry,
            "RetryBlock must not be step-level retryable",
        )
    }

    @Test
    fun `the wrapped block keeps its inner steps`() {
        val scope = StageScope("build")
        scope.retry(count = 2) { echo("inside") }

        val block = scope.steps().single() as StepSpec.RetryBlock
        assertEquals(2, block.count)
        assertEquals(1, block.steps.size, "the nested echo must survive wrapping")
    }
}
