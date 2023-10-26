package dev.rubentxu.pipeline.library

class LibraryNotFoundException(id: LibraryId) : Exception("Librería ${id.name} con ${id.version} no encontrada")

class SourceNotFoundException(message:String) : Exception(message)

class JarFileNotFoundException(path: String) : Exception("No se encontró el archivo JAR en la ruta especificada: $path")
