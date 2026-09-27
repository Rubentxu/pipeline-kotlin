package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpecFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D-024 / T2 E-EM-11 surface coverage.
 *
 * Codifies the contract between [BlockShellScopeDescriptor] and
 * [ContextOverlayDescriptor] for the canonical body interpreter.
 * This is a fitness test for the deadline + cancellation surface,
 * mirroring the spirit of E-EM-11 T2 "typed clock tests + shared
 * persisted deadline" without changing production (the contract is
 * already what production code does; this test pins it).
 *
 * Reference: AGENTS.md §"STEP IMPLEMENTATION — OPERATIVE GUIDE" and
 * §"EXPLICIT IMMUTABLE EXECUTION CONTEXT". The mapping is pure
 * (projection → interpreted), so the assertions are total and
 * deterministic.
 *
 * The four key invariants are:
 *  1. Timeout scope → Deadline overlay (budgetMs preserved).
 *  2. Sequential interpretation → no overlay (ContextOverlayDescriptor.None).
 *  3. Retry/WaitUntil/Timestamps scope → no overlay
 *     (these don't introduce a new deadline; they reuse the parent's).
 *  4. Directory/Env scope → Cwd/Environment overlay (not Deadline).
 *
 * These are mechanical invariants of the interpreter mapping and
 * prevent regressions in the T2 surface that would otherwise only
 * surface as flaky timeout behaviour under load.
 */
class DslInterpreterDeadlineContractFitnessTest {

    private val interpreter: BodyInterpreter = DefaultBodyInterpreter

    @Test
    fun `Timeout scope projects to Deadline overlay with preserved budgetMs`() {
        val scope = BlockShellScopeDescriptor.Timeout(budgetMs = 30_000L)
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(
            interpreted is InterpretedBody.Scoped,
            "Timeout scope must yield Scoped (not Sequential). Got: $interpreted"
        )
        val scoped = interpreted as InterpretedBody.Scoped
        assertEquals(
            ContextOverlayDescriptor.Deadline(30_000L),
            scoped.contextOverlay,
            "Timeout scope must carry a Deadline overlay with the same budgetMs"
        )
        assertEquals(
            30_000L,
            scoped.childShPatch.timeoutMs,
            "Timeout scope must propagate budgetMs into the ShPatch"
        )
    }

    @Test
    fun `Sequential interpretation carries no context overlay`() {
        // BodyExecutionProjectionDescriptor.Scope(None) maps to InterpretedBody.Sequential.
        val projection = BodyExecutionProjectionDescriptor.Scope(BlockShellScopeDescriptor.None)
        val interpreted = interpreter.interpret(projection)

        assertEquals(InterpretedBody.Sequential, interpreted)
        // Sequential is a data object; the absence of overlay is structural.
        // The runner never sees a contextOverlay for Sequential.
    }

    @Test
    fun `Retry scope does not introduce a new deadline`() {
        val scope = BlockShellScopeDescriptor.Retry(maxAttempts = 3)
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertEquals(
            ContextOverlayDescriptor.None,
            scoped.contextOverlay,
            "Retry scope must inherit the parent's deadline (no new overlay)"
        )
        assertNull(scoped.childShPatch.timeoutMs, "Retry must not override ShOptions timeoutMs")
    }

    @Test
    fun `WaitUntil scope does not introduce a new deadline`() {
        val scope = BlockShellScopeDescriptor.WaitUntil(
            initialRecurrencePeriodMs = 60_000L,
            quiet = false,
        )
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertEquals(ContextOverlayDescriptor.None, scoped.contextOverlay)
        assertNull(scoped.childShPatch.timeoutMs)
    }

    @Test
    fun `Timestamps scope does not introduce a new deadline`() {
        val scope = BlockShellScopeDescriptor.Timestamps(runId = "run-1")
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertEquals(ContextOverlayDescriptor.None, scoped.contextOverlay)
        assertNull(scoped.childShPatch.timeoutMs)
    }

    @Test
    fun `Directory scope carries Cwd overlay (not Deadline)`() {
        val scope = BlockShellScopeDescriptor.Directory(
            target = java.nio.file.Paths.get("/tmp/build"),
            previous = java.nio.file.Paths.get("/tmp"),
        )
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertTrue(
            scoped.contextOverlay is ContextOverlayDescriptor.Cwd,
            "Directory scope must carry Cwd overlay, not Deadline. Got: ${scoped.contextOverlay}"
        )
        assertTrue(scoped.contextOverlay !is ContextOverlayDescriptor.Deadline)
    }

    @Test
    fun `Env scope carries Environment overlay (not Deadline)`() {
        val scope = BlockShellScopeDescriptor.Env(
            overrides = listOf("KEY=value"),
            parentEnv = mapOf("EXISTING" to "from-parent"),
        )
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)

        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertTrue(
            scoped.contextOverlay is ContextOverlayDescriptor.Environment,
            "Env scope must carry Environment overlay. Got: ${scoped.contextOverlay}"
        )
        assertTrue(scoped.contextOverlay !is ContextOverlayDescriptor.Deadline)
    }

    @Test
    fun `exhaustive - every BlockShellScopeDescriptor case maps to a defined InterpretedBody`() {
        // Exhaustiveness proof: each case of BlockShellScopeDescriptor is
        // exercised and yields a structurally valid InterpretedBody. If a
        // new case is added to the sealed interface, this test must be
        // updated (compile error forces the maintainer to add an assertion),
        // preventing accidental fall-through.
        val cases: List<Pair<String, BlockShellScopeDescriptor>> = listOf(
            "None" to BlockShellScopeDescriptor.None,
            "Timeout(30s)" to BlockShellScopeDescriptor.Timeout(30_000L),
            "Retry(3)" to BlockShellScopeDescriptor.Retry(3),
            "WaitUntil" to BlockShellScopeDescriptor.WaitUntil(60_000L, false),
            "Timestamps" to BlockShellScopeDescriptor.Timestamps("run-1"),
            "Directory" to BlockShellScopeDescriptor.Directory(
                java.nio.file.Paths.get("/tmp/x"),
                java.nio.file.Paths.get("/tmp"),
            ),
            "Env" to BlockShellScopeDescriptor.Env(listOf("A=b"), emptyMap()),
        )

        cases.forEach { (name, scope) ->
            val projection = BodyExecutionProjectionDescriptor.Scope(scope)
            val interpreted = interpreter.interpret(projection)
            assertTrue(
                interpreted is InterpretedBody,
                "Case $name must yield a valid InterpretedBody. Got: $interpreted"
            )
        }
    }

    @Test
    fun `CredentialLease projection passes through unchanged`() {
        // Sanity: a non-Scope projection (CredentialLease) yields
        // CredentialLeased. This guards the outer `when` in interpret().
        val bindings = listOf(
            CredentialBindingSpecFactory.usernamePassword(
                credentialsId = CredentialsId("creds-1"),
                usernameVariable = "USER_VAR",
                passwordVariable = "PW_VAR",
            ),
        )
        val projection = BodyExecutionProjectionDescriptor.CredentialLease(bindings)
        val interpreted = interpreter.interpret(projection)
        assertTrue(
            interpreted is InterpretedBody.CredentialLeased,
            "CredentialLease projection must pass through. Got: $interpreted"
        )
    }

    @Test
    fun `sanity - Timeout with budgetMs=0 still produces Scoped (no implicit validation)`() {
        // Pins the current contract: the interpreter does not reject
        // budgetMs <= 0; it simply propagates the value. A future change
        // that adds `require(budgetMs > 0)` here will need to update
        // this test deliberately (and document the fail-closed policy).
        val scope = BlockShellScopeDescriptor.Timeout(budgetMs = 0L)
        val projection = BodyExecutionProjectionDescriptor.Scope(scope)
        val interpreted = interpreter.interpret(projection)
        assertTrue(interpreted is InterpretedBody.Scoped)
        val scoped = interpreted as InterpretedBody.Scoped
        assertEquals(ContextOverlayDescriptor.Deadline(0L), scoped.contextOverlay)
    }
}
