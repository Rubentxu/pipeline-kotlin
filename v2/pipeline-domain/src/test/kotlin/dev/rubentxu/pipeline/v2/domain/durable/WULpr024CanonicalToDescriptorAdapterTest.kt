package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpecFactory
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicyShape
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionProjectionDescriptor
import dev.rubentxu.pipeline.v2.domain.step.BlockShellScopeDescriptor
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * WU-LPR-024 (slice 1/3) — Adapter tests.
 *
 * The adapter maps canonical coordinator types to the descriptor family.
 * The contract is structural 1:1: every canonical case has exactly one
 * descriptor case, and every descriptor case is reachable. Golden parity
 * is enforced by pinning the field-by-field translation; a future
 * migration that changes a field name MUST update the adapter and these
 * tests together.
 */
class WULpr024CanonicalToDescriptorAdapterTest {

    @Nested
    @DisplayName("BlockShellScope adaptation")
    inner class ScopeCases {

        @Test
        fun `None maps to descriptor None`() {
            assertEquals(
                BlockShellScopeDescriptor.None,
                CanonicalToDescriptorAdapter.adaptScope(CanonicalBlockShellScope.None),
            )
        }

        @Test
        fun `Directory maps verbatim (target + previous preserved)`() {
            val target = Paths.get("/tmp/build")
            val previous = Paths.get("/tmp")
            assertEquals(
                BlockShellScopeDescriptor.Directory(target = target, previous = previous),
                CanonicalToDescriptorAdapter.adaptScope(
                    CanonicalBlockShellScope.Directory(target = target, previous = previous),
                ),
            )
        }

        @Test
        fun `TimestampsScope maps verbatim`() {
            assertEquals(
                BlockShellScopeDescriptor.Timestamps(runId = "r-1"),
                CanonicalToDescriptorAdapter.adaptScope(
                    CanonicalBlockShellScope.TimestampsScope(runId = "r-1"),
                ),
            )
        }

        @Test
        fun `EnvScope maps overrides verbatim and borrows parentEnv to strings`() {
            val parentHandle = CanonicalPlainSecretHandle("kept".toByteArray())
            val adapted = CanonicalToDescriptorAdapter.adaptScope(
                CanonicalBlockShellScope.EnvScope(
                    overrides = listOf("FOO=bar", "BAZ=qux"),
                    parentEnv = mapOf("PREEXISTING" to parentHandle),
                ),
            )
            assertTrue(adapted is BlockShellScopeDescriptor.Env)
            val env = adapted as BlockShellScopeDescriptor.Env
            assertEquals(listOf("FOO=bar", "BAZ=qux"), env.overrides)
            assertEquals("kept", env.parentEnv["PREEXISTING"])
        }

        @Test
        fun `Timeout maps verbatim (budgetMs preserved)`() {
            assertEquals(
                BlockShellScopeDescriptor.Timeout(budgetMs = 30_000L),
                CanonicalToDescriptorAdapter.adaptScope(
                    CanonicalBlockShellScope.Timeout(budgetMs = 30_000L),
                ),
            )
        }

        @Test
        fun `Retry maps verbatim (maxAttempts preserved)`() {
            assertEquals(
                BlockShellScopeDescriptor.Retry(maxAttempts = 3),
                CanonicalToDescriptorAdapter.adaptScope(
                    CanonicalBlockShellScope.Retry(maxAttempts = 3),
                ),
            )
        }

        @Test
        fun `WaitUntilScope maps initialRecurrencePeriod to initialRecurrencePeriodMs and preserves quiet`() {
            val adapted = CanonicalToDescriptorAdapter.adaptScope(
                CanonicalBlockShellScope.WaitUntilScope(
                    initialRecurrencePeriod = 1_500L,
                    quiet = true,
                    maxBackoffMs = 120_000L,
                ),
            )
            assertTrue(adapted is BlockShellScopeDescriptor.WaitUntil)
            val wait = adapted as BlockShellScopeDescriptor.WaitUntil
            assertEquals(1_500L, wait.initialRecurrencePeriodMs)
            assertEquals(true, wait.quiet)
        }
    }

    @Nested
    @DisplayName("BodyExecutionProjection adaptation")
    inner class ProjectionCases {

        @Test
        fun `Scope(None) maps to Scope(descriptor None)`() {
            val canonical = CanonicalBodyExecutionProjection.Scope(
                CanonicalBlockShellScope.None,
            )
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(canonical)
            assertTrue(adapted is BodyExecutionProjectionDescriptor.Scope)
            assertEquals(
                BlockShellScopeDescriptor.None,
                (adapted as BodyExecutionProjectionDescriptor.Scope).scope,
            )
        }

        @Test
        fun `Scope(Directory) maps verbatim`() {
            val target = Paths.get("/tmp/build")
            val canonical = CanonicalBodyExecutionProjection.Scope(
                CanonicalBlockShellScope.Directory(target = target, previous = Paths.get("/tmp")),
            )
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(canonical)
            assertTrue(adapted is BodyExecutionProjectionDescriptor.Scope)
            assertEquals(
                BlockShellScopeDescriptor.Directory(target = target, previous = Paths.get("/tmp")),
                (adapted as BodyExecutionProjectionDescriptor.Scope).scope,
            )
        }

        @Test
        fun `CredentialLease maps bindings verbatim`() {
            val binding = CredentialBindingSpecFactory.usernamePassword(
                credentialsId = CredentialsId("creds-1"),
                usernameVariable = "U",
                passwordVariable = "P",
            )
            val canonical = CanonicalBodyExecutionProjection.CredentialLease(bindings = listOf(binding))
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(canonical)
            assertTrue(adapted is BodyExecutionProjectionDescriptor.CredentialLease)
            assertEquals(
                listOf(binding),
                (adapted as BodyExecutionProjectionDescriptor.CredentialLease).bindings,
            )
        }

        @Test
        fun `InvalidInput maps detail verbatim`() {
            val canonical = CanonicalBodyExecutionProjection.InvalidInput(detail = "bad path")
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(canonical)
            assertTrue(adapted is BodyExecutionProjectionDescriptor.InvalidInput)
            assertEquals(
                "bad path",
                (adapted as BodyExecutionProjectionDescriptor.InvalidInput).detail,
            )
        }

        @Test
        fun `Unimplemented maps shape verbatim`() {
            val canonical = CanonicalBodyExecutionProjection.Unimplemented(
                shape = BodyExecutionPolicyShape.PARALLEL,
            )
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(canonical)
            assertTrue(adapted is BodyExecutionProjectionDescriptor.Unimplemented)
            assertEquals(
                BodyExecutionPolicyShape.PARALLEL,
                (adapted as BodyExecutionProjectionDescriptor.Unimplemented).shape,
            )
        }
    }

    @Nested
    @DisplayName("Golden parity — descriptor interpreter consumes adapter output")
    inner class GoldenParity {

        @Test
        fun `Scope(Directory) adapter output produces Sequential+Scoped via interpreter`() {
            // Pin: the adapter output, fed to the interpreter, yields the same
            // interpreted body the canonical inline dispatch would (modulo the
            // Scoped vs Sequential split). This is the WU-LPR-021 golden path:
            // Scope(None) -> Sequential, Scope(Directory) -> Scoped.
            val interpreter = dev.rubentxu.pipeline.v2.domain.step.DefaultBodyInterpreter
            val target = Paths.get("/tmp/build")
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(
                CanonicalBodyExecutionProjection.Scope(
                    CanonicalBlockShellScope.Directory(target = target, previous = Paths.get("/tmp")),
                ),
            )
            val interpreted = interpreter.interpret(adapted)
            assertTrue(
                interpreted is dev.rubentxu.pipeline.v2.domain.step.InterpretedBody.Scoped,
                "Scope(Directory) MUST produce InterpretedBody.Scoped; got $interpreted",
            )
        }

        @Test
        fun `Scope(None) adapter output produces Sequential via interpreter`() {
            val interpreter = dev.rubentxu.pipeline.v2.domain.step.DefaultBodyInterpreter
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(
                CanonicalBodyExecutionProjection.Scope(CanonicalBlockShellScope.None),
            )
            val interpreted = interpreter.interpret(adapted)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.step.InterpretedBody.Sequential,
                interpreted,
            )
        }

        @Test
        fun `Timeout adapter output produces Scoped with timeoutMs patch via interpreter`() {
            val interpreter = dev.rubentxu.pipeline.v2.domain.step.DefaultBodyInterpreter
            val adapted = CanonicalToDescriptorAdapter.adaptProjection(
                CanonicalBodyExecutionProjection.Scope(
                    CanonicalBlockShellScope.Timeout(budgetMs = 30_000L),
                ),
            )
            val interpreted = interpreter.interpret(adapted)
            assertTrue(
                interpreted is dev.rubentxu.pipeline.v2.domain.step.InterpretedBody.Scoped,
            )
            val scoped = interpreted as dev.rubentxu.pipeline.v2.domain.step.InterpretedBody.Scoped
            assertEquals(30_000L, scoped.childShPatch.timeoutMs)
        }
    }
}
