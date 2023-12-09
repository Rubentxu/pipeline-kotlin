package dev.rubentxu.pipeline.model.workspace

import dev.rubentxu.pipeline.events.Event
import java.nio.file.Path
import java.nio.file.attribute.FileTime

interface FileSystemEvent : Event {
    val filePath: Path
    val fileSize: Long
    val creationTime: FileTime
    val lastModified: FileTime
}

data class FileCreatedEvent(
    override val filePath: Path,
    override val fileSize: Long,
    override val creationTime: FileTime,
    override val lastModified: FileTime,
    ) : FileSystemEvent
data class FileDeletedEvent(
    override val filePath: Path,
    override val fileSize: Long,
    override val creationTime: FileTime,
    override val lastModified: FileTime,
) : FileSystemEvent

data class FileModifiedEvent(
    override val filePath: Path,
    override val fileSize: Long,
    override val creationTime: FileTime,
    override val lastModified: FileTime,
) : FileSystemEvent