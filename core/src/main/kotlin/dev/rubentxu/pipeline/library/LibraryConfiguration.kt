package dev.rubentxu.pipeline.library

/**
 * Configuration for a pipeline library.
 *
 * @property name The name of the library.
 * @property sourcePath The source path or URL of the library.
 * @property version The version of the library.
 * @property retriever The retriever used to obtain the library source.
 * @property credentialsId The credentials ID for accessing the source, if required.
 */
data class LibraryConfiguration(
    val name: String,
    val sourcePath: String,
    val version: String,
    val retriever: SourceRetriever,
    val credentialsId: String?

)