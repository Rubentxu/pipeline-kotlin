package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FArchM3CanonicalTaskRuntimeTest {

    private val taskSpecRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/TaskSpec.kt"
    private val runtimePortRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/DurableTaskRuntime.kt"
    private val recordingRuntimeRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/RecordingDurableTaskRuntime.kt"

    /** Exact allowlist of files where each canonical symbol may be declared. */
    private val allowedDeclarations: Map<String, List<String>> = mapOf(
        "TaskSpec" to listOf(taskSpecRelativePath),
        "InterpreterPolicy" to listOf(taskSpecRelativePath),
        "DurableTaskRuntime" to listOf(runtimePortRelativePath),
        "TaskExecutionRequest" to listOf(runtimePortRelativePath),
        "TaskExecutionResult" to listOf(runtimePortRelativePath),
        "ExecutionOutputSink" to listOf(runtimePortRelativePath),
        "OutputChunk" to listOf(runtimePortRelativePath),
        "TaskStream" to listOf(runtimePortRelativePath),
        "RecordingDurableTaskRuntime" to listOf(recordingRuntimeRelativePath),
        "ProcessDurableTaskRuntime" to listOf(
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/task/ProcessDurableTaskRuntime.kt"
        ).sorted(),
    )

    @Test
    fun `TaskSpec is a closed sealed set with ExecTask argv preservation`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(taskSpecRelativePath))

        assertTrue(
            Regex("""sealed\s+interface\s+TaskSpec\b""").containsMatchIn(source),
            "TaskSpec must be a sealed interface (closed set)",
        )
        listOf("ShellScriptTask", "ExecTask").forEach { variant ->
            assertTrue(
                Regex("""data\s+class\s+$variant\b""").containsMatchIn(source),
                "TaskSpec must declare data class variant `$variant`",
            )
        }
        // ExecTask carries argv — the no-shell execution shape (M3-002).
        assertTrue(
            Regex("""val\s+argv\s*:\s*List<String>""").containsMatchIn(source),
            "ExecTask must carry argv as List<String>",
        )
        // Interpreter binaries are a closed enum, not free-form strings.
        assertTrue(
            Regex("""enum\s+class\s+InterpreterPolicy""").containsMatchIn(source),
            "InterpreterPolicy must be an enum (no arbitrary binary injection)",
        )
    }

    @Test
    fun `DurableTaskRuntime port lives in domain with typed request result and streaming sink`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(runtimePortRelativePath))

        assertTrue(
            interfacePattern("DurableTaskRuntime").containsMatchIn(source),
            "DurableTaskRuntime must be an interface in domain",
        )
        // The port is suspend (spec): execution is asynchronous by contract.
        assertTrue(
            Regex("""suspend\s+fun\s+execute""").containsMatchIn(source),
            "DurableTaskRuntime.execute must be suspend per DURABLE_TASK_RUNTIME_SPEC",
        )
        assertTrue(
            Regex("""data\s+class\s+TaskExecutionRequest\b""").containsMatchIn(source),
            "TaskExecutionRequest must be a data class",
        )
        assertTrue(
            Regex("""class\s+OutputChunk\b""").containsMatchIn(source),
            "OutputChunk must be declared in the same file",
        )
        // O(chunk) memory invariant: bounded chunks cross the sink.
        assertTrue(
            Regex("""val\s+data\s*:\s*ByteArray""").containsMatchIn(source),
            "OutputChunk must carry bounded byte windows",
        )
        // Secrets are typed at the request boundary (redaction before persisting).
        assertTrue(
            Regex("""Map<String,\s*SecretHandle>""").containsMatchIn(source),
            "TaskExecutionRequest.env must use typed SecretHandle values",
        )
        listOf("System.getenv", "System.getProperty", "Files.").forEach { token ->
            assertFalse(
                source.contains(token),
                "The domain port file must not perform I/O or read the environment",
            )
        }
    }

    @Test
    fun `RecordingDurableTaskRuntime is the deterministic test adapter`() {
        val source = sanitizedSource(FitnessPaths.v2Root().resolve(recordingRuntimeRelativePath))

        assertTrue(
            classPattern("RecordingDurableTaskRuntime", "DurableTaskRuntime").containsMatchIn(source),
            "RecordingDurableTaskRuntime must implement DurableTaskRuntime",
        )
        // No processes in the test adapter — ever.
        assertFalse(
            source.contains("ProcessBuilder"),
            "RecordingDurableTaskRuntime must never spawn processes",
        )
    }

    @Test
    fun `ProcessDurableTaskRuntime is the single authorised ProcessBuilder home for the task runtime`() {
        val path = FitnessPaths.v2Root()
            .resolve("pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/task/ProcessDurableTaskRuntime.kt")
        val source = sanitizedSource(path)

        assertTrue(
            classPattern("ProcessDurableTaskRuntime", "DurableTaskRuntime").containsMatchIn(source),
            "ProcessDurableTaskRuntime must implement the domain DurableTaskRuntime port",
        )
        assertTrue(
            source.contains("ProcessBuilder(argv)"),
            "ExecTask must construct the process from argv verbatim (no shell)",
        )
        // O(chunk) invariant: streaming reads only — whole-pipe reads are
        // the memory blow-up the spec prohibits.
        assertFalse(
            source.contains("readText()") || source.contains("readAllBytes()"),
            "ProcessDurableTaskRuntime must not buffer whole pipes (O(chunk) invariant)",
        )
        // Process-tree termination.
        assertTrue(
            source.contains("descendants()"),
            "ProcessDurableTaskRuntime must kill the whole process tree (LF-0304)",
        )
        // Atomic durable result.
        assertTrue(
            source.contains("ATOMIC_MOVE"),
            "Durable result must be written atomically",
        )
    }

    @Test
    fun `task runtime symbols match the M3 allowlist exactly`() {
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
            "M3 canonical task-runtime symbols must match the exact allowlist. " +
                "Expected: $normalizedAllowlist; actual: $actualByName; findings: $declarations",
        )
    }

    @Test
    fun `domain declares only RecordingDurableTaskRuntime as a concrete DurableTaskRuntime`() {
        // The production process adapter (LF-0302+) belongs to the runtime
        // module; any additional concrete runtime in domain would reopen the
        // two-sources-of-truth pattern.
        val domainRoot = FitnessPaths.v2Root().resolve("pipeline-domain/src/main/kotlin")
        val offenders = Files.walk(domainRoot)
            .use { stream -> stream.filter { it.toString().endsWith(".kt") }.toList() }
            .flatMap { file ->
                sanitizedSource(file).lineSequence().withIndex().filter { (_, line) ->
                    Regex("""class\s+\w+\b[^\{]*:\s*[^\{]*\bDurableTaskRuntime\b""")
                        .containsMatchIn(line) &&
                        !file.toString().replace('\\', '/').endsWith("RecordingDurableTaskRuntime.kt")
                }.map { (index, line) -> Finding(file, index + 1, "DurableTaskRuntime impl", line) }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "Domain must declare only RecordingDurableTaskRuntime as a concrete DurableTaskRuntime; offenders: $offenders",
        )
    }

    @Test
    fun `LF-0305 LF-0306 LF-0307 LF-0308 migrate git tar sh and SDK sh onto the task runtime (no ProcessBuilder outside the runtime)`() {
        val v2Root = FitnessPaths.v2Root()

        // The five call-site families migrated by LF-0305 (scm-git),
        // LF-0306 (artefacts-local) and LF-0307 (ShExecution non-durable
        // fallback). They MUST NOT use ProcessBuilder; they MUST route
        // through the runtime.
        val migratedFiles = listOf(
            "pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCheckoutExecutor.kt",
            "pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitPollExecutor.kt",
            "pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitChangelogWriter.kt",
            "pipeline-artefacts-local/src/main/kotlin/dev/rubentxu/pipeline/v2/artefacts/local/TarWriter.kt",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt",
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/StepExecutors.kt",
        )
        for (relative in migratedFiles) {
            val path = v2Root.resolve(relative)
            val source = sanitizedSource(path)
            assertFalse(
                source.contains("ProcessBuilder("),
                "$relative must not construct processes directly — use the runtime (LF-0305/0306/0307)",
            )
            assertTrue(
                source.contains("ProcessDurableTaskRuntime") || source.contains("runCaptured") ||
                    source.contains("DurableTaskRuntime") || source.contains("TaskSpec"),
                "$relative must reference the task runtime after LF-0305/0306/0307",
            )
        }

        // LF-0308 deletion: ProcessExecutor and ShellResult are gone. Any
        // surviving reference is a regression — the legacy PB wrapper must
        // not reappear.
        val legacyRefs = listOf(
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/ProcessExecutor.kt",
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/ShellResult.kt",
            "pipeline-step-sdk/runtime/src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/ProcessExecutorTest.kt",
        )
        for (relative in legacyRefs) {
            assertFalse(
                Files.exists(v2Root.resolve(relative)),
                "$relative must not exist after LF-0308",
            )
        }

        // Global census across the whole v2 tree, EXCLUDING the runtime
        // module's main sources (ProcessDurableTaskRuntime is the only
        // authorised home): zero ProcessBuilder( call sites. This is the
        // LF-0309 single-home gate condition, now enforced globally.
        val runtimeMainRoot = v2Root.resolve("pipeline-step-sdk/runtime/src/main/kotlin").toAbsolutePath()
        val allKtFiles = FitnessPaths.walkKotlinFiles(v2Root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .filter { file -> !file.toAbsolutePath().startsWith(runtimeMainRoot) }
            .toList()

        val offenders = allKtFiles
            .flatMap { file ->
                sanitizedSource(file).lineSequence().withIndex().filter { (_, line) ->
                    line.contains("ProcessBuilder(")
                }.map { (i, line) -> Finding(file, i + 1, "ProcessBuilder call", line) }.toList()
            }
        assertTrue(
            offenders.isEmpty(),
            "ProcessBuilder must appear ONLY inside the runtime module (ProcessDurableTaskRuntime home); offenders: $offenders",
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
