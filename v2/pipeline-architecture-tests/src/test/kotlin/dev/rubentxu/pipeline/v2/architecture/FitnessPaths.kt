package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

object FitnessPaths {
    fun v2Root(): Path {
        val override = System.getProperty("fitness.v2.root", "v2")
        return Path.of(override).toAbsolutePath().normalize()
    }

    fun walkKotlinFiles(root: Path): List<Path> {
        return Files.walk(root).use { stream ->
            stream.filter { it.toFile().isFile &&
                (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".kts")) }
                .toList()
        }
    }

    fun walkBuildFiles(root: Path): List<Path> {
        return Files.walk(root).use { stream ->
            stream.filter { it.toFile().isFile &&
                (it.fileName.toString() == "build.gradle.kts" || it.fileName.toString().endsWith(".toml")) }
                .toList()
        }
    }
}
