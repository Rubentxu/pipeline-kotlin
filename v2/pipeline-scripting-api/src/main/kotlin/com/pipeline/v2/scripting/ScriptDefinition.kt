package com.pipeline.v2.scripting

import java.nio.file.Path

/**
 * Defines a Kotlin script to be compiled and evaluated by a [ScriptingHost].
 *
 * @param sourceText Raw Kotlin script text. Set when using inline scripts.
 * @param sourcePath Path to a `.kts` file. Set when loading from disk.
 * @param classpath List of absolute file paths (JARs or directories) added to
 *                  the script's compilation and evaluation classloader.
 * @param properties Arbitrary key-value configuration passed to the script.
 */
data class ScriptDefinition(
    val sourceText: String? = null,
    val sourcePath: Path? = null,
    val classpath: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(sourceText != null || sourcePath != null) {
            "ScriptDefinition requires either sourceText or sourcePath"
        }
    }

    companion object {
        @JvmStatic
        fun inline(text: String, classpath: List<String> = emptyList(), properties: Map<String, String> = emptyMap()): ScriptDefinition =
            ScriptDefinition(sourceText = text, classpath = classpath, properties = properties)

        @JvmStatic
        fun file(path: Path, classpath: List<String> = emptyList(), properties: Map<String, String> = emptyMap()): ScriptDefinition =
            ScriptDefinition(sourcePath = path, classpath = classpath, properties = properties)
    }
}
