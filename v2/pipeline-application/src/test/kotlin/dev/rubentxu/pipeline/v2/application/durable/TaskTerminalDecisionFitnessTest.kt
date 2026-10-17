package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Architecture fitness for the [dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision]
 * decider (BQ3 closure, commit `d2bf51b0`; pipeline-step-sdk extension in
 * commit `306e844f`).
 *
 * ## Why this exists
 *
 * The legacy [dev.rubentxu.pipeline.v2.application.durable.ShExecution] line 731
 *     when { result.timedOut -> Cancelled(...); result.cancelled -> Cancelled(...); else -> Exited(...) }
 * was the only place a consumer of [dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult]
 * read those two booleans. Extracting them into a sealed ADT (`TaskTerminalDecision`) closed
 * that hole, but the fix is local — the booleans are still public on the data class and any
 * future code in any module can re-introduce a flag-bag `when {}` over them and silently
 * regress the cause-of-termination classification (the watchdog kill exitCode=137 was the
 * concrete bug; commit `306e844f` found a parallel defect in `ProcessDurableTaskRuntime`).
 *
 * This test scans every `.kt` file under both `pipeline-application/src/main` and
 * `pipeline-step-sdk/runtime/src/main` and asserts that `.timedOut` / `.cancelled` only appear
 * in the data-class definition itself. Any other occurrence is a regression that must be
 * fixed at the decider, not at the call site.
 *
 * ## What it does NOT cover
 *
 *  - Other pipeline-step-sdk submodules (scm-git, http, junit, utilities, files) and any
 *    future module that consumes TaskExecutionResult; same rule should be enforced there.
 *  - Reflection-based or runtime-introduced access patterns. Pure source scan only.
 */
class TaskTerminalDecisionFitnessTest {

    private val appMainRoot: Path = Paths.get("src/main/kotlin")
    private val sdkRuntimeMainRoot: Path = Paths.get("../pipeline-step-sdk/runtime/src/main/kotlin")

    private fun kotlinSources(): List<Path> {
        // Aggregate both roots so a regression in either module fires the same assertion.
        // Either root missing (e.g. running outside the module directory) is a no-op.
        val roots = listOf(appMainRoot, sdkRuntimeMainRoot).filter { Files.isDirectory(it) }
        require(roots.isNotEmpty()) {
            "no scan roots found (looked for $appMainRoot and $sdkRuntimeMainRoot); " +
                "run from v2/pipeline-application"
        }
        return roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
            }
        }
    }

    /** Strip line comments and block comments so the scan doesn't false-positive on KDoc. */
    private fun stripComments(text: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return noBlock.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    private fun linesReferencing(file: Path, regex: Regex): List<String> =
        stripComments(Files.readString(file)).lines()
            .withIndex()
            .filter { regex.containsMatchIn(it.value) }
            .map { "${file.fileName}:${it.index + 1}: ${it.value.trim()}" }

    private val ownBooleans =
        Regex("""(?:val|var)\s+timedOut\s*:\s*Boolean|(?:val|var)\s+cancelled\s*:\s*Boolean""")

    @Test
    fun `no kotlin source under pipeline-application reads TaskExecutionResult booleans except in TaskTerminalDecision and TaskExecutionResult`() {
        val sources = kotlinSources()
        assertTrue(sources.isNotEmpty(), "no kotlin sources found under $appMainRoot or $sdkRuntimeMainRoot — run from v2/pipeline-application")

        val offending = sources.flatMap { file ->
            val ownDefinition = ownBooleans.containsMatchIn(stripComments(Files.readString(file)))
            val booleanUses = linesReferencing(file, Regex("""\.timedOut|\.cancelled"""))
                .filterNot { ownDefinition && (it.contains("val timedOut") || it.contains("val cancelled")) }
            if (file.fileName.toString() == "TaskTerminalDecision.kt") emptyList()
            else booleanUses
        }

        assertEquals(
            emptyList<String>(),
            offending,
            "TaskExecutionResult.timedOut/cancelled must be consumed only via " +
                "decideTaskTerminal(); direct reads elsewhere are a flag-bag regression.\n" +
                offending.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `ShExecution does not contain a when over result booleans outside the decider call`() {
        val sh = appMainRoot.resolve("dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt")
        if (!Files.isRegularFile(sh)) return // not present in this build flavour; nothing to assert

        val body = stripComments(Files.readString(sh))
        // Permit only the import line and the `when (val decision = decideTaskTerminal(result))` site.
        // A `when {` block with a bare `result.timedOut` or `result.cancelled` is the regression
        // signature this guard is here to detect.
        val booleanWhens = Regex(
            """when\s*\{[^}]*?(?:\bresult\b[^{}]*?(?:\.timedOut|\.cancelled))""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertEquals(
            false,
            booleanWhens.containsMatchIn(body),
            "ShExecution.kt must not contain a `when {}` over result.timedOut/cancelled directly. " +
                "Route it through decideTaskTerminal() so the sealed ADT and isSuccessFromDecision " +
                "remain the single source of truth for task termination classification.",
        )
    }
}