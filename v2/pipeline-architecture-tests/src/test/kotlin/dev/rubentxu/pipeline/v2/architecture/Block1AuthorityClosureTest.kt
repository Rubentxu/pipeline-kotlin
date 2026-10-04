package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BLOCK 1 closing audit — seven authorities, and the negative searches that keep them single.
 *
 * ## Why this file exists separately from the fitnesses that went before it
 *
 * BLOCK 1's exit criterion is not "the feature works" but "one authority per fact, and a regression
 * is a build failure". Those are two different kinds of claim, so they are pinned in two places:
 * the fitnesses that were written WITH each change, and this one, which is written from the exit
 * criterion and asks whether the whole set still holds.
 *
 * The seven, and where each is proven. Duplicating a row that already has a fitness would create
 * two things to update for one law, so the already-pinned ones are NAMED here rather than repeated:
 *
 * | authority | proven by |
 * |---|---|
 * | `core.sh` admission | `CoreShSingleAdmissionAuthorityTest` (6 rows, 2 mutations) |
 * | durable process transcript | same, including the NonDurable-fallback property |
 * | retention decision | `RetentionAuthorityFitnessTest` (9 rows, 2 mutations) |
 * | replay cursor ownership | `D7ReplayCursorOwnershipFitnessTest` (5 rows) |
 * | Step dispatch / no central switch | `FArch` structural fitnesses + row 5 below |
 * | **stage finalization** | row 2 below |
 * | **run-outcome precedence** | *not mechanically pinned — see the note on that row* |
 *
 * ## The one authority deliberately NOT pinned here
 *
 * "`RunOutcome` has a single owner" cannot be checked by scanning, and the scan that looks like it
 * would give a false alarm: five production files legitimately mention both `RunOutcome.Failure` and
 * `RunOutcome.Unstable`, because projecting a run outcome into an event, a CLI string or a stage
 * fold is not the same as deciding its precedence. Only `RunOutcomeReducer` decides order, and it is
 * proven by its own tests. A fitness that claimed to check this would be a row that looks covered
 * and is not, which is worse than an honest gap.
 */
class Block1AuthorityClosureTest {

    private val v2Root: Path = generateSequence(Path.of("").toAbsolutePath()) { dir -> dir.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?.resolve("v2")
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has a " +
                ".git entry. This fitness would otherwise scan nothing and pass.",
        )

    private fun productionSources(): List<Path> =
        Files.walk(v2Root).use { stream ->
            val roots = stream
                .filter { Files.isDirectory(it) && it.fileName.toString() == "kotlin" }
                .filter { it.parent?.fileName?.toString() == "main" }
                .filter { it.parent?.parent?.fileName?.toString() == "src" }
                .filter { !it.toString().contains("/build/") }
                .toList()
            roots.flatMap { root ->
                Files.walk(root).use { inner ->
                    inner
                        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                        .toList()
                }
            }
        }.filterNot { it.fileName.toString() == "Block1AuthorityClosureTest.kt" }

    private fun Path.relativeToV2(): String = toString().removePrefix("$v2Root/").removePrefix("$v2Root\\")

    private fun Path.text(): String = Files.readString(this)

    /**
     * The source with its comments removed.
     *
     * A dead guard is a statement, not a sentence, so a mention inside a KDoc must not count — and
     * this very repository now carries three KDoc lines that quote `if (false)` to explain that it
     * was removed. Stripping block comments and line comments is blunt (a `//` inside a string
     * literal would also be cut) and that is acceptable here: the fitness would then miss a guard
     * written after such a literal on the same line, which is a shape no reviewer would write.
     */
    private fun Path.codeOnly(): String =
        text()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }

    // ----------------------------------------------------- negative search: dead guards

    @Test
    fun `no production source carries a guard that can never run`() {
        // Found and removed in this block: `SegmentOutputStore` truncated a segment through
        // `if (false)`, with a dead local beside it and a comment describing a truncation that never
        // happened. It read like a defence and enforced nothing — and a disabled guard is worse than
        // a missing one, because the next reader counts it.
        val offenders = productionSources()
            .map { it to it.codeOnly() }
            .filter { (_, code) -> code.contains("if (false)") || code.contains("if (true)") }
            .map { (path, _) -> path.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "a guard that cannot run is a claim the code is not making. Offending files: $offenders",
        )
    }

    @Test
    fun `no production source leaves an explicit todo behind`() {
        // Same class, same reason: a marker that promises work the code has not done is a lie with a
        // comment's syntax. Allowed: a reference to a ticket in a comment, which this does not match.
        val offenders = productionSources()
            .map { it to it.codeOnly() }
            .filter { (_, code) -> code.contains("TODO(") || code.contains("FIXME(") }
            .map { (path, _) -> path.relativeToV2() }

        assertTrue(offenders.isEmpty(), "production code carries no unresolved todo. Offending files: $offenders")
    }

    // ------------------------------------------- authority: stage finalization, once

    @Test
    fun `the stage finalization rule is called from exactly one file`() {
        // Three call sites needed the same three things — run `post` for a known outcome, emit
        // `StageFinished` unless the run is failing, report a finalizer failure — and they were
        // written three times. Two of the copies had already drifted: the coordinator's parallel arm
        // passed outcome STRINGS where the engine passed the enum. `finalizeStage` is now reachable
        // only from its own class, and the coordinator goes through `finalizeStageOutcome`.
        val callers = productionSources()
            .map { it to it.codeOnly() }
            .filter { (path, _) -> path.fileName.toString() != "StageExecutionEngine.kt" }
            .filter { (_, code) -> Regex("""\bfinalizeStage\s*\(""").containsMatchIn(code) }
            .map { (path, _) -> path.relativeToV2() }

        assertTrue(
            callers.isEmpty(),
            "finalizeStage is the ONE finalization rule and it is owned by StageExecutionEngine. " +
                "Anything else calling it is a second copy of the rule: $callers",
        )
    }

    @Test
    fun `the coordinator routes both one-outcome bodies through the shared tail`() {
        // The other half of the law, and the reason the above is not satisfied by moving the copies:
        // a `parallel` body and a `scripted` body each produce exactly ONE outcome for the whole
        // stage, so both take the same tail. If a third body shape appears, it takes this call too
        // rather than inlining a continuation.
        val coordinator = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/" +
                "CanonicalDurableRunCoordinator.kt",
        )
        val code = coordinator.codeOnly()

        assertTrue(
            code.contains("finalizeStageOutcome("),
            "the coordinator must finalise a one-outcome body through the engine's tail",
        )
        assertEquals(
            1,
            Regex("""\bfinalizeStageOutcome\s*\(""").findAll(code).count(),
            "and through it exactly once, because two copies of a rule is two places to forget one",
        )
    }

    // ------------------------------- authority: a scripted stage is not IR any more

    @Test
    fun `a scripted stage body is reached by the run spine, not refused by it`() {
        // BLOCK 1's reason for existing at all. `StageBody.Scripted` was declared, validated and
        // unreachable: the coordinator threw `Unsupported Scripted` at dispatch. A construct that is
        // IR-only is a promise with no behaviour, and the semantic constitution requires one of
        // three things of a new construct — a carrier, a pure desugar, or a refusal. The refusal
        // branch is still the correct one for an artifact that cannot be resolved; what must not
        // come back is refusing the SHAPE.
        val coordinator = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/" +
                "CanonicalDurableRunCoordinator.kt",
        ).codeOnly()

        assertTrue(
            coordinator.contains("stage.body is StageBody.Scripted"),
            "the run spine must fork for a scripted body instead of refusing the shape",
        )
        assertTrue(
            coordinator.contains("runScriptedStage("),
            "and it must hand the body to the engine, which owns the continuation and the finalization",
        )
        // The fail-closed on an unknown body shape must SURVIVE. Adding a branch for one shape is
        // not a reason to remove the guard for the next one, and the guard is what makes a body
        // shape nobody runs a named failure rather than a silent pass.
        assertTrue(
            coordinator.contains("IllegalArgumentException") && coordinator.contains("Unsupported"),
            "an unknown body shape must still be refused BY NAME at dispatch",
        )
    }

    @Test
    fun `a scripted body's structural address is derived, never a hardcoded zero`() {
        // `OpId` is `runId-s{stageIndex}-{stepIndex}`. A scripted stage addressed with a fixed 0
        // answers to the same durable row as the stage at index 0, and two bodies would read each
        // other's facts as their own replay evidence. The mutation that motivated this row killed
        // exactly one test and this is what keeps it killed.
        val callers = productionSources()
            .map { it to it.codeOnly() }
            .filter { (path, _) -> path.fileName.toString() != "ScriptedStructuralAddress.kt" }
            .filter { (path, code) ->
                code.contains("InCanonicalStage(") && Regex("""stageIndex\s*=\s*0\b""").containsMatchIn(code)
            }
            .map { (path, _) -> path.relativeToV2() }

        assertTrue(
            callers.isEmpty(),
            "a scripted stage carries its real stage index; a literal 0 here is a durable row " +
                "collision. Offending files: $callers",
        )
    }

    // ------------------------------------------ negative search: no central step switch

    @Test
    fun `no dispatcher switches on a concrete Step key`() {
        // The fitness of the step registry: a `when` over a step key at a selection site is the
        // closed-world defect the registry was split to remove, because it makes every new plugin a
        // core edit. Measured today: zero. Named patterns rather than a bare `when`, because a
        // `when` over a *closed structural ADT* is the engine working correctly.
        val dispatchers = productionSources()
            .map { it to it.codeOnly() }
            .filter { (_, code) ->
                Regex("""when\s*\(\s*(stepKey|pluginKey|stepId|step\.key|body\.key)\s*\)""").containsMatchIn(code)
            }
            .map { (path, _) -> path.relativeToV2() }

        assertTrue(
            dispatchers.isEmpty(),
            "Step selection goes through the registry, not through a key. Offending files: $dispatchers",
        )
    }
}
