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
    private val allowedEffectDeclarations = mapOf(
        "Effect" to listOf(domainEffectRelativePath),
    )

    private val allowedReplayPolicyDeclarations = mapOf(
        "ReplayPolicy" to listOf(domainReplayPolicyRelativePath),
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
    fun `Effect declarations have one canonical authority`() {
        assertEnumAllowlist("Effect", allowedEffectDeclarations)
    }

    @Test
    fun `ReplayPolicy declarations have one canonical authority`() {
        assertEnumAllowlist("ReplayPolicy", allowedReplayPolicyDeclarations)
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
            "$name declarations must match the one-authority LFC1 allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations. " +
                "See ADR-0064 and LFC1-003.",
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
    private fun sanitizedSource(file: Path): String =
        KotlinSourceSanitizer.sanitizedSource(file)

}
