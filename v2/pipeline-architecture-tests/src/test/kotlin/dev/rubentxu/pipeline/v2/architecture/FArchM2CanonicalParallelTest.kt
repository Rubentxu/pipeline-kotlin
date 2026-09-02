package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM2CanonicalParallelTest {

    private val executionUnitRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ExecutionUnit.kt"
    private val plannerRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ExecutionPlanner.kt"
    private val concurrentDispatcherRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ConcurrentStepDispatcher.kt"

    /** Exact allowlist of files where each canonical symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = mapOf(
        "ExecutionUnit" to listOf(executionUnitRelativePath),
        "ExecutionPlan" to listOf(executionUnitRelativePath),
        "ExecutionPlanner" to listOf(plannerRelativePath),
        "ConcurrentStepDispatcher" to listOf(concurrentDispatcherRelativePath).sorted(),
    )

    @Test
    fun `ExecutionUnit and ExecutionPlan live in domain as the canonical plan vocabulary`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(executionUnitRelativePath))

        assertTrue(
            Regex("""sealed\s+interface\s+ExecutionUnit\b""").containsMatchIn(source),
            "ExecutionUnit must be a sealed interface (closed set: Single, Concurrent)",
        )
        listOf("Single", "Concurrent").forEach { variant ->
            assertTrue(
                Regex("""data\s+class\s+$variant\b""").containsMatchIn(source),
                "ExecutionUnit must declare data class variant `$variant`",
            )
        }
        assertTrue(
            classPattern("ExecutionPlan").containsMatchIn(source),
            "ExecutionPlan must be a data class in the same file",
        )
        // A concurrent wave needs at least two steps; one step is a Single.
        // (Pinned on code, not on the message — sanitized source masks
        // string literals.)
        assertTrue(
            Regex("""steps\.size\s*>=\s*2""").containsMatchIn(source),
            "ExecutionUnit.Concurrent must reject single-step waves at construction time",
        )
    }

    @Test
    fun `ExecutionPlanner gives PARALLEL edges real semantics - no ordering, same-wave assertion`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(plannerRelativePath))

        assertTrue(
            objectPattern("ExecutionPlanner").containsMatchIn(source),
            "ExecutionPlanner must be declared as an object (pure, stateless)",
        )
        // PARALLEL edges must NOT feed the ordering graph — they are
        // same-wave assertions. Pin the filter explicitly.
        assertTrue(
            Regex("""kind\s*!=\s*EdgeKind\.PARALLEL""").containsMatchIn(source),
            "ExecutionPlanner must exclude PARALLEL edges from the ordering graph",
        )
        // Contradiction check pinned on code, not on the message (sanitized
        // source masks string literals).
        assertTrue(
            Regex("""fromWave\s*!=\s*toWave""").containsMatchIn(source),
            "ExecutionPlanner must fail closed on PARALLEL edges across different waves",
        )
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "ExecutionPlanner must not call $token; it is a pure function of the definition",
            )
        }
    }

    @Test
    fun `ConcurrentStepDispatcher reuses the SAME dispatcher instance as the serial path`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(concurrentDispatcherRelativePath))

        // The single-dispatcher property (M2-004): the wave dispatcher is a
        // decorator over the injected StepDispatcher, never a second
        // execution path.
        assertTrue(
            Regex("""private\s+val\s+\w+\s*:\s*StepDispatcher""").containsMatchIn(source),
            "ConcurrentStepDispatcher must wrap the injected StepDispatcher (the same instance the serial path uses)",
        )
        assertTrue(
            Regex("""ExecutorService""").containsMatchIn(source),
            "ConcurrentStepDispatcher must take the executor from the caller (JDK, framework-free)",
        )
        // Determinism: results are collected in declaration order, not
        // completion order, so the reducer folds deterministically.
        assertTrue(
            classPattern("ConcurrentStepDispatcher").containsMatchIn(source),
            "ConcurrentStepDispatcher must be a class",
        )
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "ConcurrentStepDispatcher must not call $token; dispatch is its only job",
            )
        }
    }

    @Test
    fun `parallel symbols match the allowlist exactly`() {
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
            "M2 canonical parallel symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    private fun classPattern(name: String, superType: String? = null): Regex {
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
    private fun sanitizedSource(file: Path): String {
        val source = Files.readString(file)
        val result = StringBuilder(source.length)
        var index = 0
        var blockCommentDepth = 0
        var state = LexicalState.CODE

        fun mask(character: Char) = if (character == '\n' || character == '\r') character else ' '

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                LexicalState.CODE -> when {
                    current == '/' && next == '/' -> {
                        result.append("  ")
                        index += 2
                        state = LexicalState.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        result.append("  ")
                        index += 2
                        blockCommentDepth = 1
                        state = LexicalState.BLOCK_COMMENT
                    }
                    source.startsWith("\"\"\"", index) -> {
                        result.append("   ")
                        index += 3
                        state = LexicalState.RAW_STRING
                    }
                    current == '"' -> {
                        result.append(' ')
                        index++
                        state = LexicalState.STRING
                    }
                    current == '\'' -> {
                        result.append(' ')
                        index++
                        state = LexicalState.CHAR
                    }
                    else -> {
                        result.append(current)
                        index++
                    }
                }
                LexicalState.LINE_COMMENT -> {
                    result.append(mask(current))
                    index++
                    if (current == '\n') state = LexicalState.CODE
                }
                LexicalState.BLOCK_COMMENT -> when {
                    current == '/' && next == '*' -> {
                        result.append("  ")
                        index += 2
                        blockCommentDepth++
                    }
                    current == '*' && next == '/' -> {
                        result.append("  ")
                        index += 2
                        blockCommentDepth--
                        if (blockCommentDepth == 0) state = LexicalState.CODE
                    }
                    else -> {
                        result.append(mask(current))
                        index++
                    }
                }
                LexicalState.RAW_STRING -> if (source.startsWith("\"\"\"", index)) {
                    result.append("   ")
                    index += 3
                    state = LexicalState.CODE
                } else {
                    result.append(mask(current))
                    index++
                }
                LexicalState.STRING, LexicalState.CHAR -> {
                    val closing = if (state == LexicalState.STRING) '"' else '\''
                    result.append(mask(current))
                    index++
                    if (current == '\\' && index < source.length) {
                        result.append(mask(source[index]))
                        index++
                    } else if (current == closing) {
                        state = LexicalState.CODE
                    }
                }
            }
        }
        return result.toString()
    }

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, RAW_STRING, STRING, CHAR }
}
