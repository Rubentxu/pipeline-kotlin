package dev.rubentxu.pipeline.model

import java.io.File

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent
import java.nio.file.WatchKey

class WorkspaceManager(
    val workspacePath: Path,
    val changeListener: (Path) -> Unit,
    val changeLog: MutableMap<File, WatchEvent.Kind<Path>> = mutableMapOf<File, WatchEvent.Kind<Path>>(),
) {

    fun monitorChanges() {
        val watchService = FileSystems.getDefault().newWatchService()
        workspacePath.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

        while (true) {
            val key: WatchKey = watchService.take()
            val event: WatchEvent<*> = key.pollEvents().first()
            val file = event.context() as File
            val changeType = event.kind() as WatchEvent.Kind<Path>
            changeLog.put(file, changeType)
        }
    }

    fun getChangeLog(): Map<File, WatchEvent.Kind<Path>> {
        return changeLog
    }


    fun checkWatchedFiles(
        abortPipeline: Boolean,
        inclusions: List<String>,
        exclusions: List<String>,
    ): Boolean {
        val changedFiles = changeLog.keys
        val changedFilesFiltered = changedFiles.filter { file ->
            val relativePath = workspacePath.relativize(file.toPath()).toString()
            val isIncluded = inclusions.any { globMatch(it, relativePath) }
            val isExcluded = exclusions.any { globMatch(it, relativePath) }
            isIncluded && !isExcluded
        }
        if (changedFilesFiltered.isNotEmpty()) {
            if (abortPipeline) {
                throw Exception("Pipeline aborted because of changes in files: $changedFilesFiltered")
            } else {
                changedFilesFiltered.forEach { file ->
                    changeListener(file.toPath())
                }
            }
        }
        return changedFilesFiltered.isNotEmpty()
    }

    fun globMatch(pattern: String, str: String, caseSensitive: Boolean = true): Boolean {
        val regexPattern = globToRegex(pattern, caseSensitive)
        return regexPattern.matches(str)
    }

    private fun globToRegex(pattern: String, caseSensitive: Boolean): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.' -> sb.append("\\.")
                '\\' -> sb.append("\\\\")
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return Regex(sb.toString(), if (caseSensitive) RegexOption.UNIX_LINES else RegexOption.IGNORE_CASE)
    }

}
