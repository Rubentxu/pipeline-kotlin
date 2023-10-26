package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import java.io.File

class LocalSource(private val gradleCompiler: GradleCompiler) : SourceRetriever {
    override fun retrieve(libraryConfiguration: LibraryConfiguration): File {
        val sourcePath = libraryConfiguration.sourcePath
        return gradleCompiler.compileAndJar(sourcePath, libraryConfiguration)
    }
}