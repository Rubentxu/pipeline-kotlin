package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM2CanonicalRunCoordinatorTest {

    private val dispatcherRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDispatcher.kt"
    private val recordingDispatcherRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RecordingStepDispatcher.kt"
    private val plannerRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ExecutionPlanner.kt"
    private val coordinatorRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RunCoordinator.kt"
    private val inMemoryCoordinatorRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/InMemoryRunCoordinator.kt"

    /** Exact allowlist of files where each canonical symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = mapOf(
        "StepDispatcher" to listOf(dispatcherRelativePath),
        "StepExecutionContext" to listOf(dispatcherRelativePath),
        "RecordingStepDispatcher" to listOf(recordingDispatcherRelativePath),
        "ExecutionPlanner" to listOf(plannerRelativePath),
        "RunCoordinator" to listOf(coordinatorRelativePath),
        "RunRequest" to listOf(coordinatorRelativePath),
        "InMemoryRunCoordinator" to listOf(inMemoryCoordinatorRelativePath),
    )

    @Test
    fun `StepDispatcher port and its execution context live in domain`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(dispatcherRelativePath))

        assertTrue(
            interfacePattern("StepDispatcher").containsMatchIn(source),
            "StepDispatcher must be declared as a `fun interface` or `interface`",
        )
        assertTrue(
            classPattern("StepExecutionContext").containsMatchIn(source),
            "StepExecutionContext must be a data class in the same file",
        )
        assertTrue(
            source.contains("val runId: RunId"),
            "StepExecutionContext must carry the typed RunId (M1-001 chain)",
        )
        assertTrue(
            source.contains("val attempt: Int"),
            "StepExecutionContext must carry the retry attempt counter",
        )
    }

    @Test
    fun `RecordingStepDispatcher is the deterministic test adapter and performs no IO`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(recordingDispatcherRelativePath))

        assertTrue(
            classPattern("RecordingStepDispatcher", "StepDispatcher").containsMatchIn(source),
            "RecordingStepDispatcher must implement StepDispatcher",
        )
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get", "ProcessBuilder").forEach { token ->
            assertFalse(
                source.contains(token),
                "RecordingStepDispatcher must not call $token; determinism is the entire reason this adapter exists",
            )
        }
    }

    @Test
    fun `ExecutionPlanner is pure and fails closed on cycles and unknown references`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(plannerRelativePath))

        assertTrue(
            objectPattern("ExecutionPlanner").containsMatchIn(source),
            "ExecutionPlanner must be declared as an object (pure, stateless)",
        )
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "ExecutionPlanner must not call $token; it must stay a pure function of the definition",
            )
        }
        // Fail-closed structure: the planner signals definition violations
        // with IllegalArgumentException (unknown edge references, cycles,
        // and contradictory PARALLEL edges). The behavioural assertions
        // live in ExecutionPlannerTest; this pin only guards the mechanism.
        assertTrue(
            Regex("""IllegalArgumentException""").containsMatchIn(source),
            "ExecutionPlanner must throw IllegalArgumentException for definition violations",
        )
        assertEquals(
            4,
            Regex("""throw\s+IllegalArgumentException""").findAll(source).count(),
            "ExecutionPlanner must have exactly four fail-closed throw sites: unknown edge source, " +
                "unknown edge target, cycle, and contradictory PARALLEL edge",
        )
    }

    @Test
    fun `RunCoordinator port returns the canonical RunOutcome via RunRequest`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(coordinatorRelativePath))

        assertTrue(
            interfacePattern("RunCoordinator").containsMatchIn(source),
            "RunCoordinator must be declared as an interface",
        )
        assertTrue(
            Regex("""(?m)^\s*data\s+class\s+RunRequest\b""").containsMatchIn(source),
            "RunRequest must be a data class in the same file",
        )
        // The single-method contract: outcomes only, no raw strings, no
        // exceptions as step-failure signals.
        assertTrue(
            Regex("""fun\s+run\s*\(\s*request\s*:\s*RunRequest\s*\)\s*:\s*RunOutcome\b""").containsMatchIn(source),
            "RunCoordinator.run must return the typed RunOutcome (LF-0104 chain)",
        )
        assertTrue(
            source.contains("resumeAfter"),
            "RunRequest must carry the resumeAfter cursor",
        )
    }

    @Test
    fun `InMemoryRunCoordinator is the reference adapter and reduces through RunOutcomeReducer`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(inMemoryCoordinatorRelativePath))

        assertTrue(
            classPattern("InMemoryRunCoordinator", "RunCoordinator").containsMatchIn(source),
            "InMemoryRunCoordinator must implement RunCoordinator",
        )
        // The canonical-authority chain: outcomes MUST be produced by the
        // single reducer (LF-0104), never fabricated inline.
        assertTrue(
            source.contains("RunOutcomeReducer.reduce"),
            "InMemoryRunCoordinator must fold step outcomes via RunOutcomeReducer.reduce (single authority)",
        )
        assertTrue(
            source.contains("ExecutionPlanner.plan"),
            "InMemoryRunCoordinator must derive execution units via ExecutionPlanner.plan",
        )
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "InMemoryRunCoordinator must not call $token; it is the deterministic reference adapter",
            )
        }
    }

    @Test
    fun `canonical symbols match the M2 run-coordinator allowlist exactly`() {
        val declarations = scanCanonicalDeclarations(
            FitnessPaths.v2Root(),
            allowedDeclarations.keys,
        )
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowedDeclarations.mapValues { (_, paths) ->
            paths.map(::normalizedPath).sorted()
        }

        assertEquals(
            normalizedAllowlist,
            actualByName,
            "M2 canonical run-coordinator symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `domain declares only InMemoryRunCoordinator as a concrete RunCoordinator implementation`() {
        // The production durable coordinator (LF-0205) belongs to
        // :pipeline-application. Any additional concrete RunCoordinator in
        // domain would re-introduce the two-sources-of-truth pattern that
        // the M2 milestone exists to remove.
        val domainRoot = FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin")
        val offenders = Files.walk(domainRoot)
            .use { stream -> stream.filter { it.toString().endsWith(".kt") }.toList() }
            .flatMap { file ->
                sanitizedSource(file).lineSequence().withIndex().filter { (_, line) ->
                    Regex("""(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+)?class\s+\w+\b[^\{]*:\s*[^\{]*\bRunCoordinator\b""")
                        .containsMatchIn(line) &&
                        !file.toString().replace('\\', '/').endsWith("InMemoryRunCoordinator.kt")
                }.map { (index, line) -> Finding(file, index + 1, "RunCoordinator impl", line) }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "Domain must declare only InMemoryRunCoordinator as a concrete RunCoordinator; offenders: $offenders",
        )
    }

    private fun interfacePattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*(?:fun\s+)?interface\s+$name\b""")

    private fun classPattern(name: String, superType: String? = null): Regex {
        // The supertype clause is optional: `class X(...) {` has no explicit
        // supertype while `class X(...) : Y {` does. Match either shape.
        val superTypeClause = if (superType == null) "" else """[\s\S]*?:\s*[\s\S]*?\b$superType\b"""
        return Regex(
            """(?ms)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
                """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
                """class\s+$name\b[\s\S]*?$superTypeClause\s*\{""",
        )
    }

    private fun objectPattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+)?object\s+$name\b""")

    private fun scanCanonicalDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    canonicalDeclarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

    private fun canonicalDeclarationPattern(name: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
            """(?:class|interface|object|typealias)\s+$name\b""",
    )

    private fun normalizedPath(path: Path): String = normalizedPath(path.toString())

    private fun normalizedPath(path: String): String = path.replace('\\', '/')

    /** Replaces comments and literals with spaces while preserving newlines and source offsets. */
    private fun sanitizedSource(file: Path): String =
        KotlinSourceSanitizer.sanitizedSource(file)

}
