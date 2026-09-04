package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM1CanonicalIdsTest {

    private val domainRelativePath = "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/PipelineIds.kt"
    private val legacyRunIdRelativePath =
        "pipeline-artefacts-local/src/main/kotlin/dev/rubentxu/pipeline/v2/artefacts/local/LocalArtifactStore.kt"
    private val adapterRelativePath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/UuidRunIdGenerator.kt"

    private val allowedIdDeclarations = mapOf(
        "DefinitionId" to listOf(domainRelativePath),
        // M1 legacy quarantine: remove this exact entry when LocalArtifactStore adopts the domain RunId.
        "RunId" to listOf(domainRelativePath, legacyRunIdRelativePath).sorted(),
        "StageId" to listOf(domainRelativePath),
        "StepId" to listOf(domainRelativePath),
        "PluginStepId" to listOf(domainRelativePath),
        "AttemptId" to listOf(domainRelativePath),
        "OperationId" to listOf(domainRelativePath),
    )

    private val canonicalValueTypes = mapOf(
        "DefinitionId" to "String",
        "RunId" to "String",
        "StageId" to "String",
        "StepId" to "String",
        "PluginStepId" to "String",
        "AttemptId" to "Int",
        "OperationId" to "String",
    )

    @Test
    fun `pipeline domain owns the canonical id contracts`() {
        val canonicalIds = FitnessPaths.v2Root().resolve(domainRelativePath)

        assertTrue(Files.isRegularFile(canonicalIds), "Missing canonical domain source: $domainRelativePath")

        val canonicalSource = sanitizedSource(canonicalIds)
        val missingDeclarations = (canonicalValueTypes.keys + "RunIdGenerator")
            .filterNot { name -> declarationPattern(name).containsMatchIn(canonicalSource) }

        assertTrue(
            missingDeclarations.isEmpty(),
            "PipelineIds.kt must declare the canonical id contracts; missing: $missingDeclarations",
        )

        canonicalValueTypes.forEach { (name, valueType) ->
            assertTrue(
                inlineValueIdPattern(name, valueType).containsMatchIn(canonicalSource),
                "PipelineIds.kt must declare @JvmInline value class $name(val value: $valueType)",
            )
        }
    }

    @Test
    fun `V2 id type declarations match the canonical and legacy allowlist exactly`() {
        val declarations = scanIdDeclarations(FitnessPaths.v2Root(), allowedIdDeclarations.keys)
        val actualByName = declarations.groupBy(Finding::token)
            .mapValues { (_, findings) ->
                findings.map { finding ->
                    normalizedPath(FitnessPaths.v2Root().relativize(finding.file))
                }.sorted()
            }
        val normalizedAllowlist = allowedIdDeclarations.mapValues { (_, paths) ->
            paths.map(::normalizedPath).sorted()
        }

        assertTrue(
            actualByName == normalizedAllowlist,
            "Canonical ID declarations must match the exact LFC1 allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `pipeline domain does not generate random ids or read wall clock time`() {
        val domainSource = FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin")
        val forbiddenTokens = setOf("UUID.randomUUID", "Instant.now", "System.currentTimeMillis")

        val findings = scanForTokens(domainSource, forbiddenTokens)

        assertTrue(
            findings.isEmpty(),
            "Domain source must obtain identity and time through explicit seams: $findings",
        )
    }

    @Test
    fun `application owns the UUID run id generator adapter`() {
        val adapter = FitnessPaths.v2Root().resolve(adapterRelativePath)

        assertTrue(Files.isRegularFile(adapter), "Missing application adapter: $adapterRelativePath")

        val source = sanitizedSource(adapter)
        val generatorBody = classBody(source, "UuidRunIdGenerator")
        val nextBody = generatorBody?.body?.let(::overrideNextBody)
        assertTrue(
            generatorBody != null &&
                Regex("""\bRunIdGenerator\b""").containsMatchIn(generatorBody.superTypes) &&
                nextBody != null &&
                Regex("""\bUUID\s*\.\s*randomUUID\s*\(\s*\)""").containsMatchIn(nextBody) &&
                Regex("""\bRunId\s*\(""").containsMatchIn(nextBody),
            "UuidRunIdGenerator must implement RunIdGenerator and construct RunId from UUID.randomUUID() " +
                "in its override fun next() body",
        )
    }

    private fun declarationPattern(name: String): Regex =
        Regex(
            """(?m)^\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*""" +
                """(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|fun)\s+)*""" +
                """(?:class|interface|typealias)\s+$name\b""",
        )

    private fun normalizedPath(path: Path): String = normalizedPath(path.toString())

    private fun normalizedPath(path: String): String = path.replace('\\', '/')

    private fun inlineValueIdPattern(name: String, valueType: String): Regex = Regex(
        """(?m)^\s*@JvmInline\s+(?:@[\w.]+(?:\s*\([^)]*\))?\s+)*value\s+class\s+$name\s*\(\s*val\s+value\s*:\s*$valueType\s*\)""",
    )

    private fun scanIdDeclarations(root: Path, names: Set<String>): List<Finding> =
        FitnessPaths.walkKotlinFiles(root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .flatMap { file ->
                val source = sanitizedSource(file)
                names.flatMap { name ->
                    declarationPattern(name).findAll(source).map { match ->
                        val lineNumber = source.take(match.range.first).count { it == '\n' } + 1
                        Finding(file, lineNumber, name, match.value.trim())
                    }.toList()
                }
            }

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

    private fun overrideNextBody(classBody: String): String? {
        val declaration = Regex(
            """\boverride\s+fun\s+next\s*\(\s*\)\s*(?::\s*RunId\b\s*)?(=|\{)""",
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
