package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM1CanonicalRuntimeConfigTest {

    private val domainInterfaceRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/RuntimeConfig.kt"
    private val domainAdapterRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/MapRuntimeConfig.kt"
    private val applicationAdapterRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemRuntimeConfig.kt"

    private val allowedRuntimeConfigDeclarations = mapOf(
        "RuntimeConfig" to listOf(domainInterfaceRelativePath),
        "MapRuntimeConfig" to listOf(domainAdapterRelativePath),
        "SystemRuntimeConfig" to listOf(applicationAdapterRelativePath).sorted(),
    )

    @Test
    fun `pipeline domain owns the canonical RuntimeConfig contract`() {
        val path = FitnessPaths.v2Root().resolve(domainInterfaceRelativePath)

        assertTrue(Files.isRegularFile(path), "Missing canonical RuntimeConfig: $domainInterfaceRelativePath")

        val source = sanitizedSource(path)
        assertTrue(
            interfacePattern("RuntimeConfig").containsMatchIn(source),
            "RuntimeConfig.kt must declare `interface RuntimeConfig`",
        )

        listOf("fun env(", "fun property(", "fun osName(").forEach { signature ->
            assertTrue(
                source.contains(signature),
                "RuntimeConfig.kt must declare the canonical method `$signature`",
            )
        }
    }

    @Test
    fun `MapRuntimeConfig adapter lives in domain as a deterministic test-friendly implementation`() {
        val path = FitnessPaths.v2Root().resolve(domainAdapterRelativePath)

        assertTrue(
            Files.isRegularFile(path),
            "Missing MapRuntimeConfig adapter: $domainAdapterRelativePath",
        )

        val source = sanitizedSource(path)
        assertTrue(
            classPattern("MapRuntimeConfig", "RuntimeConfig").containsMatchIn(source),
            "MapRuntimeConfig.kt must declare `class MapRuntimeConfig(...) : RuntimeConfig`",
        )
        // MapRuntimeConfig must NOT call System.getenv / System.getProperty directly —
        // it must read exclusively from the maps supplied at construction. This is
        // the property that makes it deterministic.
        listOf("System.getenv", "System.getProperty").forEach { token ->
            assertTrue(
                !source.contains(token),
                "MapRuntimeConfig must not call $token directly; values come from the constructor-supplied maps",
            )
        }
    }

    @Test
    fun `SystemRuntimeConfig adapter lives in application and is the only System-env reader`() {
        val path = FitnessPaths.v2Root().resolve(applicationAdapterRelativePath)

        assertTrue(
            Files.isRegularFile(path),
            "Missing SystemRuntimeConfig adapter: $applicationAdapterRelativePath",
        )

        val source = sanitizedSource(path)
        assertTrue(
            classPattern("SystemRuntimeConfig", "RuntimeConfig").containsMatchIn(source),
            "SystemRuntimeConfig.kt must declare `class SystemRuntimeConfig() : RuntimeConfig`",
        )
        // The adapter must read System.getenv / System.getProperty — that is its
        // entire purpose. If a future contributor removes the System calls
        // because the linter complains, the production runtime stops reading
        // the OS environment entirely. This assertion makes that regression
        // explicit.
        assertTrue(
            source.contains("System.getenv"),
            "SystemRuntimeConfig.env must delegate to System.getenv",
        )
        assertTrue(
            source.contains("System.getProperty"),
            "SystemRuntimeConfig.property must delegate to System.getProperty",
        )
    }

    @Test
    fun `V2 runtime config declarations match the canonical allowlist exactly`() {
        val declarations = scanRuntimeConfigDeclarations(
            FitnessPaths.v2Root(),
            allowedRuntimeConfigDeclarations.keys,
        )
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowedRuntimeConfigDeclarations.mapValues { (_, paths) ->
            paths.map(::normalizedPath).sorted()
        }

        assertTrue(
            actualByName == normalizedAllowlist,
            "RuntimeConfig/MapRuntimeConfig/SystemRuntimeConfig declarations must match the exact M1 allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `pipeline domain does not read OS environment or JVM system properties directly`() {
        val domainSource = FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin")
        val forbiddenTokens = setOf("System.getenv", "System.getProperty", "System.getenvOrNull")

        val findings = scanForTokens(domainSource, forbiddenTokens)

        assertTrue(
            findings.isEmpty(),
            "Domain source must obtain OS environment and JVM properties through the RuntimeConfig seam only: $findings",
        )
    }

    private fun interfacePattern(name: String): Regex =
        Regex("""(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*interface\s+$name\b""")

    private fun classPattern(name: String, superType: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
            """class\s+$name\b[^\{]*:\s*[^\{]*\b$superType\b""",
    )

    private fun scanRuntimeConfigDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    runtimeConfigDeclarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

    private fun runtimeConfigDeclarationPattern(name: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
            """(?:class|interface|object|typealias)\s+$name\b""",
    )

    private fun normalizedPath(path: Path): String = normalizedPath(path.toString())

    private fun normalizedPath(path: String): String = path.replace('\\', '/')

    private fun scanForTokens(root: Path, tokens: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root).flatMap { file ->
            sanitizedSource(file).lineSequence().flatMapIndexed { index, line ->
                tokens.filter { line.contains(it) }
                    .map { token -> Finding(file, index + 1, token, line) }
            }.toList()
        }

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
