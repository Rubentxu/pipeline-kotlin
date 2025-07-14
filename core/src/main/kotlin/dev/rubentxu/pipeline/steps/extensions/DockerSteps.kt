package dev.rubentxu.pipeline.steps.extensions

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.steps.annotations.Step
import dev.rubentxu.pipeline.steps.annotations.StepCategory
import dev.rubentxu.pipeline.steps.annotations.SecurityLevel
import dev.rubentxu.pipeline.steps.builtin.sh

/**
 * Docker-related @Step functions with DSL v2 syntax.
 * 
 * These steps provide Docker functionality with automatic PipelineContext injection
 * via the K2 compiler plugin, eliminating manual context access.
 */

/**
 * Builds a Docker image from a Dockerfile.
 * 
 * @param imageName Name and tag for the built image (e.g., "myapp:latest")
 * @param dockerfilePath Path to Dockerfile (default: "Dockerfile")
 * @param buildContext Build context directory (default: ".")
 * @param buildArgs Map of build arguments to pass to Docker build
 * @param noCache Whether to build without using cache (default: false)
 * @return Image ID of the built image
 */
@Step(
    name = "dockerBuild",
    description = "Build Docker image from Dockerfile",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerBuild(
    context: PipelineContext,
    imageName: String,
    dockerfilePath: String = "Dockerfile",
    buildContext: String = ".",
    buildArgs: Map<String, String> = emptyMap(),
    noCache: Boolean = false
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(imageName.isNotBlank()) { "Image name cannot be blank" }
    
    context.logger.info("+ dockerBuild: $imageName")
    
    // Verify Docker is available
    sh(context, "docker --version")
    
    // Verify Dockerfile exists
    val dockerfilePath = context.workingDirectory.resolve(dockerfilePath)
    if (!dockerfilePath.toFile().exists()) {
        throw IllegalArgumentException("Dockerfile not found: $dockerfilePath")
    }
    
    // Build Docker command
    val buildArgsString = buildArgs.map { (key, value) -> "--build-arg $key=$value" }.joinToString(" ")
    val noCacheFlag = if (noCache) "--no-cache" else ""
    
    val command = listOf(
        "docker build",
        "-t $imageName",
        "-f $dockerfilePath",
        buildArgsString,
        noCacheFlag,
        buildContext
    ).filter { it.isNotBlank() }.joinToString(" ")
    
    sh(context, command)
    
    // Get the image ID
    val imageId = sh(context, "docker images -q $imageName", returnStdout = true).trim()
    
    context.logger.info("Successfully built Docker image: $imageName (ID: $imageId)")
    return imageId
}

/**
 * Runs a Docker container from an image.
 * 
 * @param imageName Docker image name to run
 * @param containerName Optional name for the container
 * @param ports Map of host ports to container ports (e.g., mapOf("8080" to "80"))
 * @param environment Map of environment variables
 * @param volumes Map of host paths to container paths for volume mounts
 * @param detached Whether to run in detached mode (default: true)
 * @param removeAfterExit Whether to remove container after exit (default: true)
 * @return Container ID
 */
@Step(
    name = "dockerRun",
    description = "Run Docker container from image",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerRun(
    context: PipelineContext,
    imageName: String,
    containerName: String? = null,
    ports: Map<String, String> = emptyMap(),
    environment: Map<String, String> = emptyMap(),
    volumes: Map<String, String> = emptyMap(),
    detached: Boolean = true,
    removeAfterExit: Boolean = true
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(imageName.isNotBlank()) { "Image name cannot be blank" }
    
    context.logger.info("+ dockerRun: $imageName")
    
    // Build Docker run command
    val dockerCmd = mutableListOf("docker run")
    
    if (detached) dockerCmd.add("-d")
    if (removeAfterExit) dockerCmd.add("--rm")
    
    containerName?.let { dockerCmd.addAll(listOf("--name", it)) }
    
    ports.forEach { (hostPort, containerPort) ->
        dockerCmd.addAll(listOf("-p", "$hostPort:$containerPort"))
    }
    
    environment.forEach { (key, value) ->
        dockerCmd.addAll(listOf("-e", "$key=$value"))
    }
    
    volumes.forEach { (hostPath, containerPath) ->
        dockerCmd.addAll(listOf("-v", "$hostPath:$containerPath"))
    }
    
    dockerCmd.add(imageName)
    
    val command = dockerCmd.joinToString(" ")
    val containerId = sh(context, command, returnStdout = true).trim()
    
    context.logger.info("Started Docker container: $containerId")
    return containerId
}

/**
 * Stops and removes a Docker container.
 * 
 * @param containerIdOrName Container ID or name to stop
 * @param force Whether to force stop the container (default: false)
 */
@Step(
    name = "dockerStop",
    description = "Stop and remove Docker container",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerStop(
    context: PipelineContext,
    containerIdOrName: String,
    force: Boolean = false
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(containerIdOrName.isNotBlank()) { "Container ID or name cannot be blank" }
    
    context.logger.info("+ dockerStop: $containerIdOrName")
    
    try {
        if (force) {
            sh(context, "docker kill $containerIdOrName")
        } else {
            sh(context, "docker stop $containerIdOrName")
        }
        
        // Remove the container if it wasn't started with --rm
        sh(context, "docker rm $containerIdOrName")
        
        context.logger.info("Stopped and removed container: $containerIdOrName")
    } catch (e: Exception) {
        context.logger.warn("Failed to stop container $containerIdOrName: ${e.message}")
        throw e
    }
}

/**
 * Pushes a Docker image to a registry.
 * 
 * @param imageName Full image name including registry (e.g., "registry.com/myapp:latest")
 * @param registry Registry URL (optional if included in imageName)
 * @param username Registry username (optional, uses docker login if not provided)
 * @param password Registry password (optional, uses docker login if not provided)
 */
@Step(
    name = "dockerPush",
    description = "Push Docker image to registry",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerPush(
    context: PipelineContext,
    imageName: String,
    registry: String? = null,
    username: String? = null,
    password: String? = null
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(imageName.isNotBlank()) { "Image name cannot be blank" }
    
    context.logger.info("+ dockerPush: $imageName")
    
    // Login to registry if credentials provided
    if (username != null && password != null) {
        val registryUrl = registry ?: imageName.substringBefore("/")
        context.logger.info("Logging into registry: $registryUrl")
        sh(context, "echo '$password' | docker login $registryUrl -u $username --password-stdin")
    }
    
    // Push the image
    sh(context, "docker push $imageName")
    
    context.logger.info("Successfully pushed image: $imageName")
}

/**
 * Pulls a Docker image from a registry.
 * 
 * @param imageName Image name to pull (e.g., "nginx:latest")
 * @return Image ID of the pulled image
 */
@Step(
    name = "dockerPull",
    description = "Pull Docker image from registry",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerPull(context: PipelineContext, imageName: String): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(imageName.isNotBlank()) { "Image name cannot be blank" }
    
    context.logger.info("+ dockerPull: $imageName")
    
    sh(context, "docker pull $imageName")
    
    val imageId = sh(context, "docker images -q $imageName", returnStdout = true).trim()
    
    context.logger.info("Successfully pulled image: $imageName (ID: $imageId)")
    return imageId
}

/**
 * Executes a command inside a running Docker container.
 * 
 * @param containerIdOrName Container ID or name
 * @param command Command to execute
 * @param workingDir Working directory inside container (optional)
 * @param user User to run command as (optional)
 * @param returnStdout Whether to return stdout (default: false)
 * @return Command output if returnStdout is true
 */
@Step(
    name = "dockerExec",
    description = "Execute command inside Docker container",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerExec(
    context: PipelineContext,
    containerIdOrName: String,
    command: String,
    workingDir: String? = null,
    user: String? = null,
    returnStdout: Boolean = false
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(containerIdOrName.isNotBlank()) { "Container ID or name cannot be blank" }
    require(command.isNotBlank()) { "Command cannot be blank" }
    
    context.logger.info("+ dockerExec: $containerIdOrName -> $command")
    
    val dockerCmd = mutableListOf("docker exec")
    
    workingDir?.let { dockerCmd.addAll(listOf("-w", it)) }
    user?.let { dockerCmd.addAll(listOf("-u", it)) }
    
    dockerCmd.add(containerIdOrName)
    dockerCmd.add(command)
    
    val fullCommand = dockerCmd.joinToString(" ")
    return sh(context, fullCommand, returnStdout = returnStdout)
}

/**
 * Lists running Docker containers.
 * 
 * @param all Whether to show all containers including stopped ones (default: false)
 * @param format Output format (default: "table")
 * @return Container information
 */
@Step(
    name = "dockerPs",
    description = "List Docker containers",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerPs(
    context: PipelineContext,
    all: Boolean = false,
    format: String = "table"
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    context.logger.info("+ dockerPs")
    
    val allFlag = if (all) "-a" else ""
    val formatFlag = if (format != "table") "--format '$format'" else ""
    
    val command = listOf("docker ps", allFlag, formatFlag)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    
    return sh(context, command, returnStdout = true)
}

/**
 * Removes Docker images.
 * 
 * @param imageNames List of image names or IDs to remove
 * @param force Whether to force removal (default: false)
 * @param noPrune Whether to not delete untagged parents (default: false)
 */
@Step(
    name = "dockerRmi",
    description = "Remove Docker images",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun dockerRmi(
    context: PipelineContext,
    imageNames: List<String>,
    force: Boolean = false,
    noPrune: Boolean = false
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(imageNames.isNotEmpty()) { "At least one image name must be provided" }
    
    context.logger.info("+ dockerRmi: ${imageNames.joinToString(", ")}")
    
    val flags = mutableListOf<String>()
    if (force) flags.add("-f")
    if (noPrune) flags.add("--no-prune")
    
    val command = listOf("docker rmi")
        .plus(flags)
        .plus(imageNames)
        .joinToString(" ")
    
    sh(context, command)
    
    context.logger.info("Removed images: ${imageNames.joinToString(", ")}")
}