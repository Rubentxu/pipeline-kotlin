package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreShellStep
import dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * S4-IDENTITY (I1) — FALSIFICATION FIRST, against production wiring.
 *
 * ## The claim under test
 *
 * The durable identity of a repeated scripted call is
 * `callSiteId × dynamicScopePath × invocationOrdinal`. The ordinal is assigned at
 * RUNTIME by a process-local counter ([ScriptedScope.nextOrdinal]), not from a
 * structural position. So replay is correct only while the resumed execution
 * reaches each call site the same number of times, in the same order.
 *
 * S4-A0 §3.3 recorded this as a *constraint, not a proven defect*, because nothing
 * had driven it. This suite drives it.
 *
 * ## What the search found before writing any of this
 *
 * `ScriptedScope.scoped(ScriptedDynamicScopeId)` exists and is exercised — but only
 * by **hand-written** entry points in tests that call `steps.scoped(...)` directly.
 * A grep of the whole `pipeline-scripting-kotlin24` module for loop handling returns
 * nothing: the lowering emits **no** loop scope. The test at `ScriptedScopeTest`
 * named `generated loop scopes…` generates nothing; its Kotlin calls `scoped`
 * explicitly, so its name describes an intent the compiler does not fulfil.
 *
 * That makes the ordinal the ONLY thing distinguishing iterations of a real
 * `for` loop in a real `.pipeline.kts`.
 *
 * ## The shape that breaks
 *
 * A branch inside a loop whose predicate is NOT derived from a journaled runtime
 * value. The number of times the guarded call site is reached changes between the
 * original execution and the resume, so its ordinals renumber, and an ordinal that
 * used to mean `item-1` now means `item-0`.
 *
 * The failure is not a crash. It is a **silently wrong durable value**: the resumed
 * run returns what a different iteration persisted.
 */
@Timeout(20)
class S4IdentityOrdinalFalsificationTest {

    /**
     * Production spine: [ScriptedArtifactRuntime] over [ScriptedRegistryInvoker] and a
     * real journal, with `core.sh` satisfied through its DECLARED capability rather
     * than bypassed — the same composition `ScriptedFrontendRunner` builds.
     */
    private class Spine {
        val journal = InMemoryOperationJournal(SystemClock())
        val launched = mutableListOf<String>()

        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
                    registry = InMemoryStepRegistry().also { CoreShellStep.registerInto(it) },
                    journal = journal,
                    capabilityAccessFactory = { context ->
                                        object : CanonicalRuntimeCapabilityAccess(context) {
                                            override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
                    
                                            @Suppress("UNCHECKED_CAST")
                                            override fun <T : Any> get(key: StepCapability): T {
                                                if (key == SHELL_OPERATIONS_CAPABILITY) {
                                                    return object : ShellOperations {
                                                        override suspend fun invoke(
                                                            command: ShellCommand,
                                                            runId: RunId,
                                                            stepIndex: Int,
                                                        ): ShellInvocationResult {
                                                            launched += command.script
                                                            return ShellInvocationResult.Stdout(command.script)
                                                        }
                                                    } as T
                                                }
                                                return super.get(key)
                                            }
                                        }
                                    },
                ),
        )
    }

    /** `(callSite, scopePath, ordinal)` of every durable row, in insertion order. */
    private fun InMemoryOperationJournal.identitiesOf(runId: String): List<Triple<String, String, String>> =
        listForRun(runId).map { op ->
            Triple(
                op.input.params["callSiteId"]?.jsonPrimitive?.content.orEmpty(),
                op.input.params["dynamicScopePath"]?.jsonPrimitive?.content.orEmpty(),
                op.input.params["invocationOrdinal"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

    /**
     * A loop whose body reads `predicate`, exactly as a `.pipeline.kts` would after
     * lowering: plain Kotlin control flow, one rewritten call site inside a guard.
     *
     * No `scoped(...)`, because the lowering emits none. The only thing separating
     * one iteration from the next is the ordinal.
     */
    private class LoopEntryPoint(
        private val items: List<String>,
        private val predicate: (String) -> Boolean,
        private val value: (String) -> String = { "value-of-$it" },
    ) : CompiledScriptedEntryPoint {
        override val artifact = ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
        override val entryPointId = "loop-entry"

        override suspend fun execute(steps: ScriptedStepFacade) {
            for (item in items) {
                if (predicate(item)) {
                    steps.sh(
                        ScriptedCallSiteId("pipeline.kts:10:read"),
                        value(item),
                        ReturnStdout,
                        null,
                        null,
                    )
                }
            }
        }
    }

    @Test
    fun `a branch shift with DISTINCT inputs fails closed on input divergence`() = runBlocking {
        // The first hypothesis, and it was WRONG.
        //
        // I expected the resumed execution to reach call site `read` for item-A as
        // ordinal 0 — the identity the journal already holds for item-B — and to
        // therefore be served B's persisted value, silently.
        //
        // It does not. `ScriptedRegistryCall` folds `encodedInput` into the durable
        // fingerprint, so `value-of-A` and `value-of-B` are different operations and
        // the reuse is REFUSED:
        //
        //     PipelineStepException: scripted registry step input diverged
        //
        // That is the property that makes the ordinal far less dangerous than it
        // looks: two different arguments can never collide on one journal row, so a
        // renumbered ordinal can at worst refuse, not lie. Recorded as a measurement
        // rather than a defect, because a refusal is the correct outcome.
        val spine = Spine()

        val first = runCatching {
            spine.runtime.execute("run-divergent", LoopEntryPoint(listOf("A", "B"), predicate = { it == "B" }))
        }
        assertTrue(first.isSuccess, "control: the first execution must succeed, got $first")

        val resume = runCatching {
            spine.runtime.execute("run-divergent", LoopEntryPoint(listOf("A", "B"), predicate = { it == "A" }))
        }

        assertTrue(
            resume.isFailure,
            "S4-IDENTITY MEASURED: the resume reaches the call site for a DIFFERENT argument, " +
                "so the fingerprint diverges and the reuse is refused. It is not served the " +
                "other iteration's value.",
        )
        assertTrue(
            resume.exceptionOrNull() is dev.rubentxu.pipeline.v2.domain.PipelineStepException,
            "the refusal must be the typed divergence, not an incidental throw: " +
                "${resume.exceptionOrNull()?.let { it::class.simpleName }}",
        )
    }

    @Test
    fun `a branch shift with the SAME input silently SKIPS an effect the run owed`() = runBlocking {
        // The real defect, and it is narrower than the first hypothesis.
        //
        // When the guarded call site's ARGUMENT DOES NOT VARY between iterations, the
        // fingerprint cannot help: every arrival is the same operation, so a
        // renumbered ordinal looks like a legitimate memoised reuse.
        //
        // Here one loop iteration performs an effect that the pipeline OWES. The
        // resume reaches the call site once instead of twice because the branch
        // differs, so ordinal 1 — the row written for the second arrival — is never
        // visited. Nothing diverges, nothing is refused, and the effect is simply
        // not performed. A silent no-op, which the Semantic Constitution lists as a
        // forbidden defect class.
        val spine = Spine()
        val items = listOf("A", "B")

        // The argument DOES NOT VARY between iterations, so the two arrivals are the
        // same durable operation distinguished only by ordinal. That is what removes
        // the fingerprint's ability to help.
        val sameValue: (String) -> String = { "value-of-x" }

        // Both iterations take the branch on the first execution.
        spine.runtime.execute("run-skipped", LoopEntryPoint(items, predicate = { true }, value = sameValue))

        assertEquals(
            listOf("value-of-x", "value-of-x"),
            spine.launched,
            "control: the first execution performs the effect once per iteration",
        )
        assertEquals(
            listOf("0", "1"),
            spine.journal.identitiesOf("run-skipped").map { it.third },
            "control: the two arrivals differ only by ordinal, which is the whole point",
        )

        // The resume reaches it ONCE. Ordinal 0 is served from the journal; ordinal 1
        // is never visited.
        val resume = runCatching {
            spine.runtime.execute("run-skipped", LoopEntryPoint(items, predicate = { it == "A" }, value = sameValue))
        }

        assertTrue(
            resume.isSuccess,
            "S4-IDENTITY MEASURED DEFECT: the resume does not fail. The input is unchanged, " +
                "so the fingerprint is satisfied and the reuse is legitimate-looking — but " +
                "one owed effect is now simply not performed. Got $resume",
        )
        assertEquals(
            2,
            spine.launched.size,
            "S4-IDENTITY: the pipeline owed the effect twice and performed it once. The second " +
                "arrival's journal row (ordinal 1) is orphaned, and nothing reported it.",
        )
    }

    @Test
    fun `the guard is a fact about the source, so a stable predicate keeps the identities stable`() = runBlocking {
        // The control: same artifact, same predicate, restarted. This is the case the
        // current design DOES get right, and it is why the defect above is not a
        // constant. The property being falsified is specifically about a branch whose
        // decision is not a journalled runtime value.
        val spine = Spine()
        val stable: (String) -> Boolean = { it != "C" }
        val entry = LoopEntryPoint(listOf("A", "B", "C"), stable)

        spine.runtime.execute("run-stable", entry)
        spine.runtime.execute("run-stable", entry)

        val identities = spine.journal.identitiesOf("run-stable").distinct()
        assertEquals(
            listOf(
                Triple("pipeline.kts:10:read", "", "0"),
                Triple("pipeline.kts:10:read", "", "1"),
            ),
            identities,
            "a stable predicate reaches the call site the same number of times in the same " +
                "order, so the ordinals reproduce and the resume reuses the right rows",
        )
        assertEquals(
            2,
            spine.launched.size,
            "control: the predicate excludes C, so exactly two iterations performed the " +
                "effect and the resume reused both",
        )
    }
}
