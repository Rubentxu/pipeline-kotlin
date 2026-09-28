package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    private fun sanitizedSource(file: Path): String =
        KotlinSourceSanitizer.sanitizedSource(file)

}
