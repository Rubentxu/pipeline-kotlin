package dev.rubentxu.pipeline.library

import java.io.File

/**
 * Retrieves a library from a local JAR file.
 */
class LocalJar : SourceRetriever {

    /**
     * Retrieves the JAR file from the specified path in the configuration.
     *
     * @param libraryConfiguration The configuration of the library.
     * @return The JAR file.
     * @throws JarFileNotFoundException if the file does not exist.
     */
    override fun retrieve(libraryConfiguration: LibraryConfiguration): File {
        val jarPath = libraryConfiguration.sourcePath
        val jarFile = File(jarPath)

        if (!jarFile.exists()) {
            throw JarFileNotFoundException(jarPath)
        }

        return jarFile
    }
}