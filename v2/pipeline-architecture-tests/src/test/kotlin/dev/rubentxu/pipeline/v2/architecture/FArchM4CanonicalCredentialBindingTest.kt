package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * M4 fitness pin — canonical credential binding model (LF-0401..LF-0404).
 *
 * Pins the single-home rule for the typed binding model introduced by the
 * CREDENTIAL_ENV_WORKSPACE_SPEC: the sealed [CredentialBindingSpec] hierarchy
 * lives in `:pipeline-domain`, the projection port is pure domain, the DSL
 * converts inward via `toSpec()`, and `withCredentials` has exactly one
 * execution path (the executor's `bind`, never an inline run-loop branch).
 */
class FArchM4CanonicalCredentialBindingTest {

    private val bindingSpecRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialBindingSpec.kt"
    private val projectionRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialProjection.kt"
    private val materializationRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialMaterialization.kt"
    private val dslRelativePath =
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt"
    private val executorRelativePath =
        "pipeline-credentials-executor/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/executor/WithCredentialsExecutor.kt"
    private val runRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt"

    private val bindingSubtypes = listOf(
        "StringBindingSpec",
        "UsernamePasswordBindingSpec",
        "SshUserPrivateKeyBindingSpec",
        "FileBindingSpec",
        "CertificateBindingSpec",
        "ZipBindingSpec",
        "UsernameColonPasswordBindingSpec",
    )

    /** Exact allowlist of files where each canonical M4 symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = buildMap {
        put("CredentialBindingSpec", listOf(bindingSpecRelativePath))
        bindingSubtypes.forEach { subtype ->
            put(subtype, listOf(bindingSpecRelativePath))
        }
        put("ProjectionResult", listOf(projectionRelativePath))
        put("CredentialProjector", listOf(projectionRelativePath))
        put("DefaultCredentialProjector", listOf(projectionRelativePath))
        put("CredentialMaterializationDomain", listOf(materializationRelativePath))
        put("MaterializedCredentialDomain", listOf(materializationRelativePath))
    }

    @Test
    fun `CredentialBindingSpec is the sealed canonical binding model with 7 Jenkins-verbatim subtypes`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(bindingSpecRelativePath))

        assertTrue(
            Regex("""sealed\s+interface\s+CredentialBindingSpec\b""").containsMatchIn(source),
            "CredentialBindingSpec must be a sealed interface (closed binding set, INV-L6-CR-001)",
        )
        bindingSubtypes.forEach { subtype ->
            assertTrue(
                Regex("""data\s+class\s+$subtype\b""").containsMatchIn(source),
                "CredentialBindingSpec must declare data class subtype `$subtype`",
            )
        }
        // The kind is a static type, not a runtime tag: each subtype carries a
        // kind literal while credentialsId stays on the shared interface.
        assertTrue(
            Regex("""val\s+credentialsId\s*:\s*CredentialsId""").containsMatchIn(source),
            "CredentialBindingSpec must carry typed CredentialsId (no raw strings)",
        )
    }

    @Test
    fun `credential projection is a pure domain port consumed by a default projector`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(projectionRelativePath))

        assertTrue(
            Regex("""fun\s+interface\s+CredentialProjector\b""").containsMatchIn(source),
            "CredentialProjector must be a fun interface in domain (LF-0403)",
        )
        assertTrue(
            Regex("""class\s+DefaultCredentialProjector\b""").containsMatchIn(source),
            "DefaultCredentialProjector must be declared in domain",
        )
        assertTrue(
            Regex("""data\s+class\s+ProjectionResult\b""").containsMatchIn(source),
            "ProjectionResult must be a data class",
        )
        // Env crosses the boundary typed — Map<String, SecretHandle>, never raw strings.
        assertTrue(
            Regex("""Map<String,\s*SecretHandle>""").containsMatchIn(source),
            "ProjectionResult.bindings must use typed SecretHandle values",
        )
        // Pure domain: the projector composes against the materialization port;
        // it must never touch processes, the environment, or the filesystem.
        listOf("ProcessBuilder", "System.getenv", "Files.").forEach { token ->
            assertFalse(
                source.contains(token),
                "CredentialProjection must be pure domain — no `$token` (I/O belongs to the materialization port)",
            )
        }
    }

    @Test
    fun `DSL converts inward via toSpec and the executor consumes domain types`() {
        val dslSource = sanitizedSource(FitnessPaths.v2Root().resolve(dslRelativePath))
        val executorSource = sanitizedSource(FitnessPaths.v2Root().resolve(executorRelativePath))

        assertTrue(
            dslSource.contains("fun StepSpec.CredentialsBinding.toSpec()"),
            "The DSL binding must expose toSpec() as the inward converter (LF-0401)",
        )
        assertTrue(
            dslSource.contains("domain.credentials.CredentialBindingSpec"),
            "toSpec() must return the domain CredentialBindingSpec (DSL depends on domain, never the reverse)",
        )
        assertTrue(
            executorSource.contains("domain.credentials.CredentialBindingSpec"),
            "WithCredentialsExecutor must consume the domain binding type (inverted dependency, LF-0404)",
        )
        assertTrue(
            executorSource.contains("DefaultCredentialProjector"),
            "WithCredentialsExecutor must project through DefaultCredentialProjector",
        )
    }

    @Test
    fun `PipelineRun delegates withCredentials to the executor (single code path, no inline projection)`() {
        val runSource = sanitizedSource(FitnessPaths.v2Root().resolve(runRelativePath))

        assertTrue(
            runSource.contains("withCredentialsExecutor.bind("),
            "PipelineRun must route withCredentials through the executor's bind (LF-0404 single path)",
        )
        // The deleted inline carbon-copy branch must not reappear: projection
        // code (and its helper site) lives only in the executor path.
        assertFalse(
            runSource.contains("DefaultCredentialProjector"),
            "PipelineRun must not inline credential projection — bind() via the executor is the only path",
        )
    }

    @Test
    fun `M4 canonical credential symbols match the exact allowlist`() {
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
            "M4 canonical credential symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
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
