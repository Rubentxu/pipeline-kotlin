package dev.rubentxu.pipeline.model.workspace


import dev.rubentxu.pipeline.events.EventBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Path

class ChangeLogManager(
    private val eventBus: EventBus
) {
    private val changeLog = mutableListOf<FileSystemEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            eventBus.ofType<FileSystemEvent>().collect { event ->
                synchronized(changeLog) {
                    println("Event: $event")
                    changeLog.add(event)
                }
            }
        }
    }

    fun getChangeLog(): List<FileSystemEvent> {
        return synchronized(changeLog) {
            changeLog.toList()
        }
    }

    fun logEventsToFile(logFilePath: Path) {
        val logFile = logFilePath.toFile()
        logFile.createNewFile()
        logFile.writeText("File,Last Modified,Size,Creation Time\n")
        changeLog.forEach { event ->
            logFile.appendText("${event.filePath},${event.lastModified},${event.fileSize},${event.creationTime}\n")
        }
    }

    fun getLastEventForFile(path: Path): FileSystemEvent? {
        return changeLog.filterIsInstance<FileSystemEvent>()
            .lastOrNull { it.filePath == path }
    }
}