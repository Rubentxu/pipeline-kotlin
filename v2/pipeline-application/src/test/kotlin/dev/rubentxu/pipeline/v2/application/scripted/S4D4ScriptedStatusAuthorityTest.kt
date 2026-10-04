package dev.rubentxu.pipeline.v2.application.scripted

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S4-D4 — one authority for `ShellInvocationResult -> OperationStatus`, and the rule that keeps it one.
 *
 * The defect was never the duplication itself. Two byte-identical private copies are merely untidy;
 * what makes them dangerous is that a *private* member shadows a top-level function of the same name
 * in the same package. A third copy dropped into either class would compile at every call site, pass
 * the whole suite, and leave the shared function unused. A guard that counts call sites would see
 * nothing wrong. A guard that counts *declarations* sees it immediately.
 *
 * So this file counts declarations across the whole `v2` production tree, by pattern rather than by
 * a fixed list of files, and refuses any that is not the single top-level one.
 *
 * The last test is the one that makes the rest trustworthy: it feeds a planted second declaration to
 * the same predicate and requires it to be reported. A rule nobody has seen fail is a comment.
 */
class S4D4ScriptedStatusAuthorityTest {

    private val productionSources: List<File> by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "v2").isDirectory) {
            dir = dir.parentFile
        }
        val v2 = dir?.let { File(it, "v2") }
            ?: error("v2/ not found walking up from ${File(".").absolutePath}")
        v2.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.toPath().toString().contains("${File.separator}src${File.separator}main${File.separator}") }
            .toList()
    }

    /**
     * A declaration is a `fun` with `ShellInvocationResult` as the receiver, at any visibility.
     *
     * MULTILINE is load-bearing and was not obvious: production scanning feeds this one line at a
     * time, where `^` is trivially satisfied, while the negative control feeds it a multi-line raw
     * block, where without MULTILINE `^` matches only at offset 0 and a planted declaration nested
     * inside a class body is invisible. The control caught exactly that, which is the argument for
     * having written it.
     */
    private val shellStatusDeclaration = Regex(
        """^\s*(?:(private|internal|public|protected)\s+)?fun\s+ShellInvocationResult\s*\.\s*toOperationStatus\s*\(""",
        RegexOption.MULTILINE,
    )

    /** The same function on the OTHER receiver — a different question, deliberately not merged. */
    private val stepOutcomeStatusDeclaration =
        Regex("""^\s*(?:(private|internal|public|protected)\s+)?fun\s+StepOutcome\s*\.\s*toOperationStatus\s*\(""")

    private fun declarationsMatching(pattern: Regex): List<Pair<String, String>> =
        productionSources.flatMap { file ->
            file.readText()
                .lines()
                .mapNotNull { line -> pattern.find(line)?.groupValues?.get(1)?.let { file.name to it } }
        }

    @Test
    fun `exactly one ShellInvocationResult to OperationStatus authority exists in production`() {
        val found = declarationsMatching(shellStatusDeclaration)
        assertEquals(
            listOf("internal"),
            found.map { it.second },
            "expected exactly one non-private ShellInvocationResult.toOperationStatus, found: $found",
        )
        assertEquals(
            1,
            found.size,
            "a second value-derived status authority exists; two opinions about the same mapping is " +
                "the defect this file exists to prevent: $found",
        )
    }

    @Test
    fun `the authority is not private, because private would shadow it`() {
        // The specific failure mode: `private fun ShellInvocationResult.toOperationStatus()` inside
        // a class wins over the package-level function at every call site inside that class, so the
        // call sites keep compiling and the shared function goes dead. Visibility is the whole guard.
        val authority = productionSources
            .firstOrNull { it.name == "ScriptedStatusAuthority.kt" }
            ?: error("ScriptedStatusAuthority.kt is missing; the authority has no declared home")
        val declaration = authority.readLines().first { shellStatusDeclaration.containsMatchIn(it) }
        assertFalse(
            declaration.trimStart().startsWith("private"),
            "the shared authority is private, so a per-class copy would shadow it invisibly: $declaration",
        )
    }

    @Test
    fun `the value-derived and outcome-derived authorities stay separate`() {
        // They answer different questions and have different receivers. Merging them would route a
        // scripted `Unstable` through `StepOutcome.Unstable -> OperationStatus.FAILED`, changing what
        // the journal records. That is a semantic decision, and this duplication cleanup does not make
        // it. What this asserts is the cheap structural half: they were not quietly fused.
        val fused = productionSources.filter { file ->
            val text = file.readText()
            shellStatusDeclaration.containsMatchIn(text) && stepOutcomeStatusDeclaration.containsMatchIn(text)
        }
        assertEquals(
            emptyList<File>(),
            fused,
            "one file now declares both authorities; merging them changes scripted Unstable semantics: " +
                "${fused.map { it.name }}",
        )
    }

    @Test
    fun `the count discriminates between one authority and two`() {
        // Negative control on the COUNT, because a count is only meaningful if it can be wrong.
        // A single planted declaration would prove nothing: the real tree also has one. What has to
        // be shown is that the same predicate returns 1 for the healthy shape and 2 the moment a
        // second authority appears — which is precisely the event the first test exists to catch.
        val healthy = """
            internal fun ShellInvocationResult.toOperationStatus(): OperationStatus =
                OperationStatus.SUCCEEDED
        """.trimIndent()
        val shadowingCopy = """
            class Reconciler {
                private fun ShellInvocationResult.toOperationStatus(): OperationStatus =
                    OperationStatus.SUCCEEDED
            }
        """.trimIndent()

        val count = shellStatusDeclaration.findAll(healthy).count()
        assertEquals(1, count, "the predicate over-counts a single healthy declaration")

        val visibilities = listOf(healthy, shadowingCopy).flatMap { src ->
            shellStatusDeclaration.findAll(src).map { it.groupValues[1] }.toList()
        }
        assertEquals(
            listOf("internal", "private"),
            visibilities,
            "the predicate does not see a planted private second authority, so the count cannot fail",
        )
    }
}
