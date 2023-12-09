package dev.rubentxu.pipeline.model.workspace

import dev.rubentxu.pipeline.events.EventBus
import kotlinx.coroutines.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*


interface IWorkspaceManager {
    fun startWatching(): Job
    fun stopWatching()
    fun getChangeLog(): List<FileSystemEvent>
    fun checkWatchedFiles(
        abortPipeline: Boolean,
        inclusions: List<String>,
        exclusions: List<String>,
    ): Boolean
}

class WorkspaceManager(
    val workspacePath: Path,
    private val eventBus: EventBus,
    private val changeLogManager: ChangeLogManager,
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
) : IWorkspaceManager {

    private lateinit var job: Job


    override fun startWatching() = CoroutineScope(Dispatchers.IO).launch {
        workspacePath.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY
        )

        job = launch {
            while (isActive) {
                val key = watchService.take()

                key.pollEvents().forEach { event ->

                    val fileName = event.context() as Path
                    val filePath = workspacePath.resolve(fileName)

                    val attrs = getFileAttributes(filePath)
                    // Buscar en changeLog para encontrar el último evento para el archivo
                    val lastEventForFile = changeLogManager.getLastEventForFile(filePath)

                    val lastModifiedTime = attrs?.lastModifiedTime()?: lastEventForFile?.lastModified?: FileTime.fromMillis(Date().time)
                    val creationTime = attrs?.creationTime()?: lastEventForFile?.creationTime?: FileTime.fromMillis(0)
                    val fileSize = attrs?.size()?: lastEventForFile?.fileSize?: 0L


                    when (event.kind()) {
                        StandardWatchEventKinds.ENTRY_CREATE -> {
                            eventBus.publish(FileCreatedEvent(filePath, fileSize, creationTime, lastModifiedTime))
                        }
                        StandardWatchEventKinds.ENTRY_DELETE -> {
                            eventBus.publish(FileDeletedEvent(filePath, fileSize, creationTime, lastModifiedTime))
                        }
                        StandardWatchEventKinds.ENTRY_MODIFY -> {
                            eventBus.publish(FileModifiedEvent(filePath, fileSize, creationTime, lastModifiedTime))
                        }
                    }
                }

                val valid = key.reset()
                if (!valid) {
                    break
                }
            }
        }
    }

    private fun getFileAttributes(filePath: Path):BasicFileAttributes? {
        return if (Files.exists(filePath)) {
            Files.readAttributes(filePath, BasicFileAttributes::class.java)
        } else {
            null
        }
    }

    override fun stopWatching() {
        job.cancel()
        watchService.close()
    }

    override fun getChangeLog(): List<FileSystemEvent> {
        return changeLogManager.getChangeLog()
    }


    override fun checkWatchedFiles(
        abortPipeline: Boolean,
        inclusions: List<String>,
        exclusions: List<String>,
    ): Boolean {
        val changedFilesFiltered = changeLogManager.getChangeLog().map { event: FileSystemEvent -> event.filePath }
            .filter { file: Path ->
                val relativePath = workspacePath.relativize(file).toString()
                val isIncluded = inclusions.any { globMatch(it, relativePath) }
                val isExcluded = exclusions.any { globMatch(it, relativePath) }
                isIncluded && !isExcluded
            }
        if (changedFilesFiltered.isNotEmpty()) {
            if (abortPipeline) {
                throw Exception("Pipeline aborted because of changes in files: $changedFilesFiltered")
            } else {
                changedFilesFiltered.forEach { file: Path ->
                    println("File changed: $file")
                }
            }
        }
        return changedFilesFiltered.isNotEmpty()
    }

    private fun globMatch(pattern: String, str: String, caseSensitive: Boolean = true): Boolean {
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



