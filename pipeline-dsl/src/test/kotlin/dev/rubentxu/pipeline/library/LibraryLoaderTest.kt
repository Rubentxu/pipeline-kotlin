package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
