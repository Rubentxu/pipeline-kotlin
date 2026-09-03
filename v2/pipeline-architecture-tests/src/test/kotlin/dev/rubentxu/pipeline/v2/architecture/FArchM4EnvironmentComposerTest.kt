package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * M4 fitness pin — environment composer (LF-0406) + streaming redactor (LF-0405).
 *
 * Pins the single-home rule for the canonical environment composer introduced by
 * M4 Slice 2 (binding amendment 3) and the carve-out for the inherited-
 * ProcessBuilder sandbox pre-merge helpers (applyDenyList / normalizePath),
 * which by design remain in EnvModel.kt until a separate sandbox refactor ships.
 *
 * ARCH-M4-ENV-002 pins the single home for EnvironmentComposer + EnvCompositionRequest.
 * ARCH-M4-ENV-003 pins the single home for StreamingRedactor.
 * ARCH-M4-ENV-004 pins the carve-out helpers to EnvModel.kt.
 * ARCH-M4-ENV-005 (binding amendment 3) pins the composer's import set:
 *   NO imports under dev.rubentxu.pipeline.v2.credentials.* and NO import
 *   of dev.rubentxu.pipeline.v2.domain.EnvValue in EnvironmentComposer.kt.
 *
 * Slice 1's FArchM4CanonicalCredentialBindingTest continues to assert the
 * binding model pin unmodified.
 */
class FArchM4EnvironmentComposerTest {

    // ---------------------------------------------------------------------------
    // Path constants (relative to v2 root)
    // ---------------------------------------------------------------------------

    private val composerRelativePath =
        "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/EnvironmentComposer.kt"
    private val envModelRelativePath =
        "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/EnvModel.kt"
    private val envValueRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/EnvValue.kt"
    private val redactorRelativePath =
        "pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt"

    // ---------------------------------------------------------------------------
    // ARCH-M4-ENV-002: EnvironmentComposer + EnvCompositionRequest single home
    // ---------------------------------------------------------------------------

    @Test
    fun `EnvironmentComposer and EnvCompositionRequest match the exact allowlist (ARCH-M4-ENV-002)`() {
        val declarations = scanCanonicalDeclarations(
            FitnessPaths.v2Root(),
            setOf("EnvironmentComposer", "EnvCompositionRequest"),
        )
        val actualByName = declarations.groupBy(Finding::token).mapValues { (_, fs) ->
            fs.map { FitnessPaths.v2Root().relativize(it.file).toString().replace('\\', '/') }.sorted()
        }
        val expected = mapOf(
            "EnvironmentComposer" to listOf(composerRelativePath),
            "EnvCompositionRequest" to listOf(composerRelativePath),
        )
        assertEquals(expected, actualByName, "Composer / request home must match allowlist")
    }

    // ---------------------------------------------------------------------------
    // ARCH-M4-ENV-003: StreamingRedactor single home
    // ---------------------------------------------------------------------------

    @Test
    fun `StreamingRedactor is declared exactly once in pipeline-credentials-api (ARCH-M4-ENV-003)`() {
        val declarations = scanCanonicalDeclarations(
            FitnessPaths.v2Root(),
            setOf("StreamingRedactor"),
        )
        val actual = declarations.map {
            FitnessPaths.v2Root().relativize(it.file).toString().replace('\\', '/')
        }.sorted()
        assertEquals(listOf(redactorRelativePath), actual)
    }

    // ---------------------------------------------------------------------------
    // ARCH-M4-ENV-004: applyDenyList + normalizePath pinned to EnvModel.kt
    // ---------------------------------------------------------------------------

    @Test
    fun `applyDenyList and normalizePath are pinned to EnvModel-kt (ARCH-M4-ENV-004 carve-out)`() {
        val denyListFindings = scanFunctions(
            FitnessPaths.v2Root(),
            setOf("applyDenyList"),
        )
        val normalizePathFindings = scanFunctions(
            FitnessPaths.v2Root(),
            setOf("normalizePath"),
        )
        val expected = listOf(envModelRelativePath)
        assertEquals(
            expected,
            denyListFindings.map {
                FitnessPaths.v2Root().relativize(it.file).toString().replace('\\', '/')
            }.distinct().sorted(),
            "applyDenyList must be declared exactly in EnvModel.kt (carve-out)",
        )
        assertEquals(
            expected,
            normalizePathFindings.map {
                FitnessPaths.v2Root().relativize(it.file).toString().replace('\\', '/')
            }.distinct().sorted(),
            "normalizePath must be declared exactly in EnvModel.kt (carve-out)",
        )
    }

    // ---------------------------------------------------------------------------
    // ARCH-M4-ENV-005 (binding amendment 3): import-set census
    // ---------------------------------------------------------------------------

    @Test
    fun `EnvironmentComposer has no credentials imports and no EnvValue import (ARCH-M4-ENV-005, binding amendment 3)`() {
        val composerFile = FitnessPaths.v2Root().resolve(composerRelativePath)
        val src = sanitizedSource(composerFile)

        // (a) NO imports under dev.rubentxu.pipeline.v2.credentials.*
        val credentialsImports = Regex(
            """(?m)^\s*import\s+dev\.rubentxu\.pipeline\.v2\.credentials\.[\w.]+\s*$"""
        ).findAll(src).map { it.value.trim() }.toList()
        assertEquals(
            emptyList<String>(),
            credentialsImports,
            "EnvironmentComposer.kt must NOT import anything under " +
                "dev.rubentxu.pipeline.v2.credentials.* (binding amendment 3). " +
                "Found: $credentialsImports",
        )

        // (b) NO import of dev.rubentxu.pipeline.v2.domain.EnvValue
        val envValueImports = Regex(
            """(?m)^\s*import\s+dev\.rubentxu\.pipeline\.v2\.domain\.EnvValue\s*$"""
        ).findAll(src).map { it.value.trim() }.toList()
        assertEquals(
            emptyList<String>(),
            envValueImports,
            "EnvironmentComposer.kt must NOT import dev.rubentxu.pipeline.v2.domain.EnvValue " +
                "(binding amendment 3 — EnvValue is POSTPONED from the M4 execution path). " +
                "Found: $envValueImports",
        )
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

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

    /** Scans for top-level and extension receiver functions (fun [ReceiverType.]name). */
    private fun scanFunctions(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    functionDeclarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

    private fun functionDeclarationPattern(name: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|abstract\s+|suspend\s+|operator\s+|infix\s+)*""" +
            """fun\s+(?:[\w.<>,\s]+\.)?$name\b"""
    )

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
