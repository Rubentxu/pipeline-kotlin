package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import org.mockito.kotlin.*
import java.io.File

class LibraryLoaderTest : StringSpec({
    val mockRetriever = mock<SourceRetriever>()
    val mockGradleCompiler = mock<GradleCompiler>()
    val libraryId = LibraryId("testLibrary", "1.0")
    val libraryConfiguration = LibraryConfiguration(
        name = "testLibrary",
        sourcePath = "build/path/to/source",
        version = "1.0",
        retriever = mockRetriever,
        credentialsId = null
    )
    val libraryLoader = LibraryLoader()

    "should load library successfully" {
        libraryLoader.libraries[libraryId] = libraryConfiguration
        whenever(mockRetriever.retrieve(libraryConfiguration)).thenReturn(File("/path/to/jar"))

        libraryLoader.loadLibrary(libraryId)

        verify(mockRetriever).retrieve(libraryConfiguration)
    }

    "should throw LibraryNotFoundException when library not found" {
        shouldThrow<LibraryNotFoundException> {
            libraryLoader.loadLibrary(libraryId)
        }
    }
})
