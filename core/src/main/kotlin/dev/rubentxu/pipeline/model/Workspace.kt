package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.dsl.interfaces.IWorkspace
import dev.rubentxu.pipeline.logger.PipelineLogger
import java.io.File

/**
 * Represents the workspace for pipeline execution, including file watching and directory management.
 *
 * @property watchFiles The file patterns to watch for changes.
 * @property logger The logger instance for workspace operations.
 * @property gitTool The Git tool used for SCM operations.
 */
class Workspace(
    var watchFiles: WatchFiles = WatchFiles(inclusions = mutableListOf(), exclusions = mutableListOf()),
    private val logger: PipelineLogger,
    val gitTool: GitTool
) : IWorkspace {

    /**
     * Checks if a directory exists.
     *
     * @param directory The directory path.
     * @return True if the directory exists and is a directory, false otherwise.
     */
    override fun directoryExists(directory: String): Boolean {
        return File(directory).exists() && File(directory).isDirectory
    }

    /**
     * Creates directories if they do not exist.
     *
     * @param dirs List of directory paths to create.
     */
    override fun createDirectoriesIfNotExist(dirs: List<String>) {
        dirs.forEach { dir ->
            val directory = File(dir)
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    /**
     * Checks if a string matches a glob pattern.
     *
     * @param pattern The glob pattern.
     * @param str The string to match.
     * @param caseSensitive Whether the match is case sensitive.
     * @return True if the string matches the pattern, false otherwise.
     */
    override fun globMatch(pattern: String, str: String, caseSensitive: Boolean): Boolean {
        val regexPattern = globToRegex(pattern, caseSensitive)
        return regexPattern.matches(str)
    }

    /**
     * Converts a glob pattern to a [Regex] object.
     *
     * @param pattern The glob pattern.
     * @param caseSensitive Whether the regex should be case sensitive.
     * @return The compiled [Regex].
     */
    private fun globToRegex(pattern: String, caseSensitive: Boolean): Regex {
        val sb = StringBuilder(pattern.length)
        var inGroup = 0
        var inClass = 0
        var firstIndexInClass = -1

        pattern.forEachIndexed { index, c ->
            when (c) {
                '*' -> if (index == 0 || pattern[index - 1] != '\\') sb.append(".*") else sb.append("\\*")
                '?' -> if (index == 0 || pattern[index - 1] != '\\') sb.append('.') else sb.append("\\?")
                '.' -> sb.append("\\.")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }

        return Regex(sb.toString(), if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
    }

    /**
     * Checks watched files for changes and determines if the pipeline should be aborted.
     *
     * @param abortPipeline Whether to abort the pipeline if no changes are found.
     * @return True if changes are found or no abort is required, false otherwise.
     */
    override fun checkWatchedFiles(abortPipeline: Boolean): Boolean {
        val watchFilesInclusionsList = watchFiles.inclusions ?: emptyList()
        val watchFilesExclusionsList = watchFiles.exclusions ?: emptyList()
        return checkWatchedFiles(abortPipeline, watchFilesInclusionsList, watchFilesExclusionsList)
    }

    /**
     * Checks watched files for changes using specific inclusion and exclusion patterns.
     *
     * @param abortPipeline Whether to abort the pipeline if no changes are found.
     * @param inclusions List of inclusion patterns.
     * @param exclusions List of exclusion patterns.
     * @return True if changes are found or no abort is required, false otherwise.
     */
    override fun checkWatchedFiles(
        abortPipeline: Boolean,
        inclusions: List<String>,
        exclusions: List<String>
    ): Boolean {
        //        if (inclusions.isEmpty()) {
//            logger.warn("No watchFiles configured")
//            return true
//        }
//
//        val changedFiles = getChangedFileList()
//        logger.info("Files that have changed are ${changedFiles.joinToString()}")
//
//        val foundCoincidence = changedFiles.any { file ->
//            logger.debug("Checking if file '$file' matches any inclusion patterns")
//            val fileFound = findFileInGlobExpressions(file, inclusions)
//            var fileIgnored = false
//
//            if (fileFound) {
//                logger.debug("Checking if file $file matches any exclusion patterns")
//                fileIgnored = findFileInGlobExpressions(file, exclusions)
//                logger.debug("File '$file' is ${if (fileIgnored) "ignored" else "included"}")
//            }
//            fileFound && !fileIgnored
//        }
//
//        logger.info("${if (foundCoincidence) "Found" else "Not found any"} coincidence")
//
//        if (!foundCoincidence && abortPipeline) {
//            logger.info("Skip execution since no files have changed between the reference and the HEAD")
//            // Aquí deberían ir las acciones para auto-cancelar el pipeline
//        }
//        return foundCoincidence
        return true
    }

//    private fun getChangedFileList(): List<String> {
//        val fromRef = gitTool.getLatestTagFromCurrentHead() ?: gitTool.getFirstCommitSha()
//        return gitTool.getChangedFilesBetweenCommits(fromRef, "HEAD")
//    }

    /**
     * Finds if a file path matches any of the provided glob expressions.
     *
     * @param filePath The file path to check.
     * @param expressions List of glob patterns.
     * @return True if the file matches any pattern, false otherwise.
     */
    private fun findFileInGlobExpressions(filePath: String, expressions: List<String>): Boolean {
        return expressions.any { globPattern ->
            val found = globMatch(globPattern, filePath)
            logger.debug("File '$filePath' ${if (found) "matches" else "does not match"} pattern $globPattern")
            found
        }
    }
}

/**
 * Data class representing file patterns to watch for changes.
 *
 * @property inclusions List of inclusion patterns.
 * @property exclusions List of exclusion patterns.
 */
data class WatchFiles(
    val inclusions: List<String>,
    val exclusions: List<String>
)
