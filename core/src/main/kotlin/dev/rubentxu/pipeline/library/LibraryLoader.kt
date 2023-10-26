package dev.rubentxu.pipeline.library



import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

class LibraryLoader {
    val libraries = mutableMapOf<LibraryId, LibraryConfiguration>()

    fun loadLibrary(id: LibraryId): File {
        val libraryConfiguration = libraries[id] ?: throw LibraryNotFoundException(id)
        return doLoadLibrary(libraryConfiguration)
    }

    private fun doLoadLibrary(libraryConfiguration: LibraryConfiguration): File {
        val retriever = libraryConfiguration.retriever
        val jarFile = retriever.retrieve(libraryConfiguration)
        val url = jarFile.toURI().toURL()

        // Crear un nuevo URLClassLoader con la nueva URL
        URLClassLoader.newInstance(arrayOf(url))
        return jarFile
    }

    fun resolveAndNormalizeAbsolutePath(relativePath: String): String {
        val path = Path.of(relativePath)
        return path.toAbsolutePath().normalize().toString()

    }

}