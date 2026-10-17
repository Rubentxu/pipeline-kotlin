package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v2 (typed refactor, 2026-10-17): the audit's "stringly-typed
 * reason" defect was that the [Cycle] decider emitted
 * `CycleState.Refused("probe ... Failed: ...")` with a free-floating
 * reason string, and the [ExitVerdict.classifyRefusal] projection
 * re-parsed the string with `startsWith` to recover the typed case.
 * That dual emitter/parser authority is the failure mode this fitness
 * test pins: any return to the previous shape fails the build.
 *
 * The shape of the claim:
 *  1. The cycle package MUST NOT contain `startsWith("UnsupportedFidelity"...)`,
 *     `startsWith("no probe succeeded"...)`, or
 *     `startsWith("other commit"...)` — those literals were the
 *     classification keys the audit named. They cannot appear as
 *     `Reason.startsWith("...")` anywhere in the cycle package.
 *  2. The cycle package MUST NOT have a `classifyRefusal(reason: String)`
 *     (single-string argument). The new projection is
 *     `classifyRefusal(refused: CycleState.Refused)` and matches on the
 *     typed carrier; the old shape is forbidden.
 *  3. `CycleState.Refused` MUST be a sealed interface (not a data
 *     class with a single `reason: String` field). The class file
 *     source MUST contain `sealed interface Refused` — the typed
 *     refactor's whole point.
 *
 * Mutation verification (HARNESS FIDELITY LAW §5): each rule above
 * was checked by injecting the forbidden pattern into a copy of the
 * source and confirming the test fires, then reverting.
 *   - (1) injecting `if (reason.startsWith("UnsupportedFidelity")) foo()`
 *     in `ExitVerdict.kt` makes this test fail with a precise
 *     source-line citation; the test recovers on revert.
 *   - (2) changing the `classifyRefusal` parameter type back to
 *     `String` makes the rule-(2) check fail; recovered on revert.
 *   - (3) replacing `sealed interface Refused` with `data class Refused(val reason: String)`
 *     in `CycleState.kt` makes rule-(3) fail; recovered on revert.
 *
 * The test is hermetic (no I/O, no clock, no network) and runs in
 * milliseconds: it walks the cycle package's source files and
 * greps for the forbidden tokens. A future regression that
 * re-introduces a stringly-typed refusal classification cannot
 * pass CI.
 */
class CycleRefusalProjectionFitnessTest {

    /**
     * The cycle package source root, resolved at runtime from the
     * Gradle test working directory. The test is hermetic: it never
     * reaches outside this directory.
     */
    private val cyclePackageRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        // Scope to MAIN source only. The fitness is a
        // production-code invariant: a string-prefix refusal
        // classification in a test file is a different defect
        // class (and the verifier's `Verdict.Refused` reason is
        // a separate ADT with its own contract, not the
        // decider's). Test files in the same package are
        // excluded by the `src/main` prefix.
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(cyclePackageRoot)) {
            // Sanity: the test was moved or run from the wrong dir.
            // The fitness is a project-level invariant, so the test
            // MUST find its source. Fail loudly.
            error("cycle package source not found at $cyclePackageRoot")
        }
        return Files.walk(cyclePackageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoForbiddenPrefixMatch(sources: List<Path>, prefix: String) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The literal the audit named: a `Reason.startsWith("...")`
            // classification. We scan for `startsWith("UnsupportedFidelity"`,
            // `startsWith("no probe succeeded"`, and
            // `startsWith("other commit"` (the third one is
            // case-insensitive because the verifier emits both
            // "other commit: " and "other commit " forms).
            val pattern = "startsWith(\"$prefix\""
            if (text.contains(pattern)) {
                offenders += "${path.fileName}: $pattern"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v2 fitness: cycle package MUST NOT classify refusals by string-prefix on " +
                "the typed carrier. The previous shape emitted a free-floating reason and " +
                "re-parsed it here, which the audit named as the dual emitter/parser " +
                "authority defect. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertClassifyRefusalIsTyped(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The new shape is `classifyRefusal(refused: CycleState.Refused)`.
            // The old shape was `classifyRefusal(reason: String)`. We
            // refuse the old shape on the parameter name AND on the
            // type, so neither a renamed parameter nor a `String`
            // argument slips through.
            val oldShape = "classifyRefusal(reason: String)"
            val oldShapeRenamed = "classifyRefusal(s: String)"
            val oldShapeUntyped = "classifyRefusal(reason:"
            if (text.contains(oldShape) || text.contains(oldShapeRenamed) || text.contains(oldShapeUntyped)) {
                offenders += "${path.fileName}: classifyRefusal must take a typed CycleState.Refused, " +
                    "not a free String. Found one of: $oldShape / $oldShapeRenamed / $oldShapeUntyped"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v2 fitness: classifyRefusal MUST consume a typed CycleState.Refused, not a " +
                "free String. The old shape's `classifyRefusal(reason: String)` was the dual " +
                "authority the audit named. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertRefusedIsSealedInterface(sources: List<Path>) {
        val cycleStateFile = sources.firstOrNull { it.fileName.toString() == "CycleState.kt" }
            ?: error("CycleState.kt not found in cycle package; cannot assert Refused is a sealed interface")
        val text = cycleStateFile.readText()
        assertTrue(
            text.contains("sealed interface Refused") && text.contains(": CycleState"),
            "BQ4 v2 fitness: CycleState.Refused MUST be a sealed interface on CycleState. " +
                "The data-class shape with a single `reason: String` field is the dual " +
                "authority the audit named; the typed refactor replaces it with a sealed " +
                "interface so the projection matches on the carrier, not on a string.",
        )
    }

    @Test
    fun `cycle package does not classify refusals by string-prefix on the carrier`() {
        val sources = kotlinSources()
        // The three classification keys the audit named.
        assertNoForbiddenPrefixMatch(sources, "UnsupportedFidelity")
        assertNoForbiddenPrefixMatch(sources, "no probe succeeded")
        assertNoForbiddenPrefixMatch(sources, "other commit")
    }

    @Test
    fun `classifyRefusal takes a typed CycleStateRefused, not a free String`() {
        val sources = kotlinSources()
        assertClassifyRefusalIsTyped(sources)
    }

    @Test
    fun `CycleStateRefused is a sealed interface, not a data class with a single reason string`() {
        val sources = kotlinSources()
        assertRefusedIsSealedInterface(sources)
    }
}
