package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM1CanonicalOutcomesTest {

    private val stepOutcomeRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepOutcome.kt"
    private val runOutcomeRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RunOutcome.kt"
    private val pipelineFailureRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/PipelineFailure.kt"
    private val reducerRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RunOutcomeReducer.kt"

    private val allowedOutcomeDeclarations = mapOf(
        "StepOutcome" to listOf(stepOutcomeRelativePath),
        "RunOutcome" to listOf(runOutcomeRelativePath),
        "PipelineFailure" to listOf(pipelineFailureRelativePath),
        "RunOutcomeReducer" to listOf(reducerRelativePath),
    )

    @Test
    fun `pipeline domain owns the canonical outcome contracts`() {
        val allowedPaths = allowedOutcomeDeclarations.values.flatten()
        val missing = allowedPaths
            .map { FitnessPaths.v2Root().resolve(it) }
            .filterNot { Files.isRegularFile(it) }

        assertTrue(missing.isEmpty(), "Missing canonical domain sources: $missing")

        val stepSource = sanitizedSource(FitnessPaths.v2Root().resolve(stepOutcomeRelativePath))
        assertTrue(
            sealedInterfacePattern("StepOutcome").containsMatchIn(stepSource),
            "StepOutcome.kt must declare `sealed interface StepOutcome`",
        )
        assertTrue(
            stepSource.contains("data object Success"),
            "StepOutcome.kt must declare `data object Success : StepOutcome`",
        )
        assertTrue(
            stepSource.contains("data object Unstable"),
            "StepOutcome.kt must declare `data object Unstable : StepOutcome`",
        )
        assertTrue(
            dataClassPattern("Failure", "PipelineFailure").containsMatchIn(stepSource),
            "StepOutcome.kt must declare `data class Failure(val failure: PipelineFailure) : StepOutcome`",
        )

        val runSource = sanitizedSource(FitnessPaths.v2Root().resolve(runOutcomeRelativePath))
        assertTrue(
            sealedInterfacePattern("RunOutcome").containsMatchIn(runSource),
            "RunOutcome.kt must declare `sealed interface RunOutcome`",
        )
        listOf("Success", "Unstable", "Aborted").forEach { dataObject ->
            assertTrue(
                runSource.contains("data object $dataObject"),
                "RunOutcome.kt must declare `data object $dataObject : RunOutcome`",
            )
        }
        assertTrue(
            dataClassPattern("Failure", "PipelineFailure").containsMatchIn(runSource),
            "RunOutcome.kt must declare `data class Failure(val failure: PipelineFailure) : RunOutcome`",
        )

        val failureSource = sanitizedSource(FitnessPaths.v2Root().resolve(pipelineFailureRelativePath))
        assertTrue(
            dataClassPattern("PipelineFailure", "FailureKind").containsMatchIn(failureSource),
            "PipelineFailure.kt must declare `data class PipelineFailure(val kind: FailureKind, ...)`",
        )

        val reducerSource = sanitizedSource(FitnessPaths.v2Root().resolve(reducerRelativePath))
        assertTrue(
            objectPattern("RunOutcomeReducer").containsMatchIn(reducerSource),
            "RunOutcomeReducer.kt must declare `object RunOutcomeReducer`",
        )
        assertTrue(
            reducerSource.contains("fun reduce("),
            "RunOutcomeReducer must expose `fun reduce(...)`",
        )
    }

    @Test
    fun `V2 outcome type declarations match the canonical allowlist exactly`() {
        val declarations = scanOutcomeDeclarations(
            FitnessPaths.v2Root(),
            allowedOutcomeDeclarations.keys,
        )
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowedOutcomeDeclarations.mapValues { (_, paths) ->
            paths.map(::normalizedPath).sorted()
        }

        assertTrue(
            actualByName == normalizedAllowlist,
            "StepOutcome/RunOutcome/PipelineFailure/RunOutcomeReducer declarations must match the exact M1 " +
                "allowlist. Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `domain declares the run outcome reducer with the pure reduce function`() {
        val reducerSource = sanitizedSource(FitnessPaths.v2Root().resolve(reducerRelativePath))
        val reducerBody = objectBody(reducerSource, "RunOutcomeReducer")

        assertTrue(
            reducerBody != null && reducerBody.body.contains("fun reduce("),
            "RunOutcomeReducer object must expose a `fun reduce(...)` function in its body",
        )
        // The reducer must be deterministic and pure: no I/O, no wall clock,
        // no logging. Forbid obvious impurity tokens inside its body.
        val forbiddenInReducer = setOf("System.currentTimeMillis", "Instant.now", "Clock.systemUTC", "println(", "printStackTrace(")
        val hits = forbiddenInReducer.filter { token -> reducerBody?.body?.contains(token) == true }
        assertTrue(
            hits.isEmpty(),
            "RunOutcomeReducer.reduce must be pure: forbid impurity tokens $hits",
        )
    }

    private fun sealedInterfacePattern(name: String): Regex =
        Regex("""(?m)^\s*sealed\s+interface\s+$name\b""")

    private fun objectPattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*object\s+$name\b""")

    private fun dataClassPattern(name: String, parameterType: String): Regex =
        Regex(
            """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*data\s+class\s+$name\s*\(\s*val\s+\w+\s*:\s*$parameterType\b""",
        )

    private fun scanOutcomeDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    outcomeDeclarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

    private fun outcomeDeclarationPattern(name: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
            """(?:class|interface|object|typealias)\s+$name\b""",
    )

    private fun objectBody(source: String, objectName: String): ObjectBody? {
        val declaration = Regex("""\bobject\s+$objectName\b[^\{]*\{""").find(source) ?: return null
        val openingBrace = declaration.range.last
        var depth = 1
        var index = openingBrace + 1
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        if (depth != 0) return null
        return ObjectBody(source.substring(openingBrace + 1, index - 1))
    }

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

    private data class ObjectBody(val body: String)

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, RAW_STRING, STRING, CHAR }
}
