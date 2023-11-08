package dev.rubentxu.pipeline.backend.agent


import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.BuildImageResultCallback
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rubentxu.pipeline.backend.Config
import dev.rubentxu.pipeline.dsl.DockerAgent
import io.micronaut.core.io.ResourceResolver
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.SystemUtils
import java.io.*
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Duration


private const val IMAGE_NAME = "pipeline-kts:latest"

class ContainerManager(val agent: DockerAgent, config: Config) {


    lateinit var dockerClientConfig: DefaultDockerClientConfig
    lateinit var dockerClient: DockerClient
    lateinit var httpClient: DockerHttpClient
    lateinit var configHost: String

    fun dockerConfig(): DefaultDockerClientConfig {
        configHost = if (agent.host.isNotEmpty()) {
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

    private fun createContainerClient(): DockerClient {
        httpClient = ZerodepDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()



        return DockerClientImpl.getInstance(dockerClientConfig, httpClient)
    }

    init {
        dockerClientConfig = dockerConfig()
        dockerClient = createContainerClient()
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

        val result = dockerClient.createContainerCmd(IMAGE_NAME)
            .withName(IMAGE_NAME)
            .withEnv(environment.map { "${it.key}=${it.value}" })
            .exec()

        return result.id
    }

    fun buildCustomImage(baseImage: String, paths: List<Path>): String {

        // Transformar la lista en un mapa
        val binding = mapOf(
            "baseImage" to baseImage,
        )

        // create temp file with content rendered template
        val tempFile = renderDockerfile(binding)

        val tarOutputStream = createTarStream(paths)
        val tarInputStream = ByteArrayInputStream(tarOutputStream.toByteArray())

        val imageTag = IMAGE_NAME

        // Comprobar si la imagen ya existe
        val existingImages = dockerClient.listImagesCmd().withImageNameFilter(imageTag).exec()

        if (existingImages.isEmpty()) {
            // Si la imagen no existe, construir la imagen
            val callback = BuildImageResultCallback()

            val result = dockerClient.buildImageCmd()
                .withBaseDirectory(tempFile.parentFile)
                .withDockerfile(tempFile)
                .withTarInputStream(tarInputStream)
                .withTags(setOf(imageTag))
                .exec(callback)

            return callback.awaitImageId()
        } else {
            return existingImages[0].id
        }
    }

    private fun resolveFilename(name: String): String {
        if(name.endsWith(".yaml")) {
            return "app/config.yaml"
        } else if(name.endsWith("pipeline.kts")) {
            return "app/script.pipeline.kts"
        } else if(name.endsWith(".jar")) {
            return "app/pipeline-cli.jar"
        }
        return "app/$name"
    }

    fun renderDockerfile(binding: Map<String, String>): File {
        val dockerfileTemplateContent = loadResource(Path.of("templates/Dockerfile.tpl"))

        // Crear un PebbleEngine con un StringLoader
        val engine = PebbleEngine.Builder().loader(StringLoader()).build()

        // Obtener una plantilla
        val template: PebbleTemplate = engine.getTemplate(dockerfileTemplateContent)

        // Renderizar la plantilla
        val writer: Writer = StringWriter()
        template.evaluate(writer, binding)

        // Escribir el resultado en el archivo
        val dockerfile = File.createTempFile("Dockerfile", ".tmp")
        dockerfile.writeText(writer.toString())
        return dockerfile

    }

    fun createTarStream(paths: List<Path>): ByteArrayOutputStream {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val tarStream = TarArchiveOutputStream(byteArrayOutputStream)



        paths.forEach {
            val file = it.toFile()
            val bytes = file.readBytes()
            val tarEntry = TarArchiveEntry(resolveFilename(file.name))
            tarEntry.setSize(bytes.size.toLong())
            tarStream.putArchiveEntry(tarEntry)
            tarStream.write(bytes)
            tarStream.closeArchiveEntry()
        }
        tarStream.finish()

        // Obtener bytes del ByteArrayOutputStream

        return byteArrayOutputStream

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