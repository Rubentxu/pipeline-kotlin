package dev.rubentxu.pipeline.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class LocalJarTest : StringSpec({
    val libraryConfiguration = LibraryConfiguration(
        name = "testLibrary",
        sourcePath = "/path/to/jar",
        version = "1.0",
        retriever = LocalJar(),
        credentialsId = null
    )

    "should retrieve jar file successfully" {
        val file = File("/path/to/jar")
        file.createNewFile()

        val retrievedFile = libraryConfiguration.retriever.retrieve(libraryConfiguration)

        retrievedFile.path shouldBe "/path/to/jar"
    }

    "should throw JarFileNotFoundException when jar file not found" {
        shouldThrow<JarFileNotFoundException> {
            libraryConfiguration.retriever.retrieve(libraryConfiguration)
        }
    }
})