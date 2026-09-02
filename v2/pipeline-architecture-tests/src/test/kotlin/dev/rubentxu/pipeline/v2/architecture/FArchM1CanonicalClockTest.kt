package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM1CanonicalClockTest {

    private val domainRelativePath = "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Clock.kt"
    private val adapterRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemClock.kt"

    private val allowedClockDeclarations = mapOf(
        "Clock" to listOf(domainRelativePath),
        "SystemClock" to listOf(adapterRelativePath).sorted(),
    )

    @Test
    fun `pipeline domain owns the canonical clock contract`() {
        val canonicalClock = FitnessPaths.v2Root().resolve(domainRelativePath)

        assertTrue(Files.isRegularFile(canonicalClock), "Missing canonical domain source: $domainRelativePath")

        val canonicalSource = sanitizedSource(canonicalClock)
        assertTrue(
            clockInterfacePattern.containsMatchIn(canonicalSource),
            "Clock.kt must declare `interface Clock` returning java.time.Instant",
        )
        assertTrue(
            nowMethodPattern.containsMatchIn(canonicalSource),
            "Clock.kt must declare `fun now(): Instant`",
        )
        assertTrue(
            canonicalSource.contains("import java.time.Instant"),
            "Clock.kt must depend on java.time.Instant (and only on it, never java.time.Clock)",
        )
    }

    @Test
    fun `V2 clock type declarations match the canonical allowlist exactly`() {
        val declarations = scanClockDeclarations(FitnessPaths.v2Root(), allowedClockDeclarations.keys)
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowedClockDeclarations.mapValues { (_, paths) ->
            paths.map(::normalizedPath).sorted()
        }

        assertTrue(
            actualByName == normalizedAllowlist,
            "Clock/SystemClock declarations must match the exact M1 allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `pipeline domain does not read wall clock time directly`() {
        val domainSource = FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin")
        val forbiddenTokens = setOf("System.currentTimeMillis", "Instant.now", "Clock.systemUTC", "java.time.Clock")

        val findings = scanForTokens(domainSource, forbiddenTokens)

        assertTrue(
            findings.isEmpty(),
            "Domain source must obtain the current instant through the Clock seam only: $findings",
        )
    }

    @Test
    fun `application owns the system clock adapter`() {
        val adapter = FitnessPaths.v2Root().resolve(adapterRelativePath)

        assertTrue(Files.isRegularFile(adapter), "Missing application adapter: $adapterRelativePath")

        val source = sanitizedSource(adapter)
        val systemClockBody = classBody(source, "SystemClock")

        assertTrue(
            systemClockBody != null && Regex("""\bClock\b""").containsMatchIn(systemClockBody.superTypes),
            "SystemClock must implement the Clock interface",
        )

        val nowBody = systemClockBody?.body?.let(::overrideNowBody)
        assertTrue(
            nowBody != null && Regex("""java\.time\.Clock\.systemUTC\s*\(\s*\)\s*\.\s*instant\s*\(\s*\)""")
                .containsMatchIn(nowBody),
            "SystemClock.now() must delegate to java.time.Clock.systemUTC().instant()",
        )
    }

    private val clockInterfacePattern: Regex = Regex(
        """(?m)^\s*interface\s+Clock\s*(?::\s*\w[\w.]*(?:\s*,\s*\w[\w.]*)*\s*)?\{""",
    )

    private val nowMethodPattern: Regex = Regex(
        """(?m)^\s*(?:\@[\w.]+(?:\s*\([^)]*\))?\s*)*fun\s+now\s*\(\s*\)\s*:\s*Instant\b""",
    )

    private fun scanClockDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    clockDeclarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

    private fun clockDeclarationPattern(name: String): Regex = Regex(
        """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
            """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
            """(?:class|interface|object)\s+$name\b""",
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

    private fun classBody(source: String, className: String): ClassBody? {
        val declaration = Regex("""\bclass\s+$className\b([^\{]*)\{""").find(source) ?: return null
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
        return ClassBody(declaration.groupValues[1], source.substring(openingBrace + 1, index - 1))
    }

    private fun overrideNowBody(classBody: String): String? {
        val declaration = Regex(
            """\boverride\s+fun\s+now\s*\(\s*\)\s*(?::\s*Instant\b\s*)?(=|\{)""",
        ).find(classBody) ?: return null
        val bodyStart = declaration.range.last
        if (declaration.groupValues[1] == "=") {
            return classBody.substring(bodyStart + 1, classBody.indexOf('\n', bodyStart).let {
                if (it == -1) classBody.length else it
            })
        }

        var depth = 1
        var index = bodyStart + 1
        while (index < classBody.length && depth > 0) {
            when (classBody[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        if (depth != 0) return null
        return classBody.substring(bodyStart + 1, index - 1)
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

    private data class ClassBody(val superTypes: String, val body: String)

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, RAW_STRING, STRING, CHAR }
}
