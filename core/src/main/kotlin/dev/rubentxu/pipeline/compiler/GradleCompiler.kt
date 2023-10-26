package dev.rubentxu.pipeline.compiler

import dev.rubentxu.pipeline.library.JarFileNotFoundException
import dev.rubentxu.pipeline.library.LibraryConfiguration
import dev.rubentxu.pipeline.library.LibraryNotFoundException
import dev.rubentxu.pipeline.library.SourceNotFoundException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.GradleProject
import java.io.File
import java.nio.file.Path

class GradleCompiler {
    fun compileAndJar(sourcePath: String, libraryConfiguration: LibraryConfiguration): File {
        val file =  File(resolveAndNormalizeAbsolutePath(sourcePath))
        if(!file.exists()) {
            throw SourceNotFoundException("File ${file.path} not found")
        }

        val connection: ProjectConnection = GradleConnector.newConnector()
            .forProjectDirectory(file)
            .connect()

        val project: GradleProject = connection.getModel(GradleProject::class.java)
        project.name

        try {
            connection.newBuild()
                .withArguments("clean", "build", "-ParchiveBaseName${libraryConfiguration.name}")
                .run()

            val jarFile = findJarFile(File(resolveAndNormalizeAbsolutePath(sourcePath), "build/libs/"))

            if (jarFile?.exists() == false) {
                throw JarFileNotFoundException(jarFile.path)
            }

            return jarFile!!
        } finally {
            connection.close()
        }
    }

    fun getProjectName(projectDir: String): String {
        val connection: ProjectConnection = GradleConnector.newConnector()
            .forProjectDirectory(File(projectDir))
            .connect()

        try {
            val project: GradleProject = connection.getModel(GradleProject::class.java)
            return project.name
        } finally {
            connection.close()
        }
    }

    fun findJarFile(directory: File): File? {
        return directory.walk()
            .filter { it.isFile && it.extension == "jar" }
            .firstOrNull() // Devuelve el primer archivo JAR encontrado o null si no se encontró ninguno
    }

    fun resolveAndNormalizeAbsolutePath(relativePath: String): String {
        val path = Path.of(relativePath)
        return path.toAbsolutePath().normalize().toString()

    }

}