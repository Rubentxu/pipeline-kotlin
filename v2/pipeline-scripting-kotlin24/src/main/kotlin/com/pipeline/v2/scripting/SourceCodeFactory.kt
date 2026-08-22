package com.pipeline.v2.scripting

import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.host.FileScriptSource
import java.nio.file.Path

/**
 * Converts a [ScriptDefinition] into a [SourceCode] suitable for
 * [kotlin.script.experimental.jvmhost.BasicJvmScriptingHost.evalWithTemplate].
 */
object SourceCodeFactory {
    fun toSourceCode(definition: ScriptDefinition): SourceCode {
        val sourcePath: Path? = definition.sourcePath
        val sourceText: String? = definition.sourceText
        return when {
            sourcePath != null -> {
                FileScriptSource(sourcePath.toFile())
            }
            sourceText != null -> {
                StringScriptSource(
                    sourceText,
                    name = "<inline>",
                    locationId = "<inline>"
                )
            }
            else -> error("ScriptDefinition must have either sourceText or sourcePath")
        }
    }
}
