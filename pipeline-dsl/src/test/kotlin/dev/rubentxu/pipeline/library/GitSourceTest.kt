package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File

class GitSourceTest : StringSpec({
    val mockGradleCompiler = mock<GradleCompiler>()
    val libraryConfiguration = LibraryConfiguration(
        name = "testLibrary",
        sourcePath = "/path/to/source",
        version = "1.0",
        retriever = GitSource(mockGradleCompiler),
        credentialsId = null
    )

    "should retrieve library successfully" {
        whenever(mockGradleCompiler.compileAndJar(any(), any())).thenReturn(File("/path/to/jar"))

        val file = libraryConfiguration.retriever.retrieve(libraryConfiguration)

        file.path shouldBe "/path/to/jar"
    }
})
