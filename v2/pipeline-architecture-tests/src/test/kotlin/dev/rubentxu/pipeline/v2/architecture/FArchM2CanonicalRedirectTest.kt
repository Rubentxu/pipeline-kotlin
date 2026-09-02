package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM2CanonicalRedirectTest {

    private val legacyMapperRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/LegacyOutcomeMapper.kt"
    private val coordinatorRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DurableRunCoordinator.kt"
    private val registryRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SpecRegistry.kt"
    private val mapperRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SpecDefinitionMapper.kt"
    private val mainRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt"

    /** Exact allowlist of files where each canonical symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = mapOf(
        "LegacyOutcomeMapper" to listOf(legacyMapperRelativePath),
        "DurableRunCoordinator" to listOf(coordinatorRelativePath),
        "DurableRunDelegate" to listOf(coordinatorRelativePath),
        "SpecRegistry" to listOf(registryRelativePath),
        "SpecDefinitionMapper" to listOf(mapperRelativePath).sorted(),
    )

    @Test
    fun `LegacyOutcomeMapper lives in domain as the single stable string boundary`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(legacyMapperRelativePath))

        assertTrue(
            objectPattern("LegacyOutcomeMapper").containsMatchIn(source),
            "LegacyOutcomeMapper must be declared as an object (pure, stateless)",
        )
        // Single-authority pin: the durable coordinator must cross the
        // legacy string boundary through this mapper (M2-005 failure
        // mapping estable). A second ad-hoc string→outcome mapping site
        // would defeat the contract.
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "LegacyOutcomeMapper must not call $token; it is a pure mapping",
            )
        }
    }

    @Test
    fun `DurableRunCoordinator implements the RunCoordinator port in application`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(coordinatorRelativePath))

        assertTrue(
            classPattern("DurableRunCoordinator", "RunCoordinator").containsMatchIn(source),
            "DurableRunCoordinator must implement the domain RunCoordinator port",
        )
        // The redirect's single-boundary guarantee: the coordinator crosses
        // the legacy string boundary ONLY through LegacyOutcomeMapper.
        assertTrue(
            source.contains("LegacyOutcomeMapper.toRunOutcome"),
            "DurableRunCoordinator must map legacy strings via LegacyOutcomeMapper.toRunOutcome",
        )
        // resumeAfter must fail closed on the durable surface until LF-0206.
        assertTrue(
            source.contains("resumeAfter"),
            "DurableRunCoordinator must explicitly reject resumeAfter until LF-0206",
        )
        // The delegate seam keeps the coordinator decoupled from the
        // concrete orchestrator class.
        assertTrue(
            interfacePattern("DurableRunDelegate").containsMatchIn(source),
            "DurableRunDelegate seam must be declared in the same file",
        )
    }

    @Test
    fun `Main reaches the durable runtime only through the RunCoordinator port`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(mainRelativePath))

        assertTrue(
            source.contains("DurableRunCoordinator("),
            "Main must construct DurableRunCoordinator (LF-0205 redirect)",
        )
        // The forbidden direct call: the CLI must never invoke the legacy
        // orchestrator entry point directly — every execution flows through
        // the port. This is the pin that makes M2-006 ('no alternate runner
        // reachable') progressively enforceable.
        assertFalse(
            Regex("""orchestrator\.run\s*\(""").containsMatchIn(source),
            "Main must NOT call orchestrator.run directly; route through DurableRunCoordinator",
        )
        assertTrue(
            source.contains("RunRequest("),
            "Main must build a typed RunRequest for the coordinator call",
        )
    }

    @Test
    fun `redirect symbols match the allowlist exactly`() {
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
            "M2 redirect symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    private fun interfacePattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*(?:fun\s+)?interface\s+$name\b""")

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
