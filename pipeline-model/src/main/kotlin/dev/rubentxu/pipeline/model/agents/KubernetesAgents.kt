package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.steps.EnvVars

data class KubernetesAgent(
    override val id: IDComponent,
    override val name: String,
    override val labels: List<String>,
    val serverUrl: String,
    val serverCertificate: String,
    val skipTlsVerify: Boolean,
    val credentialsId: String,
    val namespace: String,
    val pipelineUrl: String,
    val pipelineTunnel: String,
    val containerCapStr: Int,
    val maxRequestsPerHostStr: Int,
    val retentionTimeout: Int,
    val connectTimeout: Int,
    val readTimeout: Int,
    val templates: List<K8sTemplate>,
) : Agent

data class K8sTemplate(
    val name: String,
    val serviceAccount: String?,
    val instanceCap: Int?,
    val idleMinutes: Int?,
    val label: String?,
    val showRawYaml: Boolean?,
    val volumes: List<Volume>?,
    val containers: List<Container>,
    val imagePullSecrets: List<ImagePullSecret>?,
    val envVars: EnvVars?,
) : Template

data class Volume(
    val hostPathVolume: HostPathVolume?,
    val emptyDirVolume: EmptyDirVolume?,
    val configMapVolume: ConfigMapVolume?,
) : PipelineDomain

data class HostPathVolume(
    val mountPath: String,
    val hostPath: String,
) : PipelineDomain

data class EmptyDirVolume(
    val memory: Boolean,
    val mountPath: String,
) : PipelineDomain

data class ConfigMapVolume(
    val configMapName: String,
    val mountPath: String,
    val subPath: String,
) : PipelineDomain

data class Container(
    val name: String,
    val image: String,
    val privileged: Boolean,
    val alwaysPullImage: Boolean,
    val command: String,
    val args: String,
    val workingDir: String,
    val ttyEnabled: Boolean,
    val resourceRequestCpu: String,
    val resourceRequestMemory: String,
    val resourceLimitCpu: String,
    val resourceLimitMemory: String,
) : PipelineDomain

data class ImagePullSecret(
    val name: String,
) : PipelineDomain
