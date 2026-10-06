package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4-F1-C — the structural proof that a RECOVERED value reaches the program through the same
 * carrier a FRESH one does, and that nothing in the path re-decides what the exit code means.
 *
 * ## Why this is a fitness and not more rows in the truth matrix
 *
 * The matrix (`S4R1F1CRecoveryTruthMatrixTest`) measures BEHAVIOUR, and behaviour tests are the
 * evidence that matters most. They cannot, however, defend the three things F1-C changed, because
 * each of those is a shape that can be violated without changing a single observed row:
 *
 * ```text
 * 1. a `when (stepKey)` in the recovery path would answer a different question on a different day
 * 2. a semantic terminal in the observer would classify before the Step's contract is consulted
 * 3. a narrowed carrier would journal the value correctly and never hand it to the program
 * ```
 *
 * Row three is not hypothetical: it is what M-F1-C3 does, and that mutation stayed GREEN across all
 * 17 matrix rows, both replay spikes and the pre-existing 13 `ScriptedScopeTest` rows. A behavioural
 * suite did not merely fail to catch it — it could not, because the matrix reads the JOURNAL and
 * the narrowing only affects the CONSUMER. That gap is closed by one row added at the consumer
 * boundary, and this fitness prevents the shape from quietly returning afterwards.
 *
 * ## Why this reads CODE and not prose
 *
 * Every law below is checked against production sources with COMMENTS STRIPPED, for the same reason
 * `SingleDurableAuthorityFitnessTest` does it. It is not hypothetical here: FIVE separate production
 * files currently contain the string `when (stepKey)` inside KDoc, and every one of them is
 * *describing the prohibition this fitness enforces*. A scan that counted prose would be measuring
 * the documentation's diligence.
 */
class RecoveredValueSpineFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private fun productionSources(): List<Path> = ScannerSupport.walkKotlinFiles(v2Root)
        .filter { it.toString().replace('\\', '/').contains("/src/main/") }
        .map { it.toAbsolutePath().normalize() }

    /**
     * Removes KDoc and line comments so a law cannot be satisfied by prose.
     *
     * Same helper shape as `SingleDurableAuthorityFitnessTest`, and deliberately duplicated rather
     * than extracted: two fitnesses that disagree about what "code" means is a worse outcome than
     * twenty lines repeated, and the two have different blast radii (this one scans all of `src/main`,
     * that one scopes to the scripted surface).
     */
    private fun codeOnly(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            when {
                source[i] == '"' -> {
                    append(source[i])
                    i++
                    while (i < source.length) {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            append(source[i]); append(source[i + 1]); i += 2
                            continue
                        }
                        append(source[i])
                        val closing = source[i] == '"'
                        i++
                        if (closing) break
                    }
                }
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                else -> {
                    append(source[i]); i++
                }
            }
        }
    }

    /** `path:line` for every declared-code hit of [pattern], across all of production. */
    private fun codeHits(pattern: Regex): List<String> = productionSources()
        .flatMap { path ->
            val relative = v2Root.relativize(path).toString().replace('\\', '/')
            codeOnly(Files.readString(path))
                .lineSequence()
                .withIndex()
                .filter { (_, line) -> pattern.containsMatchIn(line) }
                .map { (index, line) -> "$relative:${index + 1}  ${line.trim().take(120)}" }
                .toList()
        }

    /**
     * LAW 1 — no `when` over a Step key, anywhere in production.
     *
     * ## Why the pattern is narrow, and how it was chosen
     *
     * The obvious version of this law — "no `when (key)` in production" — is FALSE, and measuring it
     * is the only reason this law has a narrow pattern. Three legitimate families match it:
     *
     * ```text
     * BodyExecutionEngine   PluginStepId("wait-until-poll") / PluginStepId("retry-attempt")
     *                       durable IDENTITY segments, not dispatch keys
     * YamlEventContractCodec                  when (key) over unrelated enums
     * ```
     *
     * A law that fails on correct code is a law that gets "fixed" by deleting the law, so the
     * pattern names the actual dispatch shapes instead: a `when` whose subject is a step key or a
     * contract key. Constructing a `PluginStepId` is not branching on one.
     */
    @Test
    fun `no production code branches on a step key`() {
        val offenders = codeHits(
            Regex("""when\s*\(\s*(stepKey|call\.stepKey|definition\.contract\.key|contract\.key)\s*\)"""),
        )

        assertTrue(
            offenders.isEmpty(),
            "ADR-S4-R1 §2.7: the recovery path reaches a Step's semantics by asking for the " +
                "`RecoveredStepProjection` INTERFACE, never by matching on its key. A key switch in " +
                "this path is the exact defect F1-C0 measured — it answered a non-zero exit with a " +
                "SCRIPT failure without knowing `returnMode`, which is correct for NONE and wrong " +
                "for STATUS. Found:\n" + offenders.joinToString("\n") { "\t$it" },
        )
    }

    /**
     * LAW 2 — the observer names no semantics.
     *
     * `RunningSubprocessRecovery` reports what it saw. Every token below is a DECISION vocabulary:
     * `StepOutcome` is the Step's answer, `OperationStatus` is the journal's, and
     * `classifyShellTerminal` is the mapping between them, which belongs to the Step's contract and
     * is reached through the projection. The moment the observer holds one of them it is answering a
     * question only the contract can answer.
     */
    @Test
    fun `the recovery observer names no semantic vocabulary`() {
        val observer = productionSources()
            .firstOrNull { it.fileName.toString() == "RunningSubprocessRecovery.kt" }
        assertTrue(
            observer != null,
            "RunningSubprocessRecovery.kt must exist: it owns substrate observation, and F1-C made " +
                "it report FACTS. Its absence is not a simplification to celebrate.",
        )
        val code = codeOnly(Files.readString(observer!!))
        val forbidden = listOf(
            "StepOutcome",
            "OperationStatus",
            "ShellReturnMode",
            "toStepOutcome",
            "classifyShellTerminal",
        )
        val hits = forbidden.filter { code.contains(it) }

        assertTrue(
            hits.isEmpty(),
            "The observer produces FACTS (a `DurableTaskTerminal` read from the control directory), " +
                "never a meaning. Each of these tokens is a decision vocabulary, and holding one here " +
                "is what made `sh(returnStatus = true)` with exit 42 report FAILED. Found: $hits",
        )
    }

    /**
     * LAW 3 — the recovered carrier is the execution carrier.
     *
     * R14 was the narrowing of `CommonExecutionResult` to `StepOutcome` at both ends of the recovery
     * path. The fix is two declarations, and a refactor that reintroduces the narrowing would change
     * no journal byte, so it needs a mechanical guard as well as the consumer row that kills
     * M-F1-C3.
     *
     * The second half is the more interesting one: the engine must not RE-DERIVE the outcome it was
     * handed. `asStepOutcome()` / `asOperationStatus()` were the shapes that let one authority
     * re-classify another authority's decision, and F1-C deleted them.
     */
    @Test
    fun `recovery hands over the execution carrier and re-derives nothing`() {
        val settledDecl = """data class Settled(val result: CommonExecutionResult)"""
        val dispatchedDecl = """data class Dispatched(val result: CommonExecutionResult"""

        val interpretation = productionSources()
            .firstOrNull { it.fileName.toString() == "RecoveryInterpretationEngine.kt" }
        val dispatch = productionSources()
            .firstOrNull { it.fileName.toString() == "StepDispatchEngine.kt" }
        assertTrue(interpretation != null && dispatch != null, "both carrier owners must exist")

        val interpretationCode = codeOnly(Files.readString(interpretation!!))
        val dispatchCode = codeOnly(Files.readString(dispatch!!))

        assertTrue(
            interpretationCode.contains(settledDecl),
            "`RecoveryInterpretation.Settled` must carry the whole `CommonExecutionResult`. " +
                "Carrying a bare `StepOutcome` is R14 itself: the invocation produced a value and the " +
                "carrier dropped it before the consumer could receive it. Got:\n" +
                interpretationCode.lineSequence()
                    .filter { it.contains("data class Settled") }
                    .joinToString("\n") { "\t$it" },
        )
        assertTrue(
            dispatchCode.contains(dispatchedDecl),
            "`StepDispatchEngine.Dispatched` must carry the same carrier, for the same reason. Got:\n" +
                dispatchCode.lineSequence()
                    .filter { it.contains("data class Dispatched") }
                    .joinToString("\n") { "\t$it" },
        )

        val reDerived = listOf("asStepOutcome(", "asOperationStatus(")
            .filter { interpretationCode.contains(it) }
        assertTrue(
            reDerived.isEmpty(),
            "The interpretation engine persists and emits; it re-classifies NOTHING. Turning a " +
                "contract-derived outcome back into a durable status here would re-create the " +
                "second-guess F1-C deleted, one layer further from the observer. Found: $reDerived",
        )
    }

    /**
     * LAW 4 — exactly one materialisation path.
     *
     * The seam is deliberately NON-NULLABLE, and that was a correction rather than a style choice: it
     * started nullable with a fail-closed default, five composition sites construct the engine, and
     * the one that omitted it made EVERY recovered `core.sh` report «cannot yield a value» while the
     * build stayed green. A second call site would re-open exactly that hole with a different
     * diagnosis, so the count is pinned at one.
     */
    @Test
    fun `the materialiser has exactly one production call site`() {
        val referencing = productionSources()
            .filter { codeOnly(Files.readString(it)).contains("RecoveredExecutionMaterializer") }
            .map { v2Root.relativize(it).toString().replace('\\', '/') }

        val consumers = referencing.filterNot { it.endsWith("RecoveredExecutionMaterializer.kt") }

        assertEquals(
            1,
            consumers.size,
            "Exactly ONE production site may materialise a recovered terminal, and it is " +
                "`RecoveryInterpretationEngine`. A second one is a second semantic authority for the " +
                "same decision, and it is the shape a nullable seam invited: a composition site that " +
                "silently omitted it once already made every recovery fail closed with a green " +
                "build. Referencing production files: $referencing",
        )
    }
}
