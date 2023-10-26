package dev.rubentxu.pipeline.library

import java.io.File

class LocalJar : SourceRetriever {
    override fun retrieve(libraryConfiguration: LibraryConfiguration): File {
        val jarPath = libraryConfiguration.sourcePath
        val jarFile = File(jarPath)

        if (!jarFile.exists()) {
            throw JarFileNotFoundException(jarPath)
        }

        return jarFile
    }
}