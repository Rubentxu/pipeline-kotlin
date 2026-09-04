package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC1-007 fitness test: legacy DSL step kinds are deprecated and pre-compiler-rewritten.
 *
 * Verifies the following invariants after the LFC1-007 migration:
 * - StepSpec.{CatchError,WarnError,Unstable} are marked @Deprecated in PipelineDsl.kt
 * - DslCompiledPipelineCompiler has rewrite rules for all three kinds
 * - PipelineRun.kt executeDurableStepImpl has error-throwing stubs (not live execution branches)
 * - stepClassifications no longer has entries for the three kinds
 * - The canonical IR knows about core.file.writeFile and core.emit.event
 */
class FArchLfc1LegacyDslRemovedTest {

    private val pipelineDslPath = FitnessPaths.v2Root()
        .resolve("pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt")
    private val compilerPath = FitnessPaths.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt")
    private val pipelineRunPath = FitnessPaths.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt")

    @Test
    fun `StepSpec CatchError WarnError Unstable are marked deprecated`() {
        val source = Files.readString(pipelineDslPath)

        listOf("CatchError", "WarnError", "Unstable").forEach { kind ->
            assertTrue(
                Regex("""@Deprecated[\s\S]*?LFC1-007[\s\S]*?data class $kind\b""").containsMatchIn(source),
                "StepSpec.$kind must be marked @Deprecated with LFC1-007 message",
            )
        }
    }

    @Test
    fun `DslCompiledPipelineCompiler rewrites CatchError to emit-event`() {
        val source = Files.readString(compilerPath)

        assertTrue(
            source.contains("rewriteWorkflowControl"),
            "Compiler must have rewriteWorkflowControl function for catchError/warnError",
        )
        assertTrue(
            Regex("""is\s+StepSpec\.CatchError""").containsMatchIn(source),
            "Compiler must have 'is StepSpec.CatchError' branch in stepNode()",
        )
    }

    @Test
    fun `DslCompiledPipelineCompiler rewrites WarnError to emit-event`() {
        val source = Files.readString(compilerPath)

        assertTrue(
            Regex("""is\s+StepSpec\.WarnError""").containsMatchIn(source),
            "Compiler must have 'is StepSpec.WarnError' branch in stepNode()",
        )
    }

    @Test
    fun `DslCompiledPipelineCompiler rewrites Unstable to emit-event plus exit-0`() {
        val source = Files.readString(compilerPath)

        assertTrue(
            Regex("""is\s+StepSpec\.Unstable""").containsMatchIn(source),
            "Compiler must have 'is StepSpec.Unstable' branch in stepNode()",
        )
        assertTrue(
            source.contains("rewriteUnstable"),
            "Compiler must have rewriteUnstable function",
        )
    }

    @Test
    fun `DslCompiledPipelineCompiler writes core file writeFile`() {
        val source = Files.readString(compilerPath)

        assertTrue(
            Regex("""is\s+StepSpec\.WriteFile""").containsMatchIn(source),
            "Compiler must have 'is StepSpec.WriteFile' branch",
        )
        assertTrue(
            source.contains("core.file.writeFile"),
            "Compiler must emit core.file.writeFile plugin step ID",
        )
    }

    @Test
    fun `DslCompiledPipelineCompiler emits core emit event`() {
        val source = Files.readString(compilerPath)

        assertTrue(
            source.contains("core.emit.event"),
            "Compiler must emit core.emit.event plugin step ID",
        )
        assertTrue(
            source.contains("emitStep"),
            "Compiler must have emitStep helper for event emission",
        )
    }

    @Test
    fun `PipelineRun executeDurableStepImpl stubs throw for legacy workflow-control kinds`() {
        val source = Files.readString(pipelineRunPath)

        // The combined when branch should throw an error if reached
        assertTrue(
            Regex("""is\s+StepSpec\.CatchError""").containsMatchIn(source),
            "PipelineRun must still reference StepSpec.CatchError in when (for exhaustiveness)",
        )
        assertTrue(
            source.contains("should have been pre-compiler-rewritten"),
            "PipelineRun when branch must throw error indicating pre-compiler rewrite is expected",
        )
    }

    @Test
    fun `stepClassifications no longer maps CatchError WarnError Unstable`() {
        val source = sanitizedSource(pipelineRunPath)

        // Extract the stepClassifications function content
        val funStart = source.indexOf("fun stepClassifications")
        val funEnd = source.indexOf("fun stepTypeMetadata")
        if (funStart == -1 || funEnd == -1) {
            // function may have been removed or renamed; this test verifies it no longer exists
            assertTrue(
                !source.contains("fun stepClassifications"),
                "stepClassifications function should be removed (now handled by canonical dispatcher)",
            )
            return
        }
        val classificationsBody = source.substring(funStart, funEnd)

        listOf("CatchError", "WarnError", "Unstable").forEach { kind ->
            assertFalse(
                Regex("""is\s+StepSpec\.$kind""").containsMatchIn(classificationsBody),
                "stepClassifications must NOT have entry for StepSpec.$kind — it is pre-compiler-rewritten",
            )
        }
    }

    @Test
    fun `PipelineRun has no live execution branches for CatchError or WarnError`() {
        val source = sanitizedSource(pipelineRunPath)

        // After the stub change, there should NOT be the old try/catch block bodies
        // for CatchError/WarnError. Check for absence of the old pattern.
        val hasCatchErrorTryBlock = Regex("""is\s+StepSpec\.CatchError[^}]*\{[^}]*try\s*\{""").containsMatchIn(source)
        val hasWarnErrorTryBlock = Regex("""is\s+StepSpec\.WarnError[^}]*\{[^}]*try\s*\{""").containsMatchIn(source)

        assertFalse(
            hasCatchErrorTryBlock,
            "PipelineRun must NOT have a live try/catch block for CatchError execution",
        )
        assertFalse(
            hasWarnErrorTryBlock,
            "PipelineRun must NOT have a live try/catch block for WarnError execution",
        )
    }

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
