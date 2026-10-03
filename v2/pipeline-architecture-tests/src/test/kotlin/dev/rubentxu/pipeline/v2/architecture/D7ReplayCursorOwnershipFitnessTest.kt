package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4-R1-B/D7 — the replay cursor is CANONICAL TRAVERSAL state, and this pins that.
 *
 * ## The hole D7 fills
 *
 * `DurableStepExecutor` held a `ReplayCursorStore` and advanced it, and
 * `RecoveryInterpretationEngine` held a second one and advanced it too. Both were wrong
 * for the same reason, and the reason is a type fact, not a style opinion:
 *
 * ```text
 * ReplayCursor(runId, lastOpId, stageIndex, savedAt)
 * ```
 *
 * `stageIndex` is documented as *"the stage index at which execution should resume"*. It
 * answers **where the RUN continues**, so it is a property of a traversal over a stage
 * graph, not of an operation. Everything a durable operation knows about itself — its id,
 * fingerprint, input, output, status, effects, replay and recovery policy — is independent
 * of which stage it happened to run in.
 *
 * That misplacement had a concrete cost rather than a theoretical one. The scripted
 * frontend cannot supply the dependency: `MainScriptedSupport` never passes a
 * `ReplayCursorStore`, and `ScriptedFrontendRunner` fixes `stageIndex = 0` for scripted
 * calls. So the moment a scripted call entered the executor as it stood, it would have
 * advanced the run's resume point to stage 0 — silently rewinding a canonical run's
 * position on a runtime-returning call that has no stage at all. The dependency could only
 * have been satisfied by inventing a `NoOpReplayCursor` or a scripted-scoped one, which is
 * the tell that the dependency was in the wrong class.
 *
 * ## Why these are source-level but not spellings
 *
 * D7-F1..F3 are asserted over **imports**, which is what "depends on" means for a type. The
 * executor and the recovery engine both still *mention* `ReplayCursorStore` in prose —
 * that is deliberate, it is the KDoc recording why they lost it — so a substring check
 * would fail on the documentation of the fix. `ScannerSupport.findImports` skips `//` and
 * `*` lines for exactly this reason.
 *
 * D7-F4 counts actual `cursorStore.advance(...)` call sites in production, because
 * "single owner" is a property of the call sites, not of a class declaration.
 */
class D7ReplayCursorOwnershipFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val durableMain: Path = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable",
    )

    private val scriptedMain: Path = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted",
    )

    private val executorSource: Path = durableMain.resolve("DurableStepExecutor.kt")
    private val recoverySource: Path = durableMain.resolve("RecoveryInterpretationEngine.kt")
    private val dispatchSource: Path = durableMain.resolve("StepDispatchEngine.kt")

    /** Only production sources: a test may legitimately hold a cursor to assert on it. */
    private fun productionFilesUnder(root: Path): List<Path> =
        ScannerSupport.walkKotlinFiles(v2Root)
            .filter { it.startsWith(root) }
            .filter { it.toString().replace('\\', '/').contains("/src/main/") }
            .map { it.toAbsolutePath().normalize() }

    private fun importFindingsIn(root: Path, token: String): List<Finding> =
        ScannerSupport.findImports(root, listOf(token)).filter { it.file.startsWith(root) }

    // ---------------------------------------------------------------- D7-F1

    @Test
    fun `D7-F1 the durable step executor does not depend on the replay cursor`() {
        val findings = importFindingsIn(durableMain, CURSOR_TYPE)
            .filter { it.file == executorSource.toAbsolutePath().normalize() }

        assertTrue(
            findings.isEmpty(),
            "DurableStepExecutor folds ONE operation into the journal and must not own the run's " +
                "resume position. `stageIndex` is traversal state; an operation has no stage. A " +
                "dependency here is what forced a frontend without a canonical stage position to " +
                "fabricate one. Found:\n" +
                findings.joinToString("\n") { "\t${it.file}:${it.line} ${it.line}" },
        )
    }

    // ---------------------------------------------------------------- D7-F2

    @Test
    fun `D7-F2 the recovery interpretation engine does not depend on the replay cursor`() {
        val findings = importFindingsIn(durableMain, CURSOR_TYPE)
            .filter { it.file == recoverySource.toAbsolutePath().normalize() }

        assertTrue(
            findings.isEmpty(),
            "Interpreting a recovery resolution is not traversal. If this engine advances the " +
                "cursor, cursor ownership is split between it and the canonical dispatch, which is " +
                "the two-owner shape ADR-0103 D7 exists to remove. Found:\n" +
                findings.joinToString("\n") { "\t${it.file}:${it.line} ${it.line}" },
        )
    }

    // ---------------------------------------------------------------- D7-F3

    @Test
    fun `D7-F3 the scripted frontend does not depend on the replay cursor`() {
        val findings = importFindingsIn(scriptedMain, CURSOR_TYPE)

        assertTrue(
            findings.isEmpty(),
            "The scripted frontend journals durable operations and returns typed values to user " +
                "Kotlin. It has no stage, so it has no cursor position: its identity is " +
                "entryPoint/callSite/dynamicScope/ordinal. A dependency here could only be " +
                "satisfied by a fabricated or no-op store. Found:\n" +
                findings.joinToString("\n") { "\t${it.file}:${it.line} ${it.line}" },
        )
    }

    // ---------------------------------------------------------------- D7-F4

    /**
     * Single owner, counted at the call sites. "The traversal owns the cursor" is only true if
     * there is exactly one traversal and no other production writer.
     */
    @Test
    fun `D7-F4 the canonical dispatch holds the only cursor advancement call sites`() {
        val writers = productionFilesUnder(durableMain)
            .plus(productionFilesUnder(scriptedMain))
            .filter { Files.readString(it).contains("cursorStore.advance") }
            .map { it.toAbsolutePath().normalize() }

        assertEquals(
            listOf(dispatchSource.toAbsolutePath().normalize()),
            writers,
            "Exactly one production component may advance the run replay cursor, and it must be the " +
                "canonical structural/run traversal. A second writer is a second owner, and the two " +
                "would disagree about when a run resumes. Found writers:\n" +
                writers.joinToString("\n") { "\t$it" },
        )

        val advanceCallSites = Files.readAllLines(dispatchSource).count { it.contains("cursorStore.advance(") }
        assertEquals(
            EXPECTED_ADVANCE_SITES,
            advanceCallSites,
            "The canonical traversal advances the cursor on exactly two paths: a recovered success " +
                "and an executed non-failure. A third site means a new rule appeared without a " +
                "decision, and a removed site means a rule was lost in the move. Both are the same " +
                "defect in opposite directions.",
        )
    }

    /**
     * The two rules are DIFFERENT on purpose, and D7 relocated them without being licensed to
     * reconcile them. Pinned so that a later change to either one is a deliberate decision rather
     * than a side effect of touching the traversal.
     *
     * ```text
     * Execute        → advance when outcome !is StepOutcome.Failure   (Unstable ADVANCES)
     * RecoverRunning → advance when outcome  is StepOutcome.Success    (Unstable does NOT)
     * ```
     */
    @Test
    fun `D7-F4b the two advancement rules are named, not inlined`() {
        val text = Files.readString(dispatchSource)

        assertTrue(
            text.contains("execution.outcome.advancesCanonicalCursor()"),
            "The Execute advancement must go through the named predicate, so its rule is stated in " +
                "one place instead of being re-derived at the call site.",
        )
        assertTrue(
            text.contains("interpretation.outcome is StepOutcome.Success"),
            "The RecoverRunning advancement keeps its stricter historical rule. Recovery has " +
                "always been stricter than execution about Unstable; whether the two should agree is " +
                "a separate question with its own work item, and quietly unifying them here would " +
                "bury a behaviour change inside a refactor.",
        )
    }

    private companion object {
        const val CURSOR_TYPE = "ReplayCursorStore"
        const val EXPECTED_ADVANCE_SITES = 2
    }
}
