package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.Agent
import dev.rubentxu.pipeline.model.pipeline.AnyAgent
import dev.rubentxu.pipeline.model.pipeline.DockerAgent
import dev.rubentxu.pipeline.model.pipeline.KubernetesAgent

/**
 * DSL builder for configuring pipeline execution agents.
 *
 * AgentBlock provides a type-safe DSL for specifying where and how a pipeline
 * should execute. Agents define the execution environment, whether it's a local
 * machine, Docker container, or Kubernetes pod. This abstraction allows pipelines
 * to be portable across different execution environments.
 *
 * ## Supported Agent Types
 * - **Any Agent**: Executes on any available agent in the pipeline system
 * - **Docker Agent**: Executes within a specified Docker container
 * - **Kubernetes Agent**: Executes within a Kubernetes pod
 *
 * ## Usage Example
 * ```kotlin
 * pipeline {
 *     agent {
 *         docker {
 *             image = "openjdk"
 *             tag = "21-jdk"
 *             host = "unix:///var/run/docker.sock"
 *         }
 *     }
 *     stages {
 *         stage("Build") {
 *             steps {
 *                 sh("./gradlew build")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Agent Selection Strategy
 * The agent configuration determines:
 * - **Execution Environment**: Where steps run (local, container, cluster)
 * - **Resource Allocation**: CPU, memory, and storage constraints
 * - **Security Context**: User permissions and system access
 * - **Tool Availability**: Pre-installed tools and dependencies
 *
 * @property agent The configured agent instance that will execute the pipeline
 * 
 * @since 1.0.0
 * @see Agent
 * @see AnyAgent
 * @see DockerAgent
 * @see KubernetesAgent
 */
@PipelineDsl
class AgentBlock() {
    lateinit var agent: Agent

    /**
     * Configures the pipeline to run on any available agent.
     *
     * This is the most flexible agent configuration, allowing the pipeline
     * system to select any suitable agent for execution. Useful for simple
     * pipelines that don't have specific environment requirements.
     *
     * ## Example
     * ```kotlin
     * agent {
     *     any {
     *         label = "general-purpose"
     *     }
     * }
     * ```
     *
     * @param block Configuration block for the any agent
     * @see AnyAgentBlock
     * @since 1.0.0
     */
    fun any(block: AnyAgentBlock.() -> Unit) {
        this.agent = AnyAgentBlock().apply(block).build()
    }

    /**
     * Configures the pipeline to run within a Docker container.
     *
     * Docker agents provide isolated, reproducible execution environments
     * with specific tool versions and dependencies. This is ideal for
     * ensuring consistent builds across different environments.
     *
     * ## Example
     * ```kotlin
     * agent {
     *     docker {
     *         image = "maven"
     *         tag = "3.8.4-openjdk-17"
     *         host = "tcp://docker-host:2376"
     *     }
     * }
     * ```
     *
     * @param block Configuration block for the Docker agent
     * @see DockerAgentBlock
     * @since 1.0.0
     */
    fun docker(block: DockerAgentBlock.() -> Unit) {
        this.agent = DockerAgentBlock().apply(block).build()
    }

    /**
     * Configures the pipeline to run within a Kubernetes pod.
     *
     * Kubernetes agents enable cloud-native pipeline execution with
     * advanced features like resource scaling, persistent volumes,
     * and service discovery. Ideal for complex, distributed pipelines.
     *
     * ## Example
     * ```kotlin
     * agent {
     *     kubernetes {
     *         yaml = """
     *             apiVersion: v1
     *             kind: Pod
     *             spec:
     *               containers:
     *               - name: build
     *                 image: gradle:7.4.2-jdk17
     *                 resources:
     *                   requests:
     *                     memory: "1Gi"
     *                     cpu: "500m"
     *         """
     *     }
     * }
     * ```
     *
     * @param block Configuration block for the Kubernetes agent
     * @see KubernetesAgentBlock
     * @since 1.0.0
     */
    fun kubernetes(block: KubernetesAgentBlock.() -> Unit) {
        this.agent = KubernetesAgentBlock().apply(block).build()
    }
}

/**
 * DSL builder for configuring "any" agent execution.
 *
 * AnyAgentBlock provides configuration for the most flexible agent type,
 * which allows the pipeline system to select any available agent for execution.
 * This is useful for simple pipelines that don't have specific environment
 * requirements or when maximum flexibility is desired.
 *
 * ## Usage Example
 * ```kotlin
 * agent {
 *     any {
 *         label = "general-purpose"
 *     }
 * }
 * ```
 *
 * @property label A descriptive label for the agent configuration
 * 
 * @since 1.0.0
 * @see AgentBlock
 * @see AnyAgent
 */
@PipelineDsl
class AnyAgentBlock {
    var label: String = "any"
    
    /**
     * Builds an AnyAgent instance with the configured properties.
     *
     * @return A configured AnyAgent instance
     * @see AnyAgent
     * @since 1.0.0
     */
    fun build(): Agent {
        return AnyAgent(label = label)
    }
}


/**
 * DSL builder for configuring Docker container agent execution.
 *
 * DockerAgentBlock provides configuration for running pipeline steps within
 * Docker containers. This enables reproducible builds with specific tool
 * versions and dependencies, ensuring consistent execution across different
 * environments.
 *
 * ## Configuration Properties
 * - **image**: The Docker image name (e.g., "openjdk", "maven", "gradle")
 * - **tag**: The specific image tag/version (e.g., "21-jdk", "3.8.4-openjdk-17")
 * - **host**: The Docker daemon host (e.g., "unix:///var/run/docker.sock")
 * - **label**: A descriptive label for the agent configuration
 *
 * ## Usage Example
 * ```kotlin
 * agent {
 *     docker {
 *         image = "gradle"
 *         tag = "7.4.2-jdk17"
 *         host = "unix:///var/run/docker.sock"
 *         label = "gradle-build"
 *     }
 * }
 * ```
 *
 * ## Docker Host Configuration
 * The host property supports various Docker daemon connection methods:
 * - **Unix socket**: `unix:///var/run/docker.sock` (default on Linux/macOS)
 * - **TCP**: `tcp://docker-host:2376` (for remote Docker daemons)
 * - **TLS**: `tcp://docker-host:2376` with TLS certificates
 *
 * @property label A descriptive label for the agent configuration
 * @property image The Docker image name to use for execution
 * @property tag The specific image tag/version to use
 * @property host The Docker daemon host connection string
 * 
 * @since 1.0.0
 * @see AgentBlock
 * @see DockerAgent
 */
@PipelineDsl
class DockerAgentBlock {
    var label: String = "docker"
    var image: String = ""
    var tag: String = ""
    var host: String = ""

    /**
     * Builds a DockerAgent instance with the configured properties.
     *
     * @return A configured DockerAgent instance
     * @see DockerAgent
     * @since 1.0.0
     */
    fun build(): Agent {
        return DockerAgent(label = label, image = image, tag = tag, host = host)
    }
}

/**
 * DSL builder for configuring Kubernetes pod agent execution.
 *
 * KubernetesAgentBlock provides configuration for running pipeline steps within
 * Kubernetes pods. This enables cloud-native pipeline execution with advanced
 * features like resource scaling, persistent volumes, service discovery, and
 * complex multi-container environments.
 *
 * ## Configuration Properties
 * - **yaml**: Complete pod specification in YAML format
 * - **label**: A descriptive label for the agent configuration
 *
 * ## Usage Example
 * ```kotlin
 * agent {
 *     kubernetes {
 *         label = "build-pod"
 *         yaml = """
 *             apiVersion: v1
 *             kind: Pod
 *             metadata:
 *               name: build-pod
 *             spec:
 *               containers:
 *               - name: gradle
 *                 image: gradle:7.4.2-jdk17
 *                 resources:
 *                   requests:
 *                     memory: "1Gi"
 *                     cpu: "500m"
 *                   limits:
 *                     memory: "2Gi"
 *                     cpu: "1000m"
 *                 volumeMounts:
 *                 - name: workspace
 *                   mountPath: /workspace
 *               volumes:
 *               - name: workspace
 *                 emptyDir: {}
 *         """
 *     }
 * }
 * ```
 *
 * ## Kubernetes Features
 * The YAML configuration supports full Kubernetes pod specifications:
 * - **Multi-container**: Multiple containers in a single pod
 * - **Resource limits**: CPU and memory constraints
 * - **Volumes**: Persistent and ephemeral storage
 * - **Service accounts**: Security and permissions
 * - **Labels and annotations**: Metadata and selectors
 * - **Init containers**: Setup and preparation containers
 *
 * @property label A descriptive label for the agent configuration
 * @property yaml The complete Kubernetes pod specification in YAML format
 * 
 * @since 1.0.0
 * @see AgentBlock
 * @see KubernetesAgent
 */
@PipelineDsl
class KubernetesAgentBlock {
    var label: String = "kubernetes"
    var yaml: String = ""

    /**
     * Builds a KubernetesAgent instance with the configured properties.
     *
     * @return A configured KubernetesAgent instance
     * @see KubernetesAgent
     * @since 1.0.0
     */
    fun build(): Agent {
        return KubernetesAgent(label = label, yaml = yaml)
    }
}
