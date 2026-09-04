package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC1-R1 canonical bridge fitness tests.
 *
 * Verifies that the canonical durable execution bridge is correctly wired:
 * - Main.kt does not import legacy bridge symbols (SpecRegistry, SpecDefinitionMapper, DurableRunCoordinator, DurableRunDelegate)
 * - CanonicalCoreStepDecoder is imported in production
 * - LegacyOutcomeMapper remains in domain as the string boundary
 * - No alternate runner is reachable
 */
class FArchLfc1CanonicalBridgeTest {

    private val legacyMapperRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/LegacyOutcomeMapper.kt"
    private val mainRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt"

    /** Legacy symbols that must NOT appear as imports in Main.kt */
    private val forbiddenLegacyImports = listOf(
        "SpecRegistry",
        "SpecDefinitionMapper",
        "DurableRunCoordinator",
        "DurableRunDelegate",
    )

    @Test
    fun `LegacyOutcomeMapper lives in domain as the single stable string boundary`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(legacyMapperRelativePath))

        assertTrue(
            objectPattern("LegacyOutcomeMapper").containsMatchIn(source),
            "LegacyOutcomeMapper must be declared as an object (pure, stateless)",
        )
        // Single-authority pin: the durable coordinator must cross the
        // legacy string boundary through this mapper. A second ad-hoc
        // string→outcome mapping site would defeat the contract.
        listOf("System.getenv", "System.getProperty", "Files.", "Paths.get").forEach { token ->
            assertFalse(
                source.contains(token),
                "LegacyOutcomeMapper must not call $token; it is a pure mapping",
            )
        }
    }

    @Test
    fun `Main does not import legacy bridge symbols`() {
        val source = Files.readString(FitnessPaths.v2Root().resolve(mainRelativePath))

        // Check for import statements of the forbidden legacy symbols
        forbiddenLegacyImports.forEach { symbol ->
            assertFalse(
                Regex("""import\s+.*\b$symbol\b""").containsMatchIn(source),
                "Main.kt must NOT import '$symbol' - legacy bridge symbols must be removed",
            )
        }
    }

    @Test
    fun `Main identity reinterpretation - derived hash is DefinitionId and RunId is fresh or recovered`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(mainRelativePath))

        // The legacy deriveRunId helper must be gone: the deterministic hash
        // survives only as DeterministicIdGenerator.definitionId.
        assertFalse(
            source.contains("deriveRunId("),
            "Main must NOT derive the run id from the script hash; the hash is the DefinitionId",
        )
        // Fresh invocations get a unique RunId from the generator seam.
        assertTrue(
            source.contains("UuidRunIdGenerator"),
            "Main must generate fresh RunIds via UuidRunIdGenerator (RunId unique per invocation)",
        )
        // Resume recovers the PRIOR RunId instead of re-deriving it.
        assertTrue(
            source.contains("RunIdDirectory"),
            "Main must recover the prior RunId from the RunIdDirectory on --resume",
        )
        assertTrue(
            Regex("""DeterministicIdGenerator\.definitionId""").containsMatchIn(source),
            "Main must derive the DefinitionId via DeterministicIdGenerator.definitionId",
        )
    }

    @Test
    fun `RunIdDirectory lives in application as the definition-to-lastrun mapping`() {
        val source = sanitizedSource(
            FitnessPaths.v2Root()
                .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/RunIdDirectory.kt")
        )

        assertTrue(
            classPattern("RunIdDirectory").containsMatchIn(source),
            "RunIdDirectory must be a class in :pipeline-application",
        )
        // Fail-closed contract: a resume without a prior record must be a
        // hard error, never a silent fresh run.
        assertTrue(
            Regex("""IllegalArgumentException""").containsMatchIn(source),
            "RunIdDirectory must fail closed (IllegalArgumentException) on missing or corrupted records",
        )
    }

    @Test
    fun `no alternate runner is reachable after LFC1-R1`() {
        val pipelineRunSource = sanitizedSource(
            FitnessPaths.v2Root()
                .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt")
        )
        val mainSource = sanitizedSource(FitnessPaths.v2Root().resolve(mainRelativePath))

        // The non-durable walker and its entry point are gone. The regex
        // deliberately does NOT match walkPipelineSpecDurable.
        assertFalse(
            Regex("""fun\s+execute\s*\(""").containsMatchIn(pipelineRunSource),
            "PipelineRun must not declare the non-durable execute() entry point",
        )
        assertFalse(
            Regex("""private\s+fun\s+walkPipelineSpec\(""").containsMatchIn(pipelineRunSource),
            "PipelineRun must not declare the non-durable walkPipelineSpec walker",
        )
        assertTrue(
            Regex("""internal\s+suspend\s+fun\s+walkPipelineSpecDurable\(""").containsMatchIn(pipelineRunSource),
            "The durable walker must remain (it IS the single execution algorithm)",
        )
        assertFalse(
            Regex("""\bexecute\s*\(\s*scriptPath""").containsMatchIn(mainSource),
            "Main must not call the deleted non-durable runner",
        )
    }

    @Test
    fun `coordinator path imports the canonical decoder in production`() {
        val decoderImport = "CanonicalCoreStepDecoder"

        val productionSources = FitnessPaths.walkKotlinFiles(
            FitnessPaths.v2Root().resolve("pipeline-application/src/main/kotlin")
        )

        val found = productionSources.any { file ->
            val source = Files.readString(file)
            Regex("""import\s+.*\b$decoderImport\b""").containsMatchIn(source)
        }

        assertTrue(
            found,
            "CanonicalCoreStepDecoder must be imported in v2/pipeline-application/src/main/** - the coordinator decodes through this seam",
        )
    }

    @Test
    fun `production tree has no legacy bridge symbol declarations`() {
        val productionSources = FitnessPaths.walkKotlinFiles(
            FitnessPaths.v2Root()
        ).filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }

        val findings = mutableListOf<Pair<Path, String>>()
        productionSources.forEach { file ->
            val source = Files.readString(file)
            forbiddenLegacyImports.forEach { symbol ->
                // Check for class/object/interface declarations of the forbidden symbols
                if (Regex("""\b(?:class|interface|object|typealias)\s+$symbol\b""").containsMatchIn(source)) {
                    findings.add(file to symbol)
                }
            }
        }

        assertTrue(
            findings.isEmpty(),
            "No legacy bridge symbol declarations must appear in production code. Found: $findings",
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
