package dev.rubentxu.pipeline.dsl.interfaces

import java.nio.file.Path

interface IWorkspace {
    val workspace: Path
    fun checkWatchedFiles(abortPipeline: Boolean = true): Boolean
    fun checkWatchedFiles(abortPipeline: Boolean, inclusions: List<String>, exclusions: List<String>): Boolean

    fun globMatch(pattern: String, str: String, caseSensitive: Boolean = true): Boolean
    fun createDirectoriesIfNotExist(dirs: List<String>)
    fun directoryExists(directory: String): Boolean
}
