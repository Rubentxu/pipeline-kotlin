package dev.rubentxu.pipeline.error

import kotlin.script.experimental.api.SourceCode

/**
 * Source mapping information for compiled scripts.
 * Maps runtime positions back to original source code positions.
 */
data class SourceMap(
    val originalFile: String,
    val originalContent: String,
    val compiledName: String,
    val mappings: List<SourceMapping> = emptyList()
) {
    
    /**
     * Maps a runtime position back to the original source position.
     */
    fun mapToOriginal(runtimeLine: Int, runtimeColumn: Int = 0): SourcePosition? {
        return mappings
            .filter { it.runtimeLine <= runtimeLine }
            .maxByOrNull { it.runtimeLine }
            ?.let { mapping ->
                SourcePosition(
                    line = mapping.originalLine + (runtimeLine - mapping.runtimeLine),
                    column = if (runtimeLine == mapping.runtimeLine) {
                        mapping.originalColumn + runtimeColumn - mapping.runtimeColumn
                    } else {
                        runtimeColumn
                    },
                    file = originalFile
                )
            }
    }
    
    /**
     * Gets the source code context around a position.
     */
    fun getSourceContext(position: SourcePosition, contextLines: Int = 3): SourceContext? {
        val lines = originalContent.lines()
        if (position.line < 1 || position.line > lines.size) return null
        
        val startLine = maxOf(1, position.line - contextLines)
        val endLine = minOf(lines.size, position.line + contextLines)
        
        val contextLines = lines.subList(startLine - 1, endLine)
        
        return SourceContext(
            errorLine = position.line,
            errorColumn = position.column,
            startLine = startLine,
            endLine = endLine,
            lines = contextLines,
            file = position.file
        )
    }
}

/**
 * Individual source mapping entry.
 */
data class SourceMapping(
    val runtimeLine: Int,
    val runtimeColumn: Int,
    val originalLine: Int,
    val originalColumn: Int
)

/**
 * Position in source code.
 */
data class SourcePosition(
    val line: Int,
    val column: Int,
    val file: String
)

/**
 * Source code context around an error.
 */
data class SourceContext(
    val errorLine: Int,
    val errorColumn: Int,
    val startLine: Int,
    val endLine: Int,
    val lines: List<String>,
    val file: String
) {
    
    /**
     * Formats the context as a readable string with line numbers and error indicators.
     */
    fun format(): String = buildString {
        lines.forEachIndexed { index, line ->
            val lineNumber = startLine + index
            val isErrorLine = lineNumber == errorLine
            
            // Line number with padding
            val linePrefix = String.format("%4d", lineNumber)
            
            if (isErrorLine) {
                appendLine(">>> $linePrefix | $line")
                // Add error indicator
                val errorIndicator = " ".repeat(8 + errorColumn) + "^"
                appendLine(errorIndicator)
            } else {
                appendLine("    $linePrefix | $line")
            }
        }
    }
}

/**
 * Source map builder for generating mappings during compilation.
 */
class SourceMapBuilder(
    private val originalFile: String,
    private val originalContent: String,
    private val compiledName: String
) {
    private val mappings = mutableListOf<SourceMapping>()
    
    /**
     * Adds a mapping between runtime and original positions.
     */
    fun addMapping(
        runtimeLine: Int,
        runtimeColumn: Int,
        originalLine: Int,
        originalColumn: Int
    ) {
        mappings.add(SourceMapping(runtimeLine, runtimeColumn, originalLine, originalColumn))
    }
    
    /**
     * Builds the final source map.
     */
    fun build(): SourceMap {
        return SourceMap(
            originalFile = originalFile,
            originalContent = originalContent,
            compiledName = compiledName,
            mappings = mappings.sortedBy { it.runtimeLine }
        )
    }
    
    companion object {
        /**
         * Creates a basic source map for simple scripts where line numbers match 1:1.
         */
        fun createBasicMapping(
            originalFile: String,
            originalContent: String,
            compiledName: String
        ): SourceMap {
            val lines = originalContent.lines()
            val mappings = lines.mapIndexed { index, _ ->
                SourceMapping(
                    runtimeLine = index + 1,
                    runtimeColumn = 0,
                    originalLine = index + 1,
                    originalColumn = 0
                )
            }
            
            return SourceMap(
                originalFile = originalFile,
                originalContent = originalContent,
                compiledName = compiledName,
                mappings = mappings
            )
        }
    }
}