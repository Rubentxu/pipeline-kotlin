package dev.rubentxu.pipeline.dsl.interfaces

interface IWorkspace {

    fun checkWatchedFiles(abortPipeline: Boolean = true): Boolean
    fun checkWatchedFiles(abortPipeline: Boolean, inclusions: List<String>, exclusions: List<String>): Boolean

    fun globMatch(pattern: String, str: String, caseSensitive: Boolean = true): Boolean
    fun createDirectoriesIfNotExist(dirs: List<String>)
    fun directoryExists(directory: String): Boolean
}
