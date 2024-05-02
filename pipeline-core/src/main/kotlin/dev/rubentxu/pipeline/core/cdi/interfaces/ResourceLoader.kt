package dev.rubentxu.pipeline.core.cdi.interfaces

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile

interface ResourceLoader<T> {
    fun loadFromJarfile(packageName: String, jarFile: JarFile, entry: JarEntry): Result<T>
    fun loadFromFilesystem(packageName: String, directory: File, fileName: String): Result<T>
}