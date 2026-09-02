package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM2CanonicalPipelineCompilerTest {

    private val definitionRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/PipelineDefinition.kt"
    private val edgeRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/Edge.kt"
    private val compilerRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/PipelineCompiler.kt"
    private val mapCompilerRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/MapPipelineCompiler.kt"
    private val simpleCompilerRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SimplePipelineCompiler.kt"

    /** Exact allowlist of files where each canonical symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = mapOf(
        "PipelineDefinition" to listOf(definitionRelativePath),
        "Edge" to listOf(edgeRelativePath),
        "EdgeKind" to listOf(edgeRelativePath),
        "Stage" to listOf(definitionRelativePath),
        "PipelineCompiler" to listOf(compilerRelativePath),
        "CompileResult" to listOf(compilerRelativePath),
        "PipelineDiagnostic" to listOf(compilerRelativePath),
        "MapPipelineCompiler" to listOf(mapCompilerRelativePath),
        "SimplePipelineCompiler" to listOf(simpleCompilerRelativePath).sorted(),
    )

    @Test
    fun `pipeline domain owns the canonical PipelineDefinition with the widened contract`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(definitionRelativePath))

        assertTrue(
            classPattern("PipelineDefinition", "Any").containsMatchIn(source),
            "PipelineDefinition must be declared as a data class",
        )
        listOf("val id:", "val name:", "val version:", "val steps:", "val edges:", "val stages:").forEach {
            assertTrue(
                source.contains(it),
                "PipelineDefinition must declare field `$it` (LF-0202 widened contract)",
            )
        }
        // The id field must be typed as DefinitionId, not a raw String. This
        // is the canonical-authority pin from LF-0101 closing the loop on
        // LF-0202 — the entity identified by DefinitionId is the same entity
        // produced by the compiler.
        assertTrue(
            Regex("""val\s+id\s*:\s*DefinitionId\b""").containsMatchIn(source),
            "PipelineDefinition.id must be typed as DefinitionId (LF-0101 contract)",
        )
    }

    @Test
    fun `Edge and EdgeKind live in domain as forward declarations for LF-0207 and LF-0307`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(edgeRelativePath))

        assertTrue(
            classPattern("Edge", "Any").containsMatchIn(source),
            "Edge must be declared as a data class",
        )
        assertTrue(
            Regex("""enum\s+class\s+EdgeKind\b""").containsMatchIn(source),
            "EdgeKind must be an enum class",
        )
        listOf("SEQUENTIAL", "PARALLEL", "CONDITIONAL").forEach {
            assertTrue(
                Regex("""\b$it\b""").containsMatchIn(source),
                "EdgeKind must declare entry `$it`",
            )
        }
    }

    @Test
    fun `PipelineCompiler port lives in domain with sealed CompileResult and PipelineDiagnostic`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(compilerRelativePath))

        assertTrue(
            interfacePattern("PipelineCompiler").containsMatchIn(source),
            "PipelineCompiler must be declared as a `fun interface` or `interface`",
        )
        assertTrue(
            Regex("""sealed\s+interface\s+CompileResult\b""").containsMatchIn(source),
            "CompileResult must be a sealed interface",
        )
        assertTrue(
            classPattern("PipelineDiagnostic", "Any").containsMatchIn(source),
            "PipelineDiagnostic must be a data class",
        )
        // CompileResult must declare Success and Failure variants — these
        // are the two outcomes every compiler on the M2 surface produces.
        listOf("Success", "Failure").forEach { variant ->
            assertTrue(
                Regex("""data\s+class\s+$variant\b""").containsMatchIn(source),
                "CompileResult must declare data class variant `$variant`",
            )
        }
    }

    @Test
    fun `MapPipelineCompiler lives in domain as a test-friendly deterministic adapter`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(mapCompilerRelativePath))

        assertTrue(
            classPattern("MapPipelineCompiler", "PipelineCompiler").containsMatchIn(source),
            "MapPipelineCompiler must implement PipelineCompiler",
        )
        // Determinism property: the adapter must NOT do I/O or call system
        // services. Pin the negative so a future "convenience" port does
        // not accidentally introduce clock or filesystem reads here.
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "MapPipelineCompiler must not call $token; determinism is the entire reason this adapter exists",
            )
        }
    }

    @Test
    fun `SimplePipelineCompiler lives in application and is the line-based adapter`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(simpleCompilerRelativePath))

        assertTrue(
            classPattern("SimplePipelineCompiler", "PipelineCompiler").containsMatchIn(source),
            "SimplePipelineCompiler must implement PipelineCompiler",
        )
        // Application adapters are allowed to do I/O. The compiler reads a
        // source string but never opens files directly — that responsibility
        // belongs to the CLI in Main.kt. Pin that delegation explicitly.
        assertFalse(
            source.contains("Files.") || source.contains("Paths.get"),
            "SimplePipelineCompiler must not perform file I/O; the CLI opens the source file",
        )
    }

    @Test
    fun `canonical symbols match the M2 allowlist exactly`() {
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
            "M2 canonical compiler symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `domain does not declare concrete PipelineCompiler implementations beyond MapPipelineCompiler`() {
        // Application is where adapters live. Domain owns the port + the
        // deterministic MapPipelineCompiler; any other concrete compiler
        // declared in domain would re-introduce the M1-era "two sources of
        // truth" pattern that LF-0201 is meant to fix.
        val domainSources = Files.walk(FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin"))
            .use { stream -> stream.filter { it.toString().endsWith(".kt") }.toList() }
        val offenders = domainSources.flatMap { file ->
            sanitizedSource(file).lineSequence().withIndex().filter { (_, line) ->
                classPattern("PipelineCompiler", "PipelineCompiler").containsMatchIn(line) &&
                    !file.toString().replace('\\', '/').endsWith("MapPipelineCompiler.kt")
            }.map { (index, line) -> Finding(file, index + 1, "PipelineCompiler", line) }.toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Domain must declare only MapPipelineCompiler as a concrete PipelineCompiler; offenders: $offenders",
        )
    }

    private fun interfacePattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*(?:fun\s+)?interface\s+$name\b""")

    private fun classPattern(name: String, superType: String): Regex {
        // If the class has no explicit supertype, the body opens with `{`
        // immediately after the parameter list; if it does, the parameter
        // list is followed by `: <superType>` (possibly multiline) before
        // the body. Match either shape.
        return Regex(
            """(?ms)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
                """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
                """class\s+$name\b[\s\S]*?(?::\s*[\s\S]*?\b$superType\b\s*)?\{""",
        )
    }

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
