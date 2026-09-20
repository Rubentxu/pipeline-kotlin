package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpecFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * WU-LPR-021 — Pure interpreter tests.
 *
 * The interpreter is `(BodyExecutionProjectionDescriptor) -> InterpretedBody`,
 * pure, total, no effects. Every projection case has exactly one
 * interpretation; the closed ADT guarantees exhaustiveness.
 *
 * These tests pin the interpretation contract. A future migration that
 * consumes the canonical coordinator's projection types and adapts them to
 * the descriptor family must preserve the output mapping verbatim —
 * otherwise `golden parity` would fail and the runner would emit a
 * different event/journal sequence than the canonical inline dispatch.
 */
class WULpr021BodyInterpreterTest {

    private val interpreter: BodyInterpreter = DefaultBodyInterpreter

    @Nested
    @DisplayName("Sequential")
    inner class SequentialProjection {
        @Test
        fun `Scope with None projection yields Sequential`() {
            val projection = BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.None,
            )
            val interpreted = interpreter.interpret(projection)
            assertEquals(InterpretedBody.Sequential, interpreted)
        }
    }

    @Nested
    @DisplayName("Scoped — Directory")
    inner class DirectoryScope {
        @Test
        fun `Directory scope produces Scoped with cwd patch and Cwd overlay`() {
            val target = Paths.get("/tmp/build")
            val previous = Paths.get("/tmp")
            val projection = BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.Directory(target = target, previous = previous),
            )
            val interpreted = interpreter.interpret(projection)
            assertTrue(interpreted is InterpretedBody.Scoped)
            val scoped = interpreted as InterpretedBody.Scoped
            assertEquals(target, scoped.childShPatch.workingDirectory)
            assertEquals(ShPatch(workingDirectory = target), scoped.childShPatch)
            assertTrue(scoped.contextOverlay is ContextOverlayDescriptor.Cwd)
            assertEquals(target.toString(), (scoped.contextOverlay as ContextOverlayDescriptor.Cwd).path)
            assertTrue(scoped.scope is BlockShellScopeDescriptor.Directory)
        }

        @Test
        fun `Directory scope patch is non-identity on workingDirectory only`() {
            val target = Paths.get("/var/build")
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.Directory(target = target, previous = Paths.get("/var")),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(null, interpreted.childShPatch.timeoutMs)
            assertEquals(null, interpreted.childShPatch.env)
        }
    }

    @Nested
    @DisplayName("Scoped — Env")
    inner class EnvScope {
        @Test
        fun `Env scope parses KEY=VALUE pairs and merges parent env`() {
            val projection = BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.Env(
                    overrides = listOf("FOO=bar", "BAZ=qux"),
                    parentEnv = mapOf("PREEXISTING" to "kept"),
                ),
            )
            val interpreted = interpreter.interpret(projection) as InterpretedBody.Scoped
            assertEquals(
                mapOf("PREEXISTING" to "kept", "FOO" to "bar", "BAZ" to "qux"),
                interpreted.childShPatch.env,
            )
            assertTrue(interpreted.contextOverlay is ContextOverlayDescriptor.Environment)
            val envOverlay = interpreted.contextOverlay as ContextOverlayDescriptor.Environment
            assertEquals(
                mapOf("PREEXISTING" to "kept", "FOO" to "bar", "BAZ" to "qux"),
                envOverlay.values,
            )
        }

        @Test
        fun `Env scope with malformed override (no equals) drops the entry`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.Env(
                        overrides = listOf("BAD_NO_EQUALS", "OK=value"),
                        parentEnv = emptyMap(),
                    ),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(mapOf("OK" to "value"), interpreted.childShPatch.env)
        }
    }

    @Nested
    @DisplayName("Scoped — Timeout")
    inner class TimeoutScope {
        @Test
        fun `Timeout scope produces Scoped with timeoutMs patch and Deadline overlay`() {
            val budgetMs = 30_000L
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.Timeout(budgetMs = budgetMs),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(budgetMs, interpreted.childShPatch.timeoutMs)
            assertEquals(null, interpreted.childShPatch.workingDirectory)
            assertEquals(null, interpreted.childShPatch.env)
            assertTrue(interpreted.contextOverlay is ContextOverlayDescriptor.Deadline)
            assertEquals(budgetMs, (interpreted.contextOverlay as ContextOverlayDescriptor.Deadline).timeoutMs)
        }
    }

    @Nested
    @DisplayName("Scoped — Retry / WaitUntil / Timestamps")
    inner class LoopingScopes {
        @Test
        fun `Retry scope produces Scoped with identity ShPatch and no overlay`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.Retry(maxAttempts = 3),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(ShPatch.NONE, interpreted.childShPatch)
            assertEquals(ContextOverlayDescriptor.None, interpreted.contextOverlay)
        }

        @Test
        fun `WaitUntil scope produces Scoped with identity ShPatch`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.WaitUntil(
                        initialRecurrencePeriodMs = 1_000L,
                        quiet = true,
                    ),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(ShPatch.NONE, interpreted.childShPatch)
        }

        @Test
        fun `Timestamps scope produces Scoped with identity ShPatch`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Scope(
                    BlockShellScopeDescriptor.Timestamps(runId = "r-1"),
                ),
            ) as InterpretedBody.Scoped
            assertEquals(ShPatch.NONE, interpreted.childShPatch)
        }
    }

    @Nested
    @DisplayName("Credential lease")
    inner class CredentialLeaseProjection {
        @Test
        fun `CredentialLease projection yields CredentialLeased carrying bindings verbatim`() {
            val bindings = listOf(
                CredentialBindingSpecFactory.usernamePassword(
                    credentialsId = dev.rubentxu.pipeline.v2.domain.CredentialsId("creds-1"),
                    usernameVariable = "USER_VAR",
                    passwordVariable = "PW_VAR",
                ),
            )
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.CredentialLease(bindings = bindings),
            )
            assertTrue(interpreted is InterpretedBody.CredentialLeased)
            assertEquals(bindings, (interpreted as InterpretedBody.CredentialLeased).bindings)
        }
    }

    @Nested
    @DisplayName("Typed failures")
    inner class TypedFailures {
        @Test
        fun `InvalidInput projection yields InvalidInput with detail verbatim`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.InvalidInput(detail = "path malformed: /bad"),
            )
            assertTrue(interpreted is InterpretedBody.InvalidInput)
            assertEquals("path malformed: /bad", (interpreted as InterpretedBody.InvalidInput).detail)
        }

        @Test
        fun `Unimplemented projection yields Unimplemented with shape verbatim`() {
            val interpreted = interpreter.interpret(
                BodyExecutionProjectionDescriptor.Unimplemented(
                    shape = BodyExecutionPolicyShape.PARALLEL,
                ),
            )
            assertTrue(interpreted is InterpretedBody.Unimplemented)
            assertEquals(
                BodyExecutionPolicyShape.PARALLEL,
                (interpreted as InterpretedBody.Unimplemented).shape,
            )
        }
    }

    @Nested
    @DisplayName("ShPatch identity behaviour")
    inner class ShPatchIdentity {
        @Test
        fun `ShPatch NONE has every field null`() {
            assertEquals(null, ShPatch.NONE.timeoutMs)
            assertEquals(null, ShPatch.NONE.workingDirectory)
            assertEquals(null, ShPatch.NONE.env)
        }
    }

    @Test
    fun `interpreter is total over the closed projection ADT`() {
        // Pin: every variant produces a typed InterpretedBody, never throws.
        val projections: List<BodyExecutionProjectionDescriptor> = listOf(
            BodyExecutionProjectionDescriptor.Scope(BlockShellScopeDescriptor.None),
            BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.Directory(Paths.get("/a"), Paths.get("/")),
            ),
            BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.Env(emptyList(), emptyMap()),
            ),
            BodyExecutionProjectionDescriptor.Scope(BlockShellScopeDescriptor.Timeout(1000L)),
            BodyExecutionProjectionDescriptor.Scope(BlockShellScopeDescriptor.Retry(3)),
            BodyExecutionProjectionDescriptor.Scope(
                BlockShellScopeDescriptor.WaitUntil(500L, false),
            ),
            BodyExecutionProjectionDescriptor.Scope(BlockShellScopeDescriptor.Timestamps("r")),
            BodyExecutionProjectionDescriptor.CredentialLease(emptyList()),
            BodyExecutionProjectionDescriptor.InvalidInput("x"),
            BodyExecutionProjectionDescriptor.Unimplemented(BodyExecutionPolicyShape.PARALLEL),
        )
        projections.forEach { p ->
            val result = interpreter.interpret(p)
            assertNotNull(result, "interpreter MUST return a non-null result for $p")
        }
    }
}
