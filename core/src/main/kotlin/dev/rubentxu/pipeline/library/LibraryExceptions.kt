package dev.rubentxu.pipeline.library

/**
 * Exception thrown when a library is not found.
 *
 * @param id The ID of the missing library.
 */
class LibraryNotFoundException(id: LibraryId) : Exception("Library ${id.name} with version ${id.version} not found")

/**
 * Exception thrown when a source is not found.
 *
 * @param message The error message.
 */
class SourceNotFoundException(message: String) : Exception(message)

/**
 * Exception thrown when a JAR file is not found at the specified path.
 *
 * @param path The path where the JAR file was expected.
 */
class JarFileNotFoundException(path: String) : Exception("JAR file not found at specified path: $path")
