package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * XCA-LAW-001 / A1 — anti-bypass architecture fitness.
 *
 * The INNER layer (`pipeline-domain`) defines the execution-evidence contracts
 * ([dev.rubentxu.pipeline.v2.domain.durable.RunExecutionEvidenceReader]) and MUST NOT
 * depend on any concrete durable backend. The single consumption path is:
 *
 *     upper layers -> RunExecutionEvidenceReader -> OperationJournal -> implementation
 *
 * `pipeline-domain` is the innermost module (it declares no project dependencies), so if
 * it cannot name a journal backend, no upper layer can reach one *through* it. Upper-layer
 * consumers are added to this law as they appear.
 *
 * This scans REAL STRUCTURE (import statements in .kt sources), never comment text.
 * A doc comment mentioning `OperationJournal` is fine; an import is a violation.
 */
@Timeout(30)
class ExecutionEvidenceBypassFitnessTest {

    /** Resolve robustly: Gradle sets the working dir to the module, but local runs may
     *  use the repo root. Never silently scan nothing. */
    private val domainMain: Path = listOf(
        Path.of("src/main"),
        Path.of("v2/pipeline-domain/src/main"),
        Path.of("pipeline-domain/src/main"),
    ).firstOrNull { Files.exists(it) }
        ?: error("could not locate pipeline-domain/src/main from ${Path.of("").toAbsolutePath()}")

    /** Forbidden in the inner layer: the durable port, concrete adapters, SQL/JDBC. */
    private val banned: List<Pair<String, String>> = listOf(
        "dev.rubentxu.pipeline.v2.events." to
            "OperationJournal port / event persistence (LAW-001: reached only via the reader)",
        "java.sql." to "raw SQL/JDBC (LAW-001: journals are reached only via the port)",
        "javax.sql." to "raw SQL/JDBC (LAW-001)",
        "org.sqlite" to "concrete SQLite journal backend (LAW-001)",
        "FileBasedRetryControlJournal" to "retry control journal (orchestration, not evidence)",
        "FileBasedWaitUntilControlJournal" to "waitUntil control journal (orchestration, not evidence)",
        "RetryControlJournal" to "retry control journal dependency",
        "WaitUntilControlJournal" to "waitUntil control journal dependency",
    )

    @Test
    fun `inner layer must not depend on a concrete durable backend (LAW-001)`() {
        val violations = mutableListOf<String>()
        Files.walk(domainMain).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.forEach { file ->
                Files.readAllLines(file).forEachIndexed { i, line ->
                    val t = line.trim()
                    // Only real import statements count; comments and prose never do.
                    if (!t.startsWith("import ")) return@forEachIndexed
                    banned.forEach { (needle, why) ->
                        if (t.contains(needle)) {
                            violations += "${file}:${i + 1}  $t   <-- $why"
                        }
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw AssertionError(
                buildString {
                    appendLine("EXECUTION-EVIDENCE BYPASS DETECTED — LAW-001 violated:")
                    appendLine()
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("The inner layer must depend only on the abstract port")
                    appendLine("RunExecutionEvidenceReader. Concrete journal backends belong in")
                    appendLine("an adapter module (e.g. JournalRunExecutionEvidenceReader in")
                    appendLine("pipeline-events). See docs/v2/07-uat/XCA2A_JOURNAL_CHARACTERIZATION.md")
                },
            )
        }
    }
}
