package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4-F1-B — ADR-S4-R1 is `ACCEPTED`, and this is the structural proof that production now has
 * exactly ONE reconciliation/replay authority.
 *
 * ## What the ADR claimed, and what was true
 *
 * ADR-S4-R1 §0 says there is a single place where facts acquire reconciliation meaning. It also had
 * to *name its own exception* in §4, because "a single-authority claim that does not name the
 * exception is incomplete". The exception was `JournaledScriptedOperationRuntime` plus
 * `DurableScriptedOperationReconciler`: 357 lines in `src/main`, constructed only from one test
 * class, owning its own fingerprint, its own replay table and its own status mapping.
 *
 * A second authority that is merely UNREACHABLE is still a second authority, and it is a worse one
 * than an absent one, because the next engineer finds it, reads its KDoc, and wires it up.
 *
 * ## Why this reads CODE and not prose
 *
 * Every rule below is checked against production sources with COMMENTS STRIPPED. Without stripping,
 * this file's own KDoc — and the historical KDoc that survives in the files the class used to live
 * in — would satisfy every law by mentioning the very names the laws forbid. A fitness scan that a
 * comment can pass is a fitness scan that measures the comment.
 *
 * Stripping is why the retired names may still be mentioned in prose: this is about DECLARED CODE,
 * which is where an authority would come back from.
 */
class SingleDurableAuthorityFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    /**
     * The retired second authority, by name. Kept in ONE list so a future addition is a deliberate
     * edit here rather than a silent new file nobody scoped.
     */
    private val retiredSecondAuthorityNames = listOf(
        "JournaledScriptedOperationRuntime",
        "DurableScriptedOperationReconciler",
        // The scripted recovery SEAM. It existed only to serve the two classes above, and with them
        // gone it had zero production consumers — which made it the hook a second authority could
        // be re-attached through, sitting in src/main looking like part of the contract.
        "RunningScriptedOperationReconciler",
        "ScriptedRunningResolution",
    )

    private fun productionSources(): List<Path> = ScannerSupport.walkKotlinFiles(v2Root)
        .filter { it.toString().replace('\\', '/').contains("/src/main/") }
        .map { it.toAbsolutePath().normalize() }

    /**
     * Removes KDoc and line comments so a law cannot be satisfied by prose.
     *
     * Deliberately simple rather than a real lexer: it only has to be good enough that no
     * DECLARED code is removed. Stripping more aggressively would risk deleting code and making a
     * fitness pass, which is the failure mode this helper exists to prevent — the D2 structural row
     * failed for exactly that reason when its own KDoc named the type it forbade.
     */
    private fun codeOnly(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            when {
                // string literal — copy verbatim, including any "//" it contains
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
                // line comment
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                // block comment
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

    private fun productionCodeContaining(token: String): List<String> = productionSources()
        .filter { codeOnly(Files.readString(it)).contains(token) }
        .map { v2Root.relativize(it).toString().replace('\\', '/') }

    @Test
    fun `no retired second reconciliation authority is declared in production`() {
        val offenders = retiredSecondAuthorityNames.flatMap { name ->
            productionCodeContaining(name).map { "$it  ($name)" }
        }

        assertTrue(
            offenders.isEmpty(),
            "ADR-S4-R1 is ACCEPTED, so production must contain exactly one reconciliation/replay " +
                "authority. A retired one that is merely unreachable is still a second authority, " +
                "and a worse one than an absent one: the next engineer finds it, reads its KDoc, " +
                "and wires it up. If a compatibility reason genuinely forces one of these back, " +
                "it must return as a DELEGATING SHIM with no fingerprint, no replay table and no " +
                "status mapping of its own, and this list must say so. Found in:\n" +
                offenders.joinToString("\n") { "\t$it" },
        )
    }

    @Test
    fun `only the reconciliation authority ever asks for a replay decision`() {
        val callers = productionCodeContaining("effectReplayPolicy.decide(")

        assertEquals(
            1,
            callers.size,
            "EXACTLY ONE production call site may ask `EffectReplayPolicy` for a replay decision, " +
                "and it is `DurableInvocationResolver`. This is the law, as opposed to the one this " +
                "row originally asserted — a cap on `Fingerprint.compute` call sites, which turned " +
                "out to measure nothing: the fingerprint is a PURE constructor with six legitimate " +
                "call sites (body loop, body single, parallel aggregate, step journal, the " +
                "resolver's own comparison, and the scripted adaptation), and none of them " +
                "decides anything. A frontend SUPPLYING the policy is not deciding with it: " +
                "`ScriptedFrontendRunner` constructs `DefaultEffectReplayPolicy` and injects it, " +
                "which is the correct direction of dependency. Found decide() callers in:\n" +
                callers.joinToString("\n") { "\t$it" },
        )
    }

    /** Comparison forms that would turn a durable status into a decision. */
    private val statusComparisons = listOf(
        "status ==",
        "status !=",
        "when (status",
        "when (row.status",
        ".status ==",
        ".status !=",
    )

    @Test
    fun `the scripted surface never reads a durable status to decide`() {
        val offenders = productionSources()
            .filter { it.toString().replace('\\', '/').contains("/application/scripted/") }
            .map { it to codeOnly(Files.readString(it)) }
            .filter { (_, code) -> statusComparisons.any { code.contains(it) } }
            .map { (path, _) -> v2Root.relativize(path).toString().replace('\\', '/') }

        assertTrue(
            offenders.isEmpty(),
            "A frontend MUST NOT branch on a durable status. `OperationStatus` is the " +
                "AUTHORITY's output vocabulary; reading it to choose reuse-vs-execute re-creates " +
                "the very decision table ADR-S4-R1 forbids, and it is the exact shape of the " +
                "divergence ADR-0103 R1-E removed. Writing a row's status is fine and expected — " +
                "it is READING one to decide that is forbidden. Found in:\n" +
                offenders.joinToString("\n") { "\t$it" },
        )
    }

    @Test
    fun `no scripted surface hardcodes a replay policy`() {
        val offenders = productionSources()
            .filter { it.toString().replace('\\', '/').contains("/application/scripted/") }
            .map { it to codeOnly(Files.readString(it)) }
            .filter { (_, code) -> code.contains("ReplayPolicy.MEMOIZED") }
            .map { (path, _) -> v2Root.relativize(path).toString().replace('\\', '/') }

        assertTrue(
            offenders.isEmpty(),
            "A frontend MUST NOT substitute a literal replay policy. `ReplayPolicy` is declared by " +
                "the StepDescriptor and consumed by the canonical authority; a scripted literal is " +
                "the exact shape of the divergence ADR-0103 R1-E removed. Found in:\n" +
                offenders.joinToString("\n") { "\t$it" },
        )
    }
}
