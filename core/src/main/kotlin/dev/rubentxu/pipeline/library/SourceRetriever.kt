package dev.rubentxu.pipeline.library

import java.io.File

/**
 * Interface for retrieving a library source.
 */
interface SourceRetriever {

    /**
     * Retrieves the library source as a [File].
     *
     * @param libraryConfiguration The configuration of the library to retrieve.
     * @return The retrieved file (e.g., a JAR).
     */
    fun retrieve(libraryConfiguration: LibraryConfiguration): File
}