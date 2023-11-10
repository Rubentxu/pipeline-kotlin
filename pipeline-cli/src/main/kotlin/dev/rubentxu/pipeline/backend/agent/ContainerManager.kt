package dev.rubentxu.pipeline.backend.agent


import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.BuildImageResultCallback
import com.github.dockerjava.api.command.LogContainerCmd
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Image
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.core.command.LogContainerResultCallback
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rubentxu.pipeline.backend.Config
import dev.rubentxu.pipeline.dsl.DockerAgent
import dev.rubentxu.pipeline.logger.PipelineLogger
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.SystemUtils
import java.io.*
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.Comparator


private const val IMAGE_NAME = "pipeline-kts"

class ContainerManager(val agent: DockerAgent, val config: Config, val logger: PipelineLogger) {


    private val dockerClientConfig: DefaultDockerClientConfig by lazy {
        dockerConfig()
    }

    private val dockerClient: DockerClient by lazy {
        createContainerClient()
    }

    private val httpClient: DockerHttpClient by lazy {
        createHttpClient()
    }

    lateinit var configHost: String

    fun dockerConfig(): DefaultDockerClientConfig {
        configHost = if (agent.host.isEmpty()) {
            if (SystemUtils.IS_OS_WINDOWS) {
                "tcp://localhost:2375"
            } else {
                "unix:///var/run/docker.sock"
            }
        } else {
            agent.host
        }

        return DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(configHost)
            .build()
    }


    private fun createHttpClient(): DockerHttpClient {
        return ZerodepDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()
    }

    private fun createContainerClient(): DockerClient {
        return DockerClientImpl.getInstance(dockerClientConfig, httpClient)
    }


    fun ping() {
        val request: DockerHttpClient.Request = DockerHttpClient.Request.builder()
            .method(DockerHttpClient.Request.Method.GET)
            .path("/_ping")
            .build()

        httpClient.execute(request).use { response ->
            assert(response.getStatusCode() == 200, { "Docker daemon is not running" })
            assert(
                IOUtils.toString(response.getBody(), Charset.defaultCharset()) == "OK",
                { "Docker daemon is not running" })
        }
    }

    fun createContainer(environment: Map<String, String>): String {

        val uniqueContainerName = "$IMAGE_NAME-${UUID.randomUUID()}"


        val result = dockerClient.createContainerCmd(IMAGE_NAME)
            .withName(uniqueContainerName)
            .withEnv(environment.map { "${it.key}=${it.value}" })
            .exec()

        return result.id
    }

    fun waitForContainer(containerId: String) {
        dockerClient.waitContainerCmd(containerId)
            .exec(WaitContainerResultCallback())
            .awaitCompletion() // Bloquea y espera a que el contenedor termine.
    }

    fun createAndStartContainer(environment: Map<String, String>): String {
        // Crear el contenedor (o reutilizar el lógica si ya existe).
        val containerId = createContainer(environment)

        // Iniciar el contenedor.
        dockerClient.startContainerCmd(containerId).exec()

        // Visualizar las trazas del contenedor en tiempo real.
        showContainerLogs(containerId)

        // Esperar a que el contenedor termine su ejecución.
        waitForContainer(containerId)

        return containerId
    }


    fun removeContainerIfExists(containerName: String) {
        try {

            val containers = pipelineContainers(containerName)
            val containerId = containers.firstOrNull()?.id

            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId)
                    .withForce(true) // Forzar la eliminación del contenedor si está en ejecución
                    .exec()
            }
        } catch (e: NotFoundException) {
            // El contenedor no existe, por lo que no hay nada que eliminar.
            logger.info("Container $containerName does not exist")
        }
    }

    fun restartContainer(containerName: String, environment: Map<String, String>): String {
        // Intenta obtener el contenedor por nombre.
        val container = dockerClient.listContainersCmd()
            .withNameFilter(listOf(containerName))
            .withShowAll(true) // Importante para obtener contenedores que no estén en ejecución.
            .exec()
            .firstOrNull()

        container?.let {
            // Si el contenedor existe, detenerlo.
            dockerClient.stopContainerCmd(it.id).exec()

            // Opcional: actualizar el entorno del contenedor si es necesario.
            // Esto podría implicar la creación de un nuevo contenedor si se necesitan cambios en el entorno.

            // Reiniciar el contenedor.
            dockerClient.startContainerCmd(it.id).exec()

            return it.id
        } ?: run {
            // Si el contenedor no existe, crear uno nuevo.
            val result = dockerClient.createContainerCmd(IMAGE_NAME)
                .withName(containerName)
                .withEnv(environment.map { "${it.key}=${it.value}" })
                .exec()

            // Iniciar el nuevo contenedor.
            dockerClient.startContainerCmd(result.id).exec()

            return result.id
        }
    }

    fun buildCustomImage(baseImage: String, paths: List<Path>): String {

        // Transformar la lista en un mapa
        val binding = mapOf("baseImage" to baseImage)

        val buildDir = Path.of(System.getProperty("user.dir")).resolve("build").resolve("dockerContext")
        logger.system("Create Build dir: $buildDir")
        if (Files.exists(buildDir)) {
            Files.walk(buildDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        Files.createDirectories(buildDir)

        // create temp file with content rendered template
        val dockerfile = renderDockerfile(binding, buildDir)
// Obtén el directorio de ejecución de la aplicación y añade el subdirectorio 'build'
        // add Dockerfile to paths
        val pathsWithDockerfile = paths + listOf(dockerfile.toPath())
        logger.system("Create paths with Dockerfile: $pathsWithDockerfile")

        val tarFile = createTarFile(pathsWithDockerfile, buildDir)
        logger.system("Create tar file: $tarFile")

        val imageTag = IMAGE_NAME

        // Comprobar si la imagen ya existe
        val existingImages = pipelineImages(imageTag)


        if (existingImages.isEmpty()) {
            // Si la imagen no existe, construir la imagen
            val callback = BuildImageResultCallback()

            val result = dockerClient.buildImageCmd()
                .withBaseDirectory(File(".")) //
                .withTarInputStream(Files.newInputStream(tarFile.toPath()))
                .withTags(setOf(imageTag))
                .exec(callback)

            return callback.awaitImageId()
        } else {
            logger.system("Image already exists: $imageTag")
            val mostRecentImage = existingImages.maxByOrNull { it.created } // Usar maxWithOrNull si usas una versión anterior de Kotlin
            return mostRecentImage?.id ?: throw IllegalStateException("No images found after filtering")
        }
    }

    fun showContainerLogs(containerId: String) {
        val latch = CountDownLatch(1)

        val logContainerCmd: LogContainerCmd = dockerClient.logContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTailAll()

        logContainerCmd.exec(object : LogContainerResultCallback() {
            override fun onNext(item: Frame) {
                println(String(item.payload).trim()) // Imprimir las trazas del contenedor.
            }

            override fun onComplete() {
                super.onComplete()
                latch.countDown() // Decrementar el latch cuando se completen los logs.
            }

            override fun onError(throwable: Throwable?) {
                println("Error al leer los logs del contenedor: ${throwable?.message}")
                latch.countDown() // Decrementar el latch en caso de error.
            }
        })

        latch.await() // Esperar a que los logs se hayan terminado de procesar.
    }


    private fun pipelineImages(imageTag: String): List<Image> {
        val existingImages = dockerClient.listImagesCmd().exec() // Obtener todas las imágenes.

        return existingImages.filter { image ->
            image.repoTags.any { tag ->
                tag.contains("pipeline-kts")
            }
        }

    }

    private fun pipelineContainers(imageTag: String): List<Container> {
        val existingContainers = dockerClient.listContainersCmd().exec() // Obtener todas las imágenes.

        return existingContainers.filter { container ->
            container.names.any { name ->
                name.contains("pipeline-kts")
            }

        }


    }

    private fun resolveFilename(filename: String): String {
        return when {
            filename.endsWith(".yaml") -> "app/config.yaml"
            filename.endsWith("pipeline.kts") -> "app/script.pipeline.kts"
            filename.endsWith(".jar") -> "app/pipeline-cli.jar"
            filename.endsWith("Dockerfile") -> "Dockerfile"
            else -> "app/$filename"
        }
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

    fun createTarFile(paths: List<Path>, buildDir: Path):File {
        // Asegúrate de que el directorio de compilación existe o créalo
        Files.createDirectories(buildDir)

        // Define el archivo de salida dentro del subdirectorio 'build'
        val tarFile = buildDir.resolve("context.tar").toFile()

        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(tarFile))).use { tarStream ->
            for (path in paths) {
                val file = path.toFile()

                // Verificar si el archivo existe
                if (!file.exists()) {
                    throw IllegalArgumentException("File does not exist: ${file.absolutePath}")
                }

                // Crear la entrada para el archivo en el archivo tar
                val tarEntry = TarArchiveEntry(file, resolveFilename(file.name))
                tarEntry.size = file.length()

                // Añadir la entrada al archivo tar
                tarStream.putArchiveEntry(tarEntry)
                file.inputStream().use { fileInputStream ->
                    fileInputStream.copyTo(tarStream)
                }
                tarStream.closeArchiveEntry()
            }
        }
        return tarFile
    }

    // Carga la plantilla de recursos
    fun loadResource(path: Path): String {
        val stream = javaClass.classLoader.getResourceAsStream(path.toString())
        return stream.bufferedReader().use{ it.readText() }
    }

    fun startContainer(container: Container) {
        dockerClient.startContainerCmd(container.id).exec()
    }

    fun stopContainer(container: Container) {
        dockerClient.stopContainerCmd(container.id).exec()
    }

    fun removeContainer(container: Container) {
        dockerClient.removeContainerCmd(container.id)
            .withForce(true)
            .withRemoveVolumes(true)
            .exec()
    }

    fun getContainerStatus(container: Container): String? {
        return dockerClient.inspectContainerCmd(container.id)
            .exec()
            .state
            .status
    }

    fun getContainerLogs(container: Container): String {
        val result = dockerClient.logContainerCmd(container.id)
            .withStdOut(true)
            .withStdErr(true)
            .exec(ResultCallback.Adapter())

        result.awaitCompletion()
        return result.toString()

    }

}