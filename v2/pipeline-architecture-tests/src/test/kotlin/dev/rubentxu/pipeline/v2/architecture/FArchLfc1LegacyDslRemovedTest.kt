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
        // LEG-1.3: PipelineRun.kt is deleted entirely. The legacy stub branches
        // (and every other direct-exec branch) cannot reappear while the file is
        // absent; the canonical compiler rewrite + dispatchers own these kinds.
        assertFalse(
            java.nio.file.Files.exists(pipelineRunPath),
            "PipelineRun.kt must not exist (LEG-1.3): legacy workflow-control stubs are gone with it",
        )
    }

    @Test
    fun `stepClassifications no longer maps CatchError WarnError Unstable`() {
        // LEG-1.3: with PipelineRun.kt deleted, no stepClassifications mapping can
        // exist anywhere in production; classification is registry/descriptor-driven.
        assertFalse(
            java.nio.file.Files.exists(pipelineRunPath),
            "PipelineRun.kt must not exist (LEG-1.3): stepClassifications is gone with it",
        )
    }

    @Test
    fun `PipelineRun has no live execution branches for CatchError or WarnError`() {
        // LEG-1.3: the whole second execution algorithm is deleted; there are no
        // CatchError/WarnError execution branches in production outside the
        // canonical spine's typed dispatchers.
        assertFalse(
            java.nio.file.Files.exists(pipelineRunPath),
            "PipelineRun.kt must not exist (LEG-1.3): no live legacy execution branches can exist",
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
