package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM1CanonicalEffectsTest {

    private val domainEffectRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Effect.kt"
    private val domainReplayPolicyRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt"
    private val legacyQuarantineEffectRelativePath =
        "pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/Effect.kt"
    private val legacyQuarantineReplayPolicyRelativePath =
        "pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/ReplayPolicy.kt"

    private val allowedEffectDeclarations = mapOf(
        "Effect" to listOf(domainEffectRelativePath, legacyQuarantineEffectRelativePath).sorted(),
    )

    private val allowedReplayPolicyDeclarations = mapOf(
        "ReplayPolicy" to listOf(domainReplayPolicyRelativePath, legacyQuarantineReplayPolicyRelativePath).sorted(),
    )

    @Test
    fun `pipeline domain owns the canonical Effect and ReplayPolicy contracts`() {
        val effectPath = FitnessPaths.v2Root().resolve(domainEffectRelativePath)
        val replayPolicyPath = FitnessPaths.v2Root().resolve(domainReplayPolicyRelativePath)

        assertTrue(Files.isRegularFile(effectPath), "Missing canonical Effect: $domainEffectRelativePath")
        assertTrue(Files.isRegularFile(replayPolicyPath), "Missing canonical ReplayPolicy: $domainReplayPolicyRelativePath")

        val effectSource = sanitizedSource(effectPath)
        assertTrue(
            enumClassPattern("Effect").containsMatchIn(effectSource),
            "Effect.kt must declare `enum class Effect`",
        )
        listOf("READ_ONLY", "EXECUTES_SUBPROCESS", "ABORTS_PIPELINE", "WRITES_WORKSPACE").forEach { value ->
            assertTrue(
                effectSource.contains(value),
                "Effect.kt must declare the canonical value $value",
            )
        }

        val replayPolicySource = sanitizedSource(replayPolicyPath)
        assertTrue(
            enumClassPattern("ReplayPolicy").containsMatchIn(replayPolicySource),
            "ReplayPolicy.kt must declare `enum class ReplayPolicy`",
        )
        listOf("MEMOIZED", "RERUN", "NEVER").forEach { value ->
            assertTrue(
                replayPolicySource.contains(value),
                "ReplayPolicy.kt must declare the canonical value $value",
            )
        }
    }

    @Test
    fun `Effect declarations match the canonical and legacy-quarantine allowlist exactly`() {
        assertEnumAllowlist("Effect", allowedEffectDeclarations)
    }

    @Test
    fun `ReplayPolicy declarations match the canonical and legacy-quarantine allowlist exactly`() {
        assertEnumAllowlist("ReplayPolicy", allowedReplayPolicyDeclarations)
    }

    @Test
    fun `legacy sdk api duplicates remain quarantined until M3-R2 consolidation`() {
        // The sdk:api duplicates are part of the explicit allowlist. Their
        // presence in src/main/kotlin/ is intentional and tracked by
        // ADR-0063 §D2. This test is the human-readable assertion of that
        // quarantine so a future contributor cannot accidentally remove the
        // sdk:api duplicates before M3-R2 (which would break every consumer
        // that still imports sdk.Effect / sdk.ReplayPolicy).
        val quarantineEffect = FitnessPaths.v2Root().resolve(legacyQuarantineEffectRelativePath)
        val quarantineReplayPolicy = FitnessPaths.v2Root().resolve(legacyQuarantineReplayPolicyRelativePath)

        assertTrue(
            Files.isRegularFile(quarantineEffect),
            "Legacy quarantine copy of Effect must remain in sdk:api until M3-R2 consolidates " +
                "(ADR-0063 §D2 / §D5): $legacyQuarantineEffectRelativePath",
        )
        assertTrue(
            Files.isRegularFile(quarantineReplayPolicy),
            "Legacy quarantine copy of ReplayPolicy must remain in sdk:api until M3-R2 consolidates " +
                "(ADR-0063 §D2 / §D5): $legacyQuarantineReplayPolicyRelativePath",
        )

        val quarantineEffectSource = sanitizedSource(quarantineEffect)
        assertTrue(
            enumClassPattern("Effect").containsMatchIn(quarantineEffectSource),
            "Legacy sdk:api Effect duplicate must still declare `enum class Effect`",
        )

        val quarantineReplayPolicySource = sanitizedSource(quarantineReplayPolicy)
        assertTrue(
            enumClassPattern("ReplayPolicy").containsMatchIn(quarantineReplayPolicySource),
            "Legacy sdk:api ReplayPolicy duplicate must still declare `enum class ReplayPolicy`",
        )
    }

    private fun assertEnumAllowlist(name: String, allowlist: Map<String, List<String>>) {
        val declarations = scanEnumDeclarations(FitnessPaths.v2Root(), setOf(name))
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowlist.mapValues { (_, paths) -> paths.map(::normalizedPath).sorted() }

        assertTrue(
            actualByName == normalizedAllowlist,
            "$name declarations must match the exact M1 allowlist (canonical + legacy quarantine). " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations. " +
                "See ADR-0063 §D2 (quarantine) and §D5 (M3-R2 consolidation plan).",
        )
    }

    private fun enumClassPattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*enum\s+class\s+$name\b""")

    private fun scanEnumDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    enumClassPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
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

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, RAW_STRING, STRING, CHAR }
}
