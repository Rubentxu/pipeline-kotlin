package dev.rubentxu.pipeline.library

data class LibraryConfiguration(
    val name: String,
    val sourcePath: String,
    val version: String,
    val retriever: SourceRetriever,
    val credentialsId: String?


)