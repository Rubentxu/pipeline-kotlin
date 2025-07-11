package dev.rubentxu.pipeline.scripting

import java.nio.file.Path

interface ScriptEvaluator<T> {
    fun evaluate(scriptPath: Path): Result<T>
}