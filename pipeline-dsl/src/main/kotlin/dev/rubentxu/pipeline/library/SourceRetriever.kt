package dev.rubentxu.pipeline.library

import java.io.File

interface SourceRetriever {
    fun retrieve(libraryConfiguration: LibraryConfiguration): File
}