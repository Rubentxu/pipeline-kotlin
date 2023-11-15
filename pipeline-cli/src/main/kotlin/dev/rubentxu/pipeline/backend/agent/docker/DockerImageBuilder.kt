package dev.rubentxu.pipeline.backend.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.BuildImageResultCallback
import com.github.dockerjava.api.model.Image
import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DockerImageBuilder(
    private val dockerClientProvider: DockerClientProvider,
) {
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
    private val dockerClient: DockerClient = dockerClientProvider.dockerClient

    companion object {
        const val IMAGE_NAME = "pipeline-kts"
        private const val JAR_NAME = "pipeline-cli.jar"
    }

    fun buildCustomImage(baseImage: String, paths: List<Path>): String {
        val executable = determineExecutable(paths)
        val buildDir = prepareBuildDirectory()
        val dockerfile = renderDockerfile(mapOf("baseImage" to baseImage, "executable" to executable), buildDir)

        val pathsWithDockerfile = paths + listOf(dockerfile.toPath())
        val tarFile = createTarFile(pathsWithDockerfile, buildDir)

        return buildOrRetrieveImage(IMAGE_NAME, tarFile)
    }

    private fun buildOrRetrieveImage(imageTag: String, tarFile: File): String {
        val existingImageId = findExistingImageId(imageTag)
        if (existingImageId != null) {
            logger.system("Image already exists: $imageTag")
            return existingImageId
        }

        return buildImage(imageTag, tarFile)
    }

    private fun buildImage(imageTag: String, tarFile: File): String {
        val callback = BuildImageResultCallback()

        val result = dockerClient.buildImageCmd()
            .withBaseDirectory(File(".")) //
            .withTarInputStream(Files.newInputStream(tarFile.toPath()))
            .withTags(setOf(imageTag))
            .exec(callback)

        return callback.awaitImageId()
    }

    fun determineExecutable(paths: List<Path>): String {
        // Verifica si 'paths' contiene un archivo llamado "pipeline-kts"
        val containsBinary = paths.any { it.fileName.toString() == IMAGE_NAME }

        // Establece 'executable' basado en si se encuentra el archivo binario
        return if (containsBinary) {
            IMAGE_NAME
        } else {
            JAR_NAME
        }
    }

    private fun prepareBuildDirectory(): Path {
        val buildDir = Path.of(System.getProperty("user.dir")).resolve("build").resolve("dockerContext")
        logger.system("Create Build dir: $buildDir")
        clearAndCreateDirectory(buildDir)
        return buildDir
    }

    private fun clearAndCreateDirectory(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        Files.createDirectories(path)
    }

    fun renderDockerfile(binding: Map<String, Any>, buildDir: Path): File {
        // Configuración del motor de plantillas Freemarker
        val cfg = Configuration(Configuration.VERSION_2_3_31).apply {
            setClassForTemplateLoading(javaClass, "/templates")
            defaultEncoding = StandardCharsets.UTF_8.name()
            templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
            logTemplateExceptions = false
            wrapUncheckedExceptions = true
        }

        // Obtener la plantilla
        val template = cfg.getTemplate("Dockerfile.tpl")

        // Renderizar la plantilla
        val out = StringWriter()
        template.process(binding, out)

        // Escribir el resultado en un archivo temporal
        val dockerfile = buildDir.resolve("Dockerfile").toFile()
        dockerfile.writeText(out.toString())
        return dockerfile
    }

    fun createTarFile(paths: List<Path>, buildDir: Path): File {
        // Asegúrate de que el directorio de compilación existe o créalo
        Files.createDirectories(buildDir)

        // Define el archivo de salida dentro del subdirectorio 'build'
        val tarFile = buildDir.resolve("context.tar").toFile()

        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(tarFile))).use { tarStream ->
            for (path in paths) {
                val file = path.toFile()

                // Verificar si el archivo existe
                if (!file.exists()) {
                    logger.warn("Archivo no encontrado, omitido: ${file.absolutePath}")
                    throw IllegalArgumentException("File does not exist: ${file.absolutePath}")
                }

                val tarEntry = TarArchiveEntry(file, resolveFilename(file.name)).apply {
                    size = file.length()
                }

                tarStream.putArchiveEntry(tarEntry)
                Files.newInputStream(path).use { it.copyTo(tarStream) }
                tarStream.closeArchiveEntry()
            }
        }
        return tarFile
    }

    private fun resolveFilename(filename: String): String {
        return when {
            filename.endsWith(".yaml") -> "app/config.yaml"
            filename.endsWith("pipeline.kts") -> "app/script.pipeline.kts"
            filename.endsWith(".jar") -> "app/${JAR_NAME}"
            filename.endsWith("Dockerfile") -> "Dockerfile"
            else -> "app/$filename"
        }
    }

    private fun buildImageFromTar(tarFile: File, imageTag: String): String {
        // Check if the image already exists
        val existingImages = findExistingImages(imageTag)
        if (existingImages.isNotEmpty()) {
            logger.system("Image already exists: $imageTag")
            return existingImages.maxByOrNull { it.created }?.id
                ?: throw IllegalStateException("No images found after filtering")
        }

        val callback = BuildImageResultCallback()
        dockerClient.buildImageCmd()
            .withBaseDirectory(File("."))
            .withTarInputStream(Files.newInputStream(tarFile.toPath()))
            .withTags(setOf(imageTag))
            .exec(callback)

        return callback.awaitImageId()
    }

    private fun findExistingImageId(imageTag: String): String? {
        return findExistingImages(imageTag).maxByOrNull { it.created }?.id
    }

    private fun findExistingImages(imageTag: String): List<Image> {
        val existingImages = dockerClient.listImagesCmd().exec() // Obtener todas las imágenes.

        return existingImages.filter { image ->
            image.repoTags.any { tag ->
                tag.contains(imageTag)
            }
        }
    }
}
