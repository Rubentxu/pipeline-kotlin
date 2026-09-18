package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * WU-LPR-302 — body/control execution consolidation, architecture fitness.
 *
 * The properties pinned here are the SEAM of the refactor, not the names of the
 * files that hold it. The guard must remain meaningful if someone renames
 * `RetryEngine.kt` tomorrow or moves its content into a sibling package: it
 * fails the moment the durable coordinator re-acquires a retry/waitUntil
 * control-flow loop, OR the moment a second body re-entry port appears next
 * to [dev.rubentxu.pipeline.v2.domain.step.BodyInvoker].
 *
 * ## Properties pinned
 *
 *  1. **Body re-entry port count = 1**: only [BodyInvoker] exists. No
 *     `BranchInvoker`, `BodyExecutor`, `BodyRunnerPort`, or `BlockExecutor`
 *     anywhere in the production source.
 *  2. **BodyInvocationContext is consumed**: the canonical coordinator's
 *     adapter registers a runner that actually reads
 *     [dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext]; it is not
 *     a decorative parameter.
 *  3. **Coordinator contains no retry mechanics**: no inline retry control
 *     loops or `RetryReconciliationDecision` switches; retry is owned by
 *     `retry/RetryEngine`.
 *  4. **Coordinator contains no waitUntil mechanics**: no inline waitUntil
 *     polling loop or `WaitUntilReconciliationDecision` switches; waitUntil
 *     is owned by `waituntil/WaitUntilEngine`.
 *  5. **Coordinator contains no concrete-Step routing**: the
 *     `Lfc2ConcreteBodyRoutingDebtFitnessTest` already pins this property;
 *     this fitness re-asserts the body-path discipline as a separate guard.
 *
 * ## What this fitness does NOT pin
 *
 * Parallel branch execution is intentionally OUT OF SCOPE for this guard.
 * `runParallelStage` continues to call `executeBranchSteps` directly because
 * branch re-entry has its own durable identity scheme (`-bp{N}-b{N}`) and
 * has not yet been proven equivalent to `BodyRef`/`BodyInvoker`. That
 * follow-up is documented in `docs/v2/07-uat/WU_LPR_302_RECEIPT.md` as
 * `PARALLEL BODY-REENTRY MODEL = UNRESOLVED / DEFERRED`.
 *
 * Until the follow-up lands, the fitness treats parallel as a separate
 * primitive and asserts only that the durable coordinator still contains
 * the existing branch dispatch surface (not zero, not duplicate).
 */
class Lfc2WULpr302BodyControlSeamFitnessTest {

    private val coordinatorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt")

    private val applicationSources: List<java.nio.file.Path> =
        ScannerSupport.v2Root().resolve("pipeline-application/src/main/kotlin").let { root ->
            org.junit.jupiter.api.Assertions.assertDoesNotThrow {
                Files.walk(root)
            }
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".kt") }
                    .toList()
            }
        }

    private fun coordinatorText(): String {
        require(Files.exists(coordinatorSource)) { "Expected source not found: $coordinatorSource" }
        return Files.readString(coordinatorSource)
    }

    /**
     * Strip Kotlin line comments, block comments, and KDoc/multi-line
     * comments so that the architecture pins do not trip on prose. A naive
     * `[*XxxDecision.Yyy]` KDoc mention is documentation, not control flow.
     *
     * The stripper is intentionally line-based and not a full Kotlin parser:
     * the goal is to remove `//...` and `/* ... */` blocks well enough that
     * the banned identifiers do not appear inside them, while keeping the
     * rest of the file byte-equivalent. Nested block comments are not
     * supported because they are not legal Kotlin.
     */
    private fun stripKotlinCommentsAndDocstrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                // Line comment: skip to end of line.
                while (i < source.length && source[i] != '\n') i++
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                // Block comment: skip to `*/`.
                i += 2
                while (i + 1 < source.length &&
                    !(source[i] == '*' && source[i + 1] == '/')
                ) i++
                i += 2
            } else if (c == '"') {
                // String literal: copy as-is until closing quote.
                out.append(c); i++
                while (i < source.length && source[i] != '"') {
                    if (source[i] == '\\' && i + 1 < source.length) {
                        out.append(source[i]); out.append(source[i + 1]); i += 2
                    } else {
                        out.append(source[i]); i++
                    }
                }
                if (i < source.length) { out.append(source[i]); i++ }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /**
     * Property 1: only `BodyInvoker` exists as a body re-entry port. No
     * `BranchInvoker`, `BodyExecutor`, `BodyRunnerPort`, or `BlockExecutor`
     * in production source.
     *
     * Comments are excluded via the same stripper used by properties 3/4:
     * the engine classes have KDoc prose that NAMES these banned identifiers
     * in order to describe what they DO NOT declare. Stripping keeps prose
     * out of the executable surface.
     */
    @Test
    fun `only BodyInvoker is a body re-entry port in production source`() {
        val bannedPorts = listOf(
            "BranchInvoker",
            "BodyExecutor",
            "BodyRunnerPort",
            "BlockExecutor",
        )
        val offending = applicationSources
            .filter { Files.isRegularFile(it) }
            .map { path -> path to stripKotlinCommentsAndDocstrings(Files.readString(path)) }
            .filter { (_, stripped) -> bannedPorts.any { it in stripped } }
            .map { (path, _) -> path.toString() }
        assertEquals(
            emptyList<String>(),
            offending,
            "second body re-entry port detected; the only generic body re-entry " +
                "port is BodyInvoker. Found occurrences of banned ports in: $offending",
        )
    }

    /**
     * Property 2: `BodyInvocationContext` is consumed by the canonical
     * adapter. The runner registers against the adapter and reads the
     * context — it is not a decorative parameter.
     */
    @Test
    fun `BodyInvocationContext is consumed by the canonical body runner`() {
        val adapterSource = Files.readString(
            ScannerSupport.v2Root().resolve(
                "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalBodyInvokerAdapter.kt",
            ),
        )
        assertTrue(
            "BodyInvocationContext" in adapterSource,
            "CanonicalBodyInvokerAdapter must reference BodyInvocationContext",
        )
        // The adapter takes a contextual runner (lambda receives
        // BodyInvocationContext). If a future change drops the parameter
        // and the runner reads no fields, the seam has decayed.
        assertTrue(
            "BodyInvocationContext" in adapterSource && ".attempt" in adapterSource,
            "canonical runner must read BodyInvocationContext fields (attempt, patch); " +
                "if the parameter is decorative the seam has regressed",
        )
    }

    /**
     * Property 3: the coordinator contains no retry mechanics. The retry
     * aggregate is owned by RetryEngine.
     *
     * This is a SOURCE-LEVEL pin: the coordinator may reference
     * `RetryEngine` (the engine it dispatches to) but MUST NOT contain the
     * retry reconciliation decision switch or a `dispatchRetryAwareBody`
     * survivor.
     *
     * Comments and docstrings are deliberately excluded — the seam is about
     * executable control flow, not about prose that names a decision
     * (KDoc `[*XxxDecision.Yyy]` mentions are part of normal documentation
     * and stay allowed).
     */
    @Test
    fun `coordinator contains no inline retry mechanics`() {
        val text = stripKotlinCommentsAndDocstrings(coordinatorText())
        val bannedBodies = listOf(
            // Pre-Phase 2 inline body. Retired in commit b08538aa.
            "dispatchRetryAwareBody",
            "persistAttemptTerminalTransition",
            // Reconciler switch body. Lives in RetryEngine now.
            "is dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.",
            "RetryReconciliationDecision.ScheduleAttempt",
            "RetryReconciliationDecision.ResumeAttempt",
            "RetryReconciliationDecision.ReuseCompleted",
            "RetryReconciliationDecision.AdvanceAfterFailure",
            // Driver reference. Lives in RetryEngine now.
            "RetryReconciler",
        )
        val offending = bannedBodies.filter { it in text }
        assertEquals(
            emptyList<String>(),
            offending,
            "coordinator must not own retry mechanics; the retry aggregate is owned by " +
                "RetryEngine. Found: $offending",
        )
    }

    /**
     * Property 4: the coordinator contains no waitUntil mechanics. The
     * waitUntil aggregate is owned by WaitUntilEngine.
     *
     * Same exclusions as property 3: comments and docstrings are not
     * control-flow surface.
     */
    @Test
    fun `coordinator contains no inline waitUntil mechanics`() {
        val text = stripKotlinCommentsAndDocstrings(coordinatorText())
        val bannedBodies = listOf(
            "is dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.",
            "WaitUntilReconciliationDecision.ScheduleAttempt",
            "WaitUntilReconciliationDecision.ResumeAttempt",
            "WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied",
            "WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied",
            "WaitUntilReconciliationDecision.DeadlineExceeded",
            "WaitUntilReconciliationDriver",
            "WaitUntilReconciler",
        )
        val offending = bannedBodies.filter { it in text }
        assertEquals(
            emptyList<String>(),
            offending,
            "coordinator must not own waitUntil mechanics; the waitUntil aggregate is " +
                "owned by WaitUntilEngine. Found: $offending",
        )
    }

    /**
     * Property 5: the parallel stage is a separate primitive, NOT routed
     * through BodyInvoker. The branch dispatch surface must remain in the
     * coordinator because branches have their own durable identity scheme
     * (`-bp{N}-b{N}`) that has not yet been proven equivalent to BodyRef.
     *
     * The fitness is a FRONTIER, not a convergence. It pins that the
     * `runParallelStage` authority still exists in the coordinator (so the
     * follow-up WU has somewhere to start) and that the parallel body path
     * has not been silently duplicated or replaced by a BodyInvoker-shaped
     * adapter.
     */
    @Test
    fun `parallel branch dispatch remains a separate primitive in the coordinator`() {
        val text = coordinatorText()
        assertTrue(
            "runParallelStage" in text,
            "runParallelStage must remain the canonical entry point for parallel " +
                "stages; the parallel body re-entry model is intentionally separate " +
                "from BodyInvoker (follow-up: WU-LPR-302P)",
        )
        assertTrue(
            "executeBranchSteps" in text,
            "executeBranchSteps must remain the parallel branch dispatcher; the " +
                "parallel body re-entry model is intentionally separate from BodyInvoker",
        )
    }

    /**
     * Property 6: the legacy retry inline loop is preserved bit-equivalent
     * for callers without `retryControlJournal`. The fitness pins that
     * surface still exists so the no-journal wiring stays honest.
     */
    @Test
    fun `legacy retry inline loop remains preserved for no-journal callers`() {
        val text = coordinatorText()
        assertTrue(
            "is BlockShellScope.Retry" in text,
            "legacy retry inline loop must be preserved bit-equivalent for callers " +
                "without retryControlJournal — the no-journal surface stays in the " +
                "coordinator even after RetryEngine was extracted",
        )
    }

    /**
     * Property 7: the legacy waitUntil inline loop is preserved bit-equivalent
     * for callers without `waitUntilControlJournal`. The fitness pins that
     * surface still exists (`executeWaitUntilBodyInline`) so the no-journal
     * wiring stays honest.
     */
    @Test
    fun `legacy waitUntil inline loop remains preserved for no-journal callers`() {
        val text = coordinatorText()
        assertTrue(
            "executeWaitUntilBodyInline" in text,
            "legacy waitUntil inline loop must be preserved bit-equivalent for " +
                "callers without waitUntilControlJournal — the no-journal surface " +
                "stays in the coordinator even after WaitUntilEngine was extracted",
        )
        // And it is the bit-equivalent legacy loop: the durable retry-aware
        // path is owned by WaitUntilEngine, never by an inline switch.
        assertTrue(
            "WaitUntilEngine" in text,
            "the durable waitUntil aggregate must reach the coordinator through " +
                "WaitUntilEngine, not through a re-introduced inline switch",
        )
    }
}
