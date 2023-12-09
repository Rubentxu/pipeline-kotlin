package dev.rubentxu.pipeline.model.workspace

import dev.rubentxu.pipeline.events.EventBus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.*
import org.mockito.kotlin.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService

class WorkspaceManagerTest : StringSpec({

    lateinit var workspacePath: Path
    lateinit var eventBus: EventBus
    lateinit var changeLogManager: ChangeLogManager
    lateinit var workspaceManager: WorkspaceManager
    lateinit var watchService: WatchService
    lateinit var file: File
    lateinit var lastEvent: FileSystemEvent
    lateinit var watchKey: WatchKey

    beforeTest {
        eventBus = EventBus()
        changeLogManager = ChangeLogManager(eventBus)


    }

    "should log file creation events with correct sizes" {
        runTest(UnconfinedTestDispatcher()) {
            val testScope = TestScope()

            // Crear un directorio temporal
            val tempDir = Files.createTempDirectory("testDir").toFile()
            workspacePath = tempDir.toPath()
            workspaceManager = WorkspaceManager(workspacePath, eventBus, changeLogManager)
            // Crear un archivo temporal dentro del directorio


            workspaceManager.startWatching()

            val tempFile = File(tempDir, "testFile.txt")
            // Escribir en el archivo temporal
            tempFile.writeText("Hello, World!")
            tempFile.createNewFile()

            eventBus.ofType<FileSystemEvent>().onEach { event ->
                println("Event Test: $event")
            }.launchIn(testScope)



            // Avanzar el tiempo hasta que todos los eventos hayan sido procesados
            testScope.advanceUntilIdle()
            tempDir.deleteRecursively()

            delay(500)

            changeLogManager.getChangeLog().forEach { event ->
                println("Event 3: $event")
            }

            changeLogManager.getChangeLog().size shouldBe 3
            changeLogManager.getChangeLog().last().filePath.toString() shouldBe tempFile.path.toString()
            changeLogManager.getChangeLog().first().shouldBeInstanceOf<FileCreatedEvent>()
            changeLogManager.getChangeLog().last().shouldBeInstanceOf<FileDeletedEvent>()

            // Recuerda eliminar el directorio temporal después de la prueba
            tempDir.deleteRecursively()
        }
    }


//    "should log file modification events with correct sizes" {
//        whenever(file.length()).thenReturn(2000L)
//        whenever(lastEvent.fileSize).thenReturn(1000L)
//        whenever(changeLogManager.getLastEventForFile(file)).thenReturn(lastEvent)
//
//        workspaceManager.startWatching()
//
//        verify(eventBus).publish(check<FileModifiedEvent> { event ->
//            event.filePath shouldBe file
//            event.creationTime shouldBe 1000L
//            event.fileSize shouldBe 2000L
//        })
//    }
//
//    "should log file creation events" {
//
//        whenever(file.length()).thenReturn(1000L)
//        whenever(changeLogManager.getLastEventForFile(file)).thenReturn(null)
//
//        workspaceManager.startWatching()
//
//        verify(eventBus).publish(check<FileCreatedEvent> { event ->
//            event.filePath shouldBe file
//            event.creationTime shouldBe 0L
//            event.fileSize shouldBe 1000L
//        })
//    }
//
//    "should log file modification events" {
//
//        whenever(file.length()).thenReturn(2000L)
//        val lastEvent: FileSystemEvent = mock()
//        whenever(lastEvent.fileSize).thenReturn(1000L)
//        whenever(changeLogManager.getLastEventForFile(file)).thenReturn(lastEvent)
//
//        workspaceManager.startWatching()
//
//        verify(eventBus).publish(check<FileModifiedEvent> { event ->
//            event.filePath shouldBe file
//            event.creationTime shouldBe 1000L
//            event.fileSize shouldBe 2000L
//        })
//    }
//
//    "should log file deletion events" {
//        whenever(file.length()).thenReturn(0L)
//        val lastEvent: FileSystemEvent = mock()
//        whenever(lastEvent.fileSize).thenReturn(1000L)
//        whenever(changeLogManager.getLastEventForFile(file)).thenReturn(lastEvent)
//
//        workspaceManager.startWatching()
//
//        verify(eventBus).publish(check<FileDeletedEvent> { event ->
//            event.filePath shouldBe file
//            event.creationTime shouldBe 1000L
//            event.fileSize shouldBe 0L
//        })
//    }

//    "should return true when watched files have changed" {
//
//        val fileEvent: FileSystemEvent = mock()
//        whenever(fileEvent.filePath).thenReturn(File("test.txt"))
//        whenever(changeLogManager.getChangeLog()).thenReturn(listOf(fileEvent))
//
//        val result = workspaceManager.checkWatchedFiles(false, listOf("*"), listOf())
//
//        result shouldBe true
//    }

    "should return false when watched files have not changed" {
        whenever(changeLogManager.getChangeLog()).thenReturn(emptyList())

        val result = workspaceManager.checkWatchedFiles(false, listOf("*"), listOf())

        result shouldBe false
    }
})