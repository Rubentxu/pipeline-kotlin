package dev.rubentxu.pipeline.library

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Loads and manages pipeline libraries.
 */
class LibraryLoader {

    /**
     * Map of loaded libraries by their ID.
     */
    val libraries = mutableMapOf<LibraryId, LibraryConfiguration>()

    /**
     * Loads a library by its ID.
     *
     * @param id The library ID.
     * @return The JAR file of the loaded library.
     * @throws LibraryNotFoundException if the library is not registered.
     */
    fun loadLibrary(id: LibraryId): File {
        val libraryConfiguration = libraries[id] ?: throw LibraryNotFoundException(id)
        return doLoadLibrary(libraryConfiguration)
    }

    /**
     * Loads a library using its configuration.
     *
     * @param libraryConfiguration The library configuration.
     * @return The JAR file of the loaded library.
     */
    private fun doLoadLibrary(libraryConfiguration: LibraryConfiguration): File {
        val retriever = libraryConfiguration.retriever
        val jarFile = retriever.retrieve(libraryConfiguration)
        val url = jarFile.toURI().toURL()

        // Create a new URLClassLoader with the new URL
        URLClassLoader.newInstance(arrayOf(url))
        return jarFile
    }

    /**
     * Resolves and normalizes an absolute path from a relative path.
     *
     * @param relativePath The relative path.
     * @return The normalized absolute path as a string.
     */
    fun resolveAndNormalizeAbsolutePath(relativePath: String): String {
        val path = Path.of(relativePath)
        return path.toAbsolutePath().normalize().toString()
    }

}