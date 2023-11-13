package dev.rubentxu.pipeline.dsl.interfaces

import dev.rubentxu.pipeline.dsl.StepsBlock

interface IWorkspace {

    fun checkWatchedFiles(abortPipeline: Boolean = true): Boolean
    fun checkWatchedFiles(abortPipeline: Boolean, inclusions: List<String>, exclusions: List<String>): Boolean
    fun StepsBlock.fileExists(file: String): Boolean
    fun StepsBlock.writeFile(file: String, text: String)
    fun StepsBlock.readFile(file: String) : String
    fun globMatch(pattern: String, str: String, caseSensitive: Boolean = true): Boolean
    fun createDirectoriesIfNotExist(dirs: List<String>)
    fun directoryExists(directory: String): Boolean
}
