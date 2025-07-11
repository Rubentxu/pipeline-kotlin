package dev.rubentxu.pipeline.scripting

import java.nio.file.Path

interface ConfigurationLoader<T> {
    fun load(configPath: Path): Result<T>
}