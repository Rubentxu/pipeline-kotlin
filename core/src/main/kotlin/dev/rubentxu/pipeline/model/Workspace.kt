package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.dsl.interfaces.IWorkspace
import dev.rubentxu.pipeline.logger.PipelineLogger
import java.io.File

class Workspace (
    var watchFiles: WatchFiles = WatchFiles(inclusions = mutableListOf(), exclusions = mutableListOf()),
    private val logger: PipelineLogger,
    val gitTool: GitTool
) : IWorkspace {

    override fun directoryExists(directory: String): Boolean {
        return File(directory).exists() && File(directory).isDirectory
    }


    override fun StepsBlock.fileExists(file: String): Boolean {
        return File(file).exists() && File(file).isFile
    }


    override fun createDirectoriesIfNotExist(dirs: List<String>) {
        dirs.forEach { dir ->
            val directory = File(dir)
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }


    override fun StepsBlock.writeFile(file: String, text: String) {
        File(file).printWriter().use { out ->
            out.print(text)
        }
    }

    override fun StepsBlock.readFile(file: String): String {
        return File(file).readText()
    }


    override fun globMatch(pattern: String, str: String, caseSensitive: Boolean): Boolean {
        val regexPattern = globToRegex(pattern, caseSensitive)
        return regexPattern.matches(str)
    }

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


    override fun checkWatchedFiles(abortPipeline: Boolean): Boolean {
        val watchFilesInclusionsList = watchFiles.inclusions ?: emptyList()
        val watchFilesExclusionsList = watchFiles.exclusions ?: emptyList()
        return checkWatchedFiles(abortPipeline, watchFilesInclusionsList, watchFilesExclusionsList)
    }

    override fun checkWatchedFiles(abortPipeline: Boolean, inclusions: List<String>, exclusions: List<String>): Boolean {
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

    private fun findFileInGlobExpressions(filePath: String, expressions: List<String>): Boolean {
        return expressions.any { globPattern ->
            val found = globMatch(globPattern, filePath)
            logger.debug("File '$filePath' ${if (found) "matches" else "does not match"} pattern $globPattern")
            found
        }
    }


}




data class WatchFiles(
    val inclusions: List<String>,
    val exclusions: List<String>
)

